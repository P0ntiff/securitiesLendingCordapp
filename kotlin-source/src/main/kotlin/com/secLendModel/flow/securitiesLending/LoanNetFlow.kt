package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import com.secLendModel.flow.oracle.PriceRequestFlow
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
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
import java.text.DecimalFormat

/**
 * Created by raymondm on 21/08/2017.
 *
 * A simple flow for combining multiple security loan states between two parties into a single loan, effectively
 * netting the position between the parties.
 *
 * TODO: Current issues are that the terms are simply based of the first input loan, so loan terms may change. May
 *       fix this by enforcing loan terms being the same for the update to be possible.
 *
 */


object LoanNetFlow {
    @StartableByRPC
    @InitiatingFlow
    class NetInitiator(val otherParty: Party
    ) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            //STEP1: Get Loans that are being merged and add them to the tx
            //TX Builder for states to be added to
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            val securityLoans: ArrayList<StateAndRef<SecurityLoan.State>> = ArrayList()
            //Also get the linear ID list for the other party
            val linearIDList = subFlow(LoanNetPrepFlow(otherParty))
            //STEP2: Update then terminate loans that are being used to net the position
            linearIDList.forEach {
                val updatedLoanID = subFlow(LoanUpdateFlow.Updator(it))
                val updatedLoan = subFlow(LoanRetrievalFlow(updatedLoanID))
                val terminateLoad = subFlow(LoanTerminationFlow.Terminator(updatedLoan.state.data.linearId))
                securityLoans.add(updatedLoan)
                println("Secloan added for net ${updatedLoan.state.data.code} ${updatedLoan.state.data.quantity} price ${updatedLoan.state.data.currentStockPrice}")
            }

            //STEP3: Calculate the output state, add cash and securities as needed (this depends on which party called the net)
            //Check who is lender of borrower here.
            val lender = securityLoans.first().state.data.lender
            val borrower = securityLoans.first().state.data.borrower
            //Calculate the new loan that is required to be generated
            val outputShares = securityLoans.map {
                if (it.state.data.borrower == borrower) {
                    -(it.state.data.quantity)
                } else {
                    it.state.data.quantity
                }
            }
            var outputSharesSum = 0
            outputShares.forEach { outputSharesSum += it }
            if (outputSharesSum < 0) {
                //If we are the lender, we need to add shares as an input state
                if (serviceHub.myInfo.legalIdentity == lender) {
                    subFlow(SecuritiesPreparationFlow(builder,securityLoans.first().state.data.code,Math.abs(outputSharesSum),borrower))
                }
            } else {
                //if we are borrower, we need to add shares as an input state
                if (serviceHub.myInfo.legalIdentity == borrower) {
                    subFlow(SecuritiesPreparationFlow(builder,securityLoans.first().state.data.code,Math.abs(outputSharesSum),lender))
                }
            }
            SecurityLoan().generateLoanNet(builder,lender, borrower, securityLoans, outputSharesSum,
                    serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)

            val outputState = builder.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
            //Add cash as needed
            val outputLender = outputState.lender
            val outputBorrower = outputState.borrower
            //if we are borrower, add cash
            if (serviceHub.myInfo.legalIdentity == outputBorrower) {
                    //Add cash
                    serviceHub.vaultService.generateSpend(builder,
                            Amount(((outputState.stockPrice.quantity * outputState.quantity) * (1.0 + outputState.terms.margin)).toLong(), CURRENCY),
                            AnonymousParty(outputState.lender.owningKey)).first
            }


            //STEP 4 Send TxBuilder with output loan state, and possible input securities or cash states
            //Find out who our counterParty is (either lender or borrower)
            val counterParty = LoanChecks.getCounterParty(LoanChecks.stateToLoanTerms(securityLoans.first().state.data), serviceHub.myInfo.legalIdentity)
            send(counterParty, builder)

            //STEP 7 Receive back signed tx and finalize this update to the loan
            val stx = sendAndReceive<SignedTransaction>(counterParty, builder).unwrap {
                val wtx: WireTransaction = it.verifySignatures(serviceHub.myInfo.legalIdentity.owningKey,
                        serviceHub.networkMapCache.notaryNodes.single().notaryIdentity.owningKey)
                //Check txn dependency chain ("resolution")
                subFlow(ResolveTransactionsFlow(wtx, counterParty))

                it
            }
            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentity.owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty))).single()
            return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
            // return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(NetInitiator::class)
    class NetAcceptor(val counterParty : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 5 Receive txBuilder with loanStates and possibly cash from initiator. Add Cash/securities if required
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                //Get the output loan state
                val outputState = it.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                val outputSharesSum = outputState.quantity
                val code = outputState.code
                val outputLender = outputState.lender
                val outputBorrower = outputState.borrower
                //Add input stock as needed if we are the lender
                if (serviceHub.myInfo.legalIdentity == outputLender) {
                    //If we are the lender, we need to add shares as an input state
                    subFlow(SecuritiesPreparationFlow(it,code,Math.abs(outputSharesSum),outputBorrower))
                } else {
                    //if we are borrower, add cash
                    if (serviceHub.myInfo.legalIdentity == outputBorrower) {
                        //Add cash
                        serviceHub.vaultService.generateSpend(it,
                                Amount(((outputState.stockPrice.quantity * outputState.quantity) * (1.0 + outputState.terms.margin)).toLong(), CURRENCY),
                                AnonymousParty(outputState.lender.owningKey)).first
                    }
                }
                it
            }



            //STEP 6: Sign Tx and send back to initiator
            val signedTX: SignedTransaction = serviceHub.signInitialTransaction(builder)
            send(counterParty, signedTX)
            return Unit
        }
    }


}