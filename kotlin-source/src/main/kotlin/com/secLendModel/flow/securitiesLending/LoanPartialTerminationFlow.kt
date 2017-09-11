package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.CollateralPreparationFlow
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow

/**
 * Created by raymondm on 31/08/2017.
 */


object LoanPartialTerminationFlowTerminationFlow {
    @StartableByRPC
    @InitiatingFlow
    open class PartTerminator(val loanID: UniqueIdentifier, val amountToTerminate: Int) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 1 Retrieve loan being terminated from the vault, and notify the counterParty about the loan we're terminating
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            val secLoanTerms = LoanChecks.stateToLoanTerms(secLoan.state.data)
            val counterParty = LoanChecks.getCounterParty(secLoanTerms, serviceHub.myInfo.legalIdentity)
            send(counterParty, Pair(loanID, amountToTerminate))

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan input state and the new security output state
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionType.General.Builder(notary = notary)
            SecurityLoan().generatePartialExit(builder, secLoan, secLoan.state.data.quantity - amountToTerminate, lender, borrower, notary)

            //STEP 3: Return either cash or securities, depending on which party we are in the deal.
            val ptx: TransactionBuilder
            //If we are the lender, then we are returning cash collateral for the correct amount of shares being returned
            if (LoanChecks.isLender(secLoanTerms, serviceHub.myInfo.legalIdentity)) {
                //Collateral is no longer just cashm so we add in the correct collateral here
                ptx = subFlow(CollateralPreparationFlow(builder, "Cash",
                        ((secLoan.state.data.stockPrice.quantity * secLoan.state.data.quantity) * (1.0 + secLoanTerms.margin)).toLong(), secLoan.state.data.lender))
                //ptx = serviceHub.vaultService.generateSpend(builder,
                  //      Amount(((secLoanTerms.stockPrice.quantity * amountToTerminate) * (1.0 + secLoanTerms.margin)).toLong(), CURRENCY),
                    //    AnonymousParty(counterParty.owningKey)).first
            } else {  //If we are the borrower, then we are returning stock to the lender
                ptx = try {
                    subFlow(SecuritiesPreparationFlow(builder, secLoanTerms.code, amountToTerminate, counterParty)).first
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

            return Unit
        }
    }

    @StartableByRPC
    @InitiatedBy(PartTerminator::class)
    class PartTerminationAcceptor(val counterParty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 5: Receive information about the loan being terminated from borrower
            val loanID = receive<Pair<UniqueIdentifier, Int>>(counterParty).unwrap { it }
            val secLoan = subFlow(LoanRetrievalFlow(loanID.first))
            val secLoanTerms = LoanChecks.stateToLoanTerms(secLoan.state.data)
            val amountToTerminate = loanID.second
            //STEP 6:Receive the tx builder and and add the required cash states
            val ptx = receive<TransactionBuilder>(counterParty).unwrap {
                //TODO: Check we want to accept this partial termination
                it
            }
            val tx: TransactionBuilder
            if (LoanChecks.isLender(secLoanTerms, serviceHub.myInfo.legalIdentity)) {
                //We are lender -> should send back cash collateral to the borrower
                tx = subFlow(CollateralPreparationFlow(ptx, "Cash",
                        ((secLoanTerms.stockPrice.quantity * amountToTerminate) * (1.0 + secLoanTerms.margin)).toLong(), counterParty))
                //tx = serviceHub.vaultService.generateSpend(ptx,
                  //      Amount(((secLoanTerms.stockPrice.quantity * amountToTerminate) * (1.0 + secLoanTerms.margin)).toLong(), CURRENCY),
                    //    AnonymousParty(counterParty.owningKey)).first
            } else { //We are the borrower -> should send back stock to the lender
                tx = try {
                    subFlow(SecuritiesPreparationFlow(ptx, secLoanTerms.code, amountToTerminate, counterParty)).first
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
