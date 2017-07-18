package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import com.secLendModel.flow.securitiesLending.LoanChecks.isLender
import com.secLendModel.flow.securitiesLending.LoanChecks.stateToLoanTerms
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
    open class Terminator(val loanID : UniqueIdentifier) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 1 Retrieve loan being terminated from the vault, and notify the counterParty about the loan we're terminating
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            val secLoanTerms = stateToLoanTerms(secLoan.state.data)
            val counterParty = LoanChecks.getCounterParty(secLoanTerms, serviceHub.myInfo.legalIdentity)
            send(counterParty, loanID)

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan input state
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionType.General.Builder(notary = notary)
            SecurityLoan().generateExit(builder, secLoan, lender, borrower)

            //STEP 3: Return either cash or securities, depending on which party we are in the deal.
            val ptx : TransactionBuilder
            //If we are the lender, then we are returning cash collateral
            if (isLender(secLoanTerms, serviceHub.myInfo.legalIdentity)) {
                ptx = serviceHub.vaultService.generateSpend(builder,
                        Amount(((secLoanTerms.stockPrice.quantity * secLoanTerms.quantity) * (1.0 + secLoanTerms.margin)).toLong(), CURRENCY),
                        AnonymousParty(counterParty.owningKey)).first
            }
            else{  //If we are the borrower, then we are returning stock to the lender
                ptx = try {
                    subFlow(SecuritiesPreparationFlow(builder, secLoanTerms.code, secLoanTerms.quantity, counterParty)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }
            val stx = sendAndReceive<SignedTransaction>(counterParty, ptx).unwrap {
                val wtx: WireTransaction = it.verifySignatures(serviceHub.myInfo.legalIdentity.owningKey,
                        serviceHub.networkMapCache.notaryNodes.single().notaryIdentity.owningKey)
                //Check txn dependency chain ("resolution")
                subFlow(ResolveTransactionsFlow(wtx, counterParty))

                it
            }
            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentity.owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty))).single()
            //return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId


            //STEP 8: Sign and finalise transaction in both parties' vaults
            //subFlow(signTransactionFlow)

            return Unit
        }
    }

    @StartableByRPC
    @InitiatedBy(Terminator::class)
    class TerminationAcceptor(val counterParty : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 5: Receive information about the loan being terminated from borrower
            val loanID = receive<UniqueIdentifier>(counterParty). unwrap { it }
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            val secLoanTerms = stateToLoanTerms(secLoan.state.data)

            //STEP 6:Receive the tx builder and and add the required cash states
            val ptx = receive<TransactionBuilder>(counterParty).unwrap {
                //TODO: Check we want to settle this loan/the total loan time has elapsed
//                //Check the securities have been returned to us
//                if (it.outputStates().map { it.data }.filterIsInstance<SecurityClaim.State>().filter {
//                    (it.owner.owningKey == serviceHub.myInfo.legalIdentity.owningKey) &&
//                            (it.code == secLoan.state.data.code)
//                }.sumBy { it.quantity } != (secLoan.state.data.quantity)) {
//                    throw FlowException("Borrower is not giving all of the securities back")
//                }
                it
            }
            val tx : TransactionBuilder
            if (isLender(secLoanTerms, serviceHub.myInfo.legalIdentity)) {
                //We are lender -> should send back cash collateral to the borrower
                tx = serviceHub.vaultService.generateSpend(ptx,
                        Amount(((secLoanTerms.stockPrice.quantity * secLoanTerms.quantity) * (1.0 + secLoanTerms.margin)).toLong(), CURRENCY),
                        AnonymousParty(counterParty.owningKey)).first
            }
            else{ //We are the borrower -> should send back stock to the lender
                tx = try {
                    subFlow(SecuritiesPreparationFlow(ptx, secLoanTerms.code, secLoanTerms.quantity, counterParty)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }

            //STEP 7: Send this tx back to the borrower
            val stx = serviceHub.signInitialTransaction(tx)
            send(counterParty, stx)

            return Unit
        }
    }
}