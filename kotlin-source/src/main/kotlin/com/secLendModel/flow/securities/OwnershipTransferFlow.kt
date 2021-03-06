package com.secLendModel.flow.securities

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow


/** A flow for transferring ownership of a securityClaim to another owner (recipient)
 *
 * @param code = A security (String) that is the code/title of the share to be moved
 * @param quantity = the quantity of the above share to be moved from one owner to newOwner
 * @param recipient = the party that is becoming the new owner of the states being sent
 */
@StartableByRPC
class OwnershipTransferFlow(val code : String,
                            val quantity : Int,
                            val recipient: Party) : FlowLogic<SignedTransaction>() {

    override val progressTracker: ProgressTracker = tracker()
    companion object {
        object PREPARING : ProgressTracker.Step("Obtaining claim from vault and building transaction.")
        object SIGNING : ProgressTracker.Step("Signing transaction.")
        object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }
        object FINALISING : ProgressTracker.Step("Finalising transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }
        fun tracker() = ProgressTracker(PREPARING, SIGNING, COLLECTING, FINALISING)
    }

    @Suspendable
    override fun call() : SignedTransaction {
        progressTracker.currentStep = PREPARING
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
        val builder : TransactionBuilder = TransactionType.General.Builder(notary)

        //Input states from vault into the transaction, and create outputs with a) recipient as the new owner of the states,
        // b) with change sent back to the old owner
        val (spendTX, keysForSigning) = subFlow(SecuritiesPreparationFlow(builder, code, quantity, recipient))
//        } catch (e: InsufficientBalanceException) {
//            throw SecurityException("Insufficient holding: ${e.message}", e)
//        }

        progressTracker.currentStep = SIGNING
        val stx = serviceHub.signInitialTransaction(spendTX, keysForSigning)

        progressTracker.currentStep = COLLECTING
        try {
            subFlow(FinalityFlow(stx, setOf(recipient)))
        } catch (e: ClassCastException) {
            throw SecurityException("Unable to notarise spend", e)
        }
        return stx
    }

}
