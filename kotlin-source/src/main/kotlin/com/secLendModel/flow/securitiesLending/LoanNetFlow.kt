package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.PriceRequestFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
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
    class NetInitiator(val linearIDList: List<UniqueIdentifier>
    ) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            //STEP1: Get Loans that are being merged and add them to the tx
            //TX Builder for states to be added to
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            val securityLoans: ArrayList<StateAndRef<SecurityLoan.State>> = ArrayList()
            linearIDList.forEach {
                val secLoan = subFlow(LoanRetrievalFlow(it))
                securityLoans.add(secLoan)
                builder.addInputState(secLoan)
                println("Secloan added for net ${secLoan.state.data.code} ${secLoan.state.data.quantity}")
            }
            println(securityLoans)
            //Check who is lender of borrower here.
            val lender = securityLoans.first().state.data.lender
            val borrower = securityLoans.first().state.data.borrower
            SecurityLoan().generateLoanNet(builder,lender, borrower, securityLoans,
                    serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)

            //STEP 4 Send TxBuilder with loanStates (input and output)  to acceptor party
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
            //STEP 5 Receive txBuilder with loanStates and possibly cash from initiator. Add Cash if required
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                //TODO: Decide whether or not to accept the proposed net of loans-> For now this is simulated if margin is too low
                //if (changeMargin <= 0.02 throw Exception("Margin Change too small for update")
                //Check if cash required, send to counterParty if needed

                it
            }

            //STEP 6: Sign Tx and send back to initiator
            val signedTX: SignedTransaction = serviceHub.signInitialTransaction(builder)
            send(counterParty, signedTX)
            //val fullySignedTX = subFlow(CollectSignaturesFlow(signedTX))
            //subFlow(FinalityFlow(fullySignedTX)).single()
            return Unit
        }
    }


}
