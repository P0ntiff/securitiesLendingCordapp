package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow

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
    open class Terminator(val loanID : UniqueIdentifier) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val myKey = serviceHub.myInfo.legalIdentity.owningKey

            //STEP 1 Retrieve loan being terminated from the vault of the borrower, and notify the counterParty about the loan we're terminating
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            send(secLoan.state.data.lender, loanID)

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan states
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionType.General.Builder(notary = notary)
            SecurityLoan().generateExit(builder, secLoan, lender, borrower)

            //STEP 3: Add the security states to return to the lender
            val code = secLoan.state.data.code
            val quantity = secLoan.state.data.quantity
            val (tx, keysForSigning) = try {
                subFlow(SecuritiesPreparationFlow(builder, code, quantity, lender))
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }

            //STEP 4: Send the tx containing stock and the securityLoan to the lender
            val ptx = sendAndReceive<SignedTransaction>(lender, tx).unwrap {
                val wtx : WireTransaction = it.verifySignatures(myKey, notary.owningKey)
                //TODO: Check cash collateral has been returned to us by the lender
                if (wtx.outputs.map { it.data }.filterIsInstance<Cash.State>().filter {
                    (it.owner.owningKey == serviceHub.myInfo.legalIdentity.owningKey)
                }.sumByDouble { it.amount.quantity.toDouble() } != (((secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity) *
                        (1.0 + secLoan.state.data.terms.margin)))) {
                    throw FlowException("Lender is not giving us back the correct amount of cash collateral.")
                }
                subFlow(ResolveTransactionsFlow(wtx, secLoan.state.data.lender))
                it
            }

            //STEP 8: Sign and finalise transaction in both parties' vaults
            val stx = serviceHub.addSignature(ptx, myKey)
            subFlow(FinalityFlow(stx, setOf(lender, borrower))).single()
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
            serviceHub.vaultService.generateSpend(ptx,
                    Amount(cashToAdd, CURRENCY),
                    AnonymousParty(borrower.owningKey)
            )
            //STEP 7: Send this tx back to the borrower
            val stx = serviceHub.signInitialTransaction(ptx)
            send(borrower, stx)

            return Unit
        }
    }
}