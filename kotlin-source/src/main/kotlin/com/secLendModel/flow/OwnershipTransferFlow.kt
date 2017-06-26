package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.Security
import com.secLendModel.contract.SecurityException
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.contracts.asset.OnLedgerAsset
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.flows.NotaryException
import java.util.ArrayList

// A flow for transferring ownership of a securityClaim to another owner (recipient)
@StartableByRPC
open class OwnershipTransferFlow(val amount : Amount<Security>, val states: ArrayList<StateAndRef<SecurityClaim.State>>, val newOwner: Party): FlowLogic<SignedTransaction>() {
    override val progressTracker: ProgressTracker = OwnershipTransferFlow.tracker()
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
        val tx : TransactionBuilder = TransactionType.General.Builder(null as Party?)
        //Gather states from vault
//        val (vault, vaultUpdates) = serviceHub.vaultService.track()
//        val states = vault.states.filterStatesOfType<SecurityClaim.State>().toList()
        val (spendTX, keysForSigning) = try {
            OnLedgerAsset.generateSpend(
                    tx,
                    amount,
                    newOwner,
                    states,
                    { state, amount, owner -> deriveState(state, amount, owner) },
                    { SecurityClaim().generateMoveCommand() }
            )
        } catch (e: InsufficientBalanceException) {
            throw SecurityException("Insufficient holding: ${e.message}", e)
        }
        val stx = serviceHub.signInitialTransaction(spendTX, keysForSigning)

        progressTracker.currentStep = COLLECTING
        try {
            subFlow(FinalityFlow(stx, setOf(newOwner)))
        } catch (e: NotaryException) {
            throw SecurityException("Unable to notarise spend", e)
        }

        return stx
    }

    @Suspendable
    private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
        = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

}
