package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.ServiceType.Companion.notary
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow

/** A flow for updating the margin of a given SecurityLoan state -> all other parameters preserved
 *
 * @param linearID = the reference to the loan in both borrower and lender's databases
 * @param newMargin = the new percentage margin that the loan will take
 */
object LoanUpdateFlow {
    @StartableByRPC
    @InitiatingFlow
    class Initiator(val linearID: UniqueIdentifier,
                    val newMargin: Int) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            //Retrieve the loan from our vault
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
            //We now have a single loan state that matches the inputs
            val borrower = secLoan.state.data.borrower
            val lender = secLoan.state.data.lender
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            SecurityLoan().generateUpdate(builder, newMargin, secLoan, lender, borrower)
            //Send to the other party to confirm they are happy with the update
            //TODO: Check that securityLoan matches
            val ptx = sendAndReceive<SignedTransaction>(borrower, builder).unwrap {
                val wtx: WireTransaction = it.verifySignatures(myKey, notary.owningKey)

                it
            }
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
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                //TODO: Check we are happy with this margin update, for now lets just sign it
                it
            }
            val signedTx : SignedTransaction = serviceHub.signInitialTransaction(builder)
            send(counterParty, signedTx)
        }
    }
}

