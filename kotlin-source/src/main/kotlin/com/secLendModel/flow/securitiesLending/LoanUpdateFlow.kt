package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.securitiesLending.LoanChecks.cashRequired
import com.secLendModel.flow.securitiesLending.LoanChecks.getCounterParty
import com.secLendModel.flow.securitiesLending.LoanChecks.stateToLoanTerms
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
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.SignTransactionFlow
import java.text.DecimalFormat

/** A flow for updating the margin of a given SecurityLoan state -> all other parameters preserved
 *
 * Commands: UPDATE
 * @param linearID = the reference to the loan in both borrower and lender's databases
 * @param newMargin = the new percentage margin that the loan will take
 */
object LoanUpdateFlow {
    @StartableByRPC
    @InitiatingFlow
    class Updator(val linearID: UniqueIdentifier,
                    val newMargin: Double) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val myKey = serviceHub.myInfo.legalIdentity.owningKey

            //STEP1: Get Loan that is being updated -> retrieving using unique LinearID
            val secLoan = subFlow(LoanRetrievalFlow(linearID))

            //STEP 2: Create Transaction with the loanState as input and updated LoanState as output
            val borrower = secLoan.state.data.borrower
            val lender = secLoan.state.data.lender
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            SecurityLoan().generateUpdate(builder, newMargin, secLoan, lender, borrower)

            //STEP 3: Calculate change in margin, add cash if required. If not required, will be added by acceptor in STEP X
            val changeMargin = DecimalFormat(".##").format(newMargin - secLoan.state.data.terms.margin).toDouble()
            val cashToAdd = (secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity * Math.abs(changeMargin)).toLong()
            //Check if cash required, send to counterparty if it is
            if (cashRequired(serviceHub.myInfo.legalIdentity, borrower, lender, changeMargin)){
                val counterParty = getCounterParty(stateToLoanTerms(secLoan.state.data), serviceHub.myInfo.legalIdentity)
                serviceHub.vaultService.generateSpend(builder,
                        Amount(cashToAdd, CURRENCY),
                        AnonymousParty(counterParty.owningKey)
                )
            }

            //STEP 4 Send TxBuilder with loanStates (input and output) and possibly cash to acceptor party
            //Find out who our counterParty is (either lender or borrower)
            var counterParty : Party = lender
            if (serviceHub.myInfo.legalIdentity == borrower) { counterParty = lender }
            if (serviceHub.myInfo.legalIdentity == lender) { counterParty = borrower }
            send(counterParty, builder)

             //STEP 7 Receive back signed tx and finalize this update to the loan
            val signTransactionFlow = object : SignTransactionFlow(counterParty) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    //TODO: Check restraints here
                }
            }
            return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Updator::class)
    class UpdateAcceptor(val counterParty : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() : Unit {
            //STEP 5 Receive txBuilder with loanStates and possibly cash from initiator. Add Cash if required
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                val outputState = it.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                val changeMargin = DecimalFormat(".##").format(outputState.terms.margin - getOldMargin(outputState)).toDouble()
                val cashToAdd = (outputState.quantity * outputState.stockPrice.quantity * Math.abs(changeMargin)).toLong()
                //TODO: Decide whether or not to accept the proposed update to margin -> For now this is simulated if margin is too low
                //if (changeMargin <= 0.01) throw Exception("Margin Change too small for update")
                //Check if cash required, send to counterParty if needed
                if (cashRequired(serviceHub.myInfo.legalIdentity, outputState.borrower, outputState.lender, changeMargin)){
                    val counterParty = getCounterParty(stateToLoanTerms(outputState), serviceHub.myInfo.legalIdentity)
                    serviceHub.vaultService.generateSpend(it,
                            Amount(cashToAdd, CURRENCY),
                            AnonymousParty(counterParty.owningKey)
                    )
                }
                it
            }
            //STEP 6: Sign Tx and send back to initiator
            val signedTX : SignedTransaction = serviceHub.signInitialTransaction(builder)
            val fullySignedTX = subFlow(CollectSignaturesFlow(signedTX))
            subFlow(FinalityFlow(fullySignedTX)).single()
            return Unit
        }

        //Get the old margin of an inputLoan given the output loanState
        @Suspendable
        fun getOldMargin(outputState : SecurityLoan.State) : Double {
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
            val loanStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)
            val loanInputState = loanStates.states.filter {
                (it.state.data.lender == outputState.lender)  &&
                        (it.state.data.borrower == outputState.borrower) &&
                        (it.state.data.code == outputState.code) &&
                        (it.state.data.quantity == outputState.quantity) &&
                        (it.state.data.terms.lengthOfLoan == outputState.terms.lengthOfLoan) &&
                        (it.state.data.terms.rebate == outputState.terms.rebate)
            }.single()
            return (loanInputState.state.data.terms.margin)
        }
    }



}

