package com.secLendModel.flow.securities

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import net.corda.core.contracts.*
import net.corda.core.flows.FinalityFlow
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker

/** Issues securities from the caller of this flow to the recipient.
 *  @param code = stock code (String) to be traded on the ledger
 *  @param quantity = number of the above stock to be issued
 *  @param recipient = party on the ledger who is receiving the issuance states (i.e the owner of these new states)
 *  @param notary = validating notary on the ledger
 */
@StartableByRPC
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
        val builder: TransactionBuilder = TransactionBuilder(notary = null)
        val issuer = serviceHub.myInfo.legalIdentities.first().ref(issueRef)
        SecurityClaim().generateIssue(builder, issuer, code, quantity, recipient, notary)

        proTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(builder)

        proTracker.currentStep = FINALISING_TX
        subFlow(FinalityFlow(tx, setOf(recipient)))
        return tx
    }


}