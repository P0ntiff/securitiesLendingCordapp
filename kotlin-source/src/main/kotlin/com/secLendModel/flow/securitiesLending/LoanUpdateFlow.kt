package com.secLendModel.flow.securitiesLending


import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import java.security.Security
import java.util.*

/**
 * Created by raymondm on 13/07/2017.
 */

/** A flow for updating the margin of a given SecurityLoan state -> all other parameters preserved
 *
 * @param code = A security (String) that is the code/title of the share to be moved
 * @param quantity = the quantity of the above share to be moved from one owner to newOwner
 * @param recipient = the party that is becoming the new owner of the states being sent
 */
object LoanUpdateFlow {
    @StartableByRPC
    @InitiatingFlow
    class Lender(val linearID: UniqueIdentifier,
                 val newMargin: Int
                 ) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            //Retrieve the loan from our vault
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
            val equityStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)
            val desiredStates = equityStates.states.filter {
                (it.state.data.linearId == linearID) }
            if (desiredStates.size > 1){
                throw Exception("Too many states found")
            }
            if (desiredStates.size == 0){
                throw Exception("No states found matching inputs")
            }

            val borrower = desiredStates.single().state.data.borrower
            val lender = serviceHub.myInfo.legalIdentity
            //We now have a single loan state that matches the inputs
            val originalStateBuilder = TransactionBuilder()
            val builder = SecurityLoan().generateUpdate(originalStateBuilder,newMargin,desiredStates.single(),lender,borrower)
            //Send a recieve to the borrower to confirm they are happy with the update
            //TODO: Check that securityLoan matches
            val ptx = sendAndReceive<SignedTransaction>(borrower, builder).unwrap { it }
            val stx = serviceHub.addSignature(ptx, serviceHub.myInfo.legalIdentity.owningKey)
            val finaltx = subFlow(FinalityFlow(stx))
            return finaltx.single().tx.outputs.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Lender::class)
    class Borrower(val lender : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() : Unit {
            val builder = receive<TransactionBuilder>(lender).unwrap { it }
            //TODO: Check we are happy with this update, for now lets just sign it
            val signedTx = serviceHub.signInitialTransaction(builder)
            send(lender, signedTx)
        }

    }

}

