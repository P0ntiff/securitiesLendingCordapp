package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.Amount
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
import java.text.DecimalFormat

/** A flow for updating the margin of a given SecurityLoan state -> all other parameters preserved
 *
 * @param linearID = the reference to the loan in both borrower and lender's databases
 * @param newMargin = the new percentage margin that the loan will take
 */
object LoanUpdateFlow {
    @StartableByRPC
    @InitiatingFlow
    class Initiator(val linearID: UniqueIdentifier,
                    val newMargin: Double) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val myKey = serviceHub.myInfo.legalIdentity.owningKey

            //STEP1: Get Loan that is being updated -> retrieving using unique LinearID
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
            val loanStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)
            val secLoans = loanStates.states.filter {
                (it.state.data.linearId == linearID) }
            if (secLoans.size > 1) {
                throw Exception("Too many states found")
            } else if (secLoans.size == 0) {
                throw Exception("No states found matching inputs")
            }
            val secLoan = secLoans.single()

            //STEP 2: Create Transaction with the loanState as input and updated LoanState as output
            val borrower = secLoan.state.data.borrower
            val lender = secLoan.state.data.lender
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            SecurityLoan().generateUpdate(builder, newMargin, secLoan, lender, borrower)

            //STEP 3: Calcualte change in margin, add cash if required. If not required, will be added by acceptor in STEP X
            val changeMargin = DecimalFormat(".##").format(newMargin - secLoan.state.data.terms.margin).toDouble()
            val cashToAdd = (secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity * Math.abs(changeMargin)).toLong()
            //Check if we are lender or borrower
            //If borrower and margin increased -> lender should recieve money
            if ((serviceHub.myInfo.legalIdentity == borrower) && (changeMargin > 0)) {
                serviceHub.vaultService.generateSpend(builder,
                        Amount(cashToAdd, CURRENCY),
                        AnonymousParty(secLoan.state.data.lender.owningKey)
                )
            }
            //If lender and margin decreased -> borrower should recieve money
            else if ((serviceHub.myInfo.legalIdentity == lender) && (changeMargin < 0)) {
                serviceHub.vaultService.generateSpend(builder,
                        Amount(cashToAdd, CURRENCY),
                        AnonymousParty(secLoan.state.data.borrower.owningKey)
                )
            }

            //STEP 4 Send TxBuilder with loanStates (input and output) and possibly cash to acceptor party
            //Find out who our counterParty is (either lender or borrower)
            var counterParty : Party = lender
            if (serviceHub.myInfo.legalIdentity == borrower) { counterParty = lender }
            if (serviceHub.myInfo.legalIdentity == lender) { counterParty = borrower }
            val ptx = sendAndReceive<SignedTransaction>(counterParty, builder).unwrap {
                //Check the PTX is what we were expecting i.e original secLoan as input, with updated loan as output
                val wtx: WireTransaction? = it.verifySignatures(myKey, notary.owningKey)
                it
            }

            //STEP 7 Receive back signed tx and finalize this update to the loan
            val ourSig = serviceHub.createSignature(ptx, myKey)
            val unnotarisedSTX = ptx + ourSig
            val finaltx = subFlow(FinalityFlow(unnotarisedSTX, setOf(lender, borrower))).single()
            return finaltx.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterParty : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() : Unit {
            //STEP 5 Receive txBuilder with loanStates and possibly cash from initiator. Add Cash if required
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                val outputState = it.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                val changeMargin = DecimalFormat(".##").format(outputState.terms.margin - getInputMargin(outputState)).toDouble()
                val cashToAdd = (outputState.quantity * outputState.stockPrice.quantity * Math.abs(changeMargin)).toLong()
                //TODO: Decide whether or not to accept the proposed update to margin -> For now this is simulated if margin is too low
                if (changeMargin <= 0.01) throw Exception("Margin Change too small for update")
                //Add cash states as needed
                //If borrower and margin increased -> send money to lender
                if ((serviceHub.myInfo.legalIdentity == outputState.borrower) && (changeMargin > 0)) {
                    serviceHub.vaultService.generateSpend(it,
                            Amount(cashToAdd, CURRENCY),
                            AnonymousParty(outputState.lender.owningKey)
                    )
                }
                //If lender and margin decreased -> borrower should recieve money
                else if ((serviceHub.myInfo.legalIdentity == outputState.lender) && (changeMargin < 0)) {
                    serviceHub.vaultService.generateSpend(it,
                            Amount(cashToAdd, CURRENCY),
                            AnonymousParty(outputState.borrower.owningKey)
                    )
                }
                it
            }
            //STEP 6: Sign Tx and send back to intitiator
            val signedTx : SignedTransaction = serviceHub.signInitialTransaction(builder)
            send(counterParty, signedTx)
        }

        @Suspendable
        //Get a loan state with the same data as the one on the inputRef and check whether it has the same StateAndRef
        fun checkLoanInput(outputState : SecurityLoan.State, inputRef : StateRef) : Boolean {
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
            return (loanInputState.ref.txhash == inputRef.txhash)
        }
        //Get the margin of an inputLoan given the output loanState
        fun getInputMargin(outputState : SecurityLoan.State) : Double {
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

