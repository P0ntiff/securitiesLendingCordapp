package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.Security
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.FinalityFlow

infix fun Security.issuedBy(deposit: PartyAndReference) = Issued(deposit, this)
infix fun Amount<Security>.issuedBy(deposit: PartyAndReference) = Amount(quantity, displayTokenSize, token.issuedBy(deposit))

@StartableByRPC
@InitiatingFlow
class SecuritiesIssueFlow(val amount: Amount<Security>,
                          val issueRef: OpaqueBytes,
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
        val builder: TransactionBuilder = TransactionType.General.Builder(notary = null)
        val issuer = serviceHub.myInfo.legalIdentity.ref(issueRef)
        SecurityClaim().generateIssue(builder, amount.issuedBy(issuer), recipient, notary)
        proTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(builder)
        proTracker.currentStep = FINALISING_TX
        subFlow(FinalityFlow(tx))
        return tx
    }


}