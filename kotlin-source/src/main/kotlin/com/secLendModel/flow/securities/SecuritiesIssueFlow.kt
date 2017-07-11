package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.FinalityFlow

@StartableByRPC
/** Issues securities from the caller of this flow to the recipient.
 */
class SecuritiesIssueFlow(val code: String,
                          val quantity: Int,
                          val recipient: Party,
                          val notary: Party) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TX : ProgressTracker.Step("Generating transaction")
        object SIGNING_TX : ProgressTracker.Step("Signing transaction")
        object FINALISING_TX : ProgressTracker.Step("Finalising transaction")

        fun tracker() = ProgressTracker(GENERATING_TX, SIGNING_TX, FINALISING_TX)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        val proTracker = tracker()

        proTracker.currentStep = GENERATING_TX
        val issueRef = OpaqueBytes.of(1)
        val builder: TransactionBuilder = TransactionType.General.Builder(notary = null)
        val issuer = serviceHub.myInfo.legalIdentity.ref(issueRef)
        SecurityClaim().generateIssue(builder, issuer, code, quantity, recipient, notary)

        proTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(builder)

        proTracker.currentStep = FINALISING_TX
        subFlow(FinalityFlow(tx))
        return tx
    }


}