package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow
import net.corda.flows.SignTransactionFlow

/**
 * Flow to model the conclusion of a security loan, creating a TXN with the following structure:
 *
 *  Commands: Exit/Terminate
 *  Inputs:  -Cash Collateral + Margin (from lender),
 *           -Securities (from borrower)
 *           -SecurityLoan (owned by lender)
 *  Outputs: -Cash (owned by borrower)
 *           -Securities (owned by lender)
 */

object LoanTerminationFlow {
    @StartableByRPC
    @InitiatingFlow
    //Borrower currently coded as the initiator/"terminator"
    open class Terminator(val loanID : UniqueIdentifier) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 1 Retrieve loan being terminated from the vault of the borrower, and notify the counterParty about the loan we're terminating
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            send(secLoan.state.data.lender, loanID)

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan input state
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionType.General.Builder(notary = notary)
            SecurityLoan().generateExit(builder, secLoan, lender, borrower)

            //STEP 3: Add the security states to return to the lender
            val code = secLoan.state.data.code
            val quantity = secLoan.state.data.quantity
            val tx = try {
                subFlow(SecuritiesPreparationFlow(builder, code, quantity, lender)).first
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }
            send(lender, tx)

            //STEP 4: Send the tx containing stock and the securityLoan to the lender
            val signTransactionFlow = object : SignTransactionFlow(secLoan.state.data.lender) {
                override fun checkTransaction(stx: SignedTransaction)  = requireThat {
                    "Lender must give us back the correct amount of cash collateral." using
                        (stx.tx.outputs.map { it.data }.filterIsInstance<Cash.State>().filter {
                        (it.owner.owningKey == serviceHub.myInfo.legalIdentity.owningKey)
                        }.sumByDouble { it.amount.quantity.toDouble() } ==
                                (((secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity) *
                            (1.0 + secLoan.state.data.terms.margin))))
                }
            }

            //STEP 8: Sign and finalise transaction in both parties' vaults
            subFlow(signTransactionFlow)

            return Unit
        }
    }

    @StartableByRPC
    @InitiatedBy(Terminator::class)
    class TerminationAcceptor(val borrower : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 5: Receive information about the loan being terminated from borrower
            val loanID = receive<UniqueIdentifier>(borrower). unwrap { it }
            val secLoan = subFlow(LoanRetrievalFlow(loanID))

            //STEP 6:Receive the tx builder and and add the required cash states
            val ptx = receive<TransactionBuilder>(borrower).unwrap {
                //TODO: Check we want to settle this loan/the total loan time has elapsed
                //Check the securities have been returned to us
                if (it.outputStates().map { it.data }.filterIsInstance<SecurityClaim.State>().filter {
                    (it.owner.owningKey == serviceHub.myInfo.legalIdentity.owningKey) &&
                            (it.code == secLoan.state.data.code)
                }.sumBy { it.quantity } != (secLoan.state.data.quantity)) {
                    throw FlowException("Borrower is not giving all of the securities back")
                }
                it
            }
            val cashToAdd: Long = ((secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity.toInt()) *
                    (1.0 + secLoan.state.data.terms.margin)).toLong()
            serviceHub.vaultService.generateSpend(ptx, Amount(cashToAdd, CURRENCY), AnonymousParty(borrower.owningKey))

            //STEP 7: Send this tx back to the borrower
            val stx = serviceHub.signInitialTransaction(ptx)
            val fullySignedTX = subFlow(CollectSignaturesFlow(stx))
            subFlow(FinalityFlow(fullySignedTX)).single()

            return Unit
        }
    }
}