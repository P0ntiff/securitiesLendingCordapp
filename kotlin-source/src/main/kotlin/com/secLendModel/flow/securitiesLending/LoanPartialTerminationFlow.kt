package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.CollateralPreparationFlow
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * Created by raymondm on 31/08/2017.
 *
 * A flow that allows a party to partially terminate a loan.
 * @param amountToTerminate is a given amount of the loan that will
 * be termianted eg to terminate 50% of a loan of 1000 shares, amountToTerminate is supplied as 500.
 * @param UniqueIdentifier is the given identifier for the loan.  LoanRetrievalFlow is used to get this loan
 * from the vault.
 */


object LoanPartialTerminationFlowTerminationFlow {

    /** Flow for the party that intiates the flow. Handles the process of retrieving the loan, preparing the txn,
     * sending the txn to get signed and finally commiting this txn to the ledger.
     */
    @StartableByRPC
    @InitiatingFlow
    open class PartTerminator(val loanID: UniqueIdentifier, val amountToTerminate: Int) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            //STEP 1 Retrieve loan being terminated from the vault, and notify the counterParty about the loan we're terminating
            val secLoan = subFlow(LoanRetrievalFlow(loanID))
            val secLoanTerms = LoanChecks.stateToLoanTerms(secLoan.state.data)
            val counterParty = LoanChecks.getCounterParty(secLoanTerms, serviceHub.myInfo.legalIdentities.first())
            val flowSession = initiateFlow(counterParty)
            flowSession.send(Pair(loanID, amountToTerminate))

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan input state and the new security output state
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionBuilder(notary = notary)
            SecurityLoan().generatePartialExit(builder, secLoan, secLoan.state.data.quantity - amountToTerminate, lender, borrower, notary)

            //STEP 3: Return either cash or securities, depending on which party we are in the deal.
            val ptx: TransactionBuilder
            //If we are the lender, then we are returning cash collateral for the correct amount of shares being returned
            if (LoanChecks.isLender(secLoanTerms, serviceHub.myInfo.legalIdentities.first())) {
                //Collateral is no longer just cashm so we add in the correct collateral here
                ptx = subFlow(CollateralPreparationFlow(builder, secLoan.state.data.terms.collateralType,
                        ((secLoan.state.data.stockPrice.quantity * amountToTerminate) * (1.0 + secLoanTerms.margin)).toLong(), secLoan.state.data.borrower))
            } else {  //If we are the borrower, then we are returning stock to the lender
                ptx = try {
                    subFlow(SecuritiesPreparationFlow(builder, secLoanTerms.code, amountToTerminate, counterParty)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }

            //STEP 4: Send the txn to the other party, and receieve it back once they have added their states
            val stx = flowSession.sendAndReceive<SignedTransaction>(ptx).unwrap { it }
            subFlow(ResolveTransactionsFlow(stx, flowSession))

            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentities.first().owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty)))
            //return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId

            return Unit
        }
    }

    /** Flow for the party that retrieves the partial termination.
     *  At this point no validation is done on whether or not we want this partial update to go ahead, and instead
     *  the txn is simply accepted and signed.
     */
    @StartableByRPC
    @InitiatedBy(PartTerminator::class)
    class PartTerminationAcceptor(val counterPartyFlow: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 5: Receive information about the loan being terminated from borrower
            val loanID = counterPartyFlow.receive<Pair<UniqueIdentifier, Int>>().unwrap { it }
            val secLoan = subFlow(LoanRetrievalFlow(loanID.first))
            val secLoanTerms = LoanChecks.stateToLoanTerms(secLoan.state.data)
            val amountToTerminate = loanID.second
            //STEP 6:Receive the tx builder and and add the required collateral states as required
            val ptx = counterPartyFlow.receive<TransactionBuilder>().unwrap {
                //TODO: Check we want to accept this partial termination
                it
            }
            val tx: TransactionBuilder
            if (LoanChecks.isLender(secLoanTerms, serviceHub.myInfo.legalIdentities.first())) {
                //We are lender -> should send back cash collateral to the borrower
                tx = subFlow(CollateralPreparationFlow(ptx, secLoan.state.data.terms.collateralType,
                        ((secLoanTerms.stockPrice.quantity * amountToTerminate) * (1.0 + secLoanTerms.margin)).toLong(), counterPartyFlow.counterparty))
            } else { //We are the borrower -> should send back stock to the lender
                tx = try {
                    subFlow(SecuritiesPreparationFlow(ptx, secLoanTerms.code, amountToTerminate, counterPartyFlow.counterparty)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }

            //STEP 7: Send this tx back to the borrower
            val stx = serviceHub.signInitialTransaction(tx)
            counterPartyFlow.send(stx)

            return Unit
        }
    }
}
