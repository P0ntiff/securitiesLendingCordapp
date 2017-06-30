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
import net.corda.core.node.services.Vault
import net.corda.flows.NotaryException
import java.util.*

/** A flow for transferring ownership of a securityClaim to another owner (recipient)
 *
 * @param stock = An Amount<Security> which is a data class containing the quantity of shares (amount.quantitiy) to be
 * moved, and the code/title of the share to be moved (amount.token.code)
 * @param newOwner = the party that is becoming the new owner of the states being sent
 */
@StartableByRPC
open class OwnershipTransferFlow(val stock : Amount<Security>, val newOwner: Party): FlowLogic<SignedTransaction>() {
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
        val desiredStates = getStates(stock.token.code)
        //Input states from vault into transaction and create outputs with newOwner as new owner of states
        val (spendTX, keysForSigning) = try {
            OnLedgerAsset.generateSpend(
                    tx,
                    stock,
                    newOwner,
                    desiredStates,
                    { state, amount, owner -> deriveState(state, amount, owner) },
                    { SecurityClaim().generateMoveCommand() }
            )
        } catch (e: InsufficientBalanceException) {
            throw SecurityException("Insufficient holding: ${e.message}", e)
        }
        progressTracker.currentStep = SIGNING
        val stx = serviceHub.signInitialTransaction(spendTX, keysForSigning)
        progressTracker.currentStep = COLLECTING
        try {
            subFlow(FinalityFlow(stx, setOf(newOwner)))
        } catch (e: ClassCastException) {
            println("ERROR DETECTED!")
            throw SecurityException("Unable to notarise spend", e)
        }
        return stx
    }

    @Suspendable
    private fun getStates(code : String) : List<StateAndRef<SecurityClaim.State>> {
        /**Old Method
         * val (vault, vaultUpdates) = serviceHub.vaultService.track()
         * val states = vault.states.filterStatesOfType<SecurityClaim.State>().toList()
         *
         *Less Old Method
         * val states = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java), EnumSet.of(Vault.StateStatus.UNCONSUMED)).toMutableList()
         * val desiredStates : ArrayList<StateAndRef<SecurityClaim.State>> = arrayListOf()
         * for (state in states) {
         *    if (state.state.data.amount.token.product.code == amount.token.code) {
         *        desiredStates.add(state)
         *    }
         *}
         *Old New Method
         * val stockStates = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java),
         * EnumSet.of(Vault.StateStatus.UNCONSUMED))
         * val desiredStates = stockStates.filter { (it.state.data.amount.token.product.code == code) }
         */
        //New Method
        val states : Vault.Page<SecurityClaim.State> = serviceHub.vaultQueryService.queryBy(SecurityClaim.State::class.java)
        val desiredStates = states.states.filter {
            (it.state.data.owner == serviceHub.myInfo.legalIdentity) &&
                    (it.state.data.amount.token.product.code == code) &&
                    (states.statesMetadata[states.states.indexOf(it)].status == Vault.StateStatus.UNCONSUMED)
        }

        return desiredStates
    }

    @Suspendable
    private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
        = txState.copy(data = txState.data.copy(amount = amount, owner = owner))
}
