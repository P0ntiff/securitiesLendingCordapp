package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import net.corda.core.contracts.PartyAndReference
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.TransactionType
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import java.security.PublicKey
import java.util.ArrayList

/**
 * This should be called as a subflow in move/trade flows to prepare equity states to go into a transaction.
 * I.e "coin selection" for the input states, gathering from the vault of the current node
 **/

class InsufficientHoldingException(val amountMissing: Int, val code: String) : FlowException() {
    override fun toString() = "Insufficient holding, missing $amountMissing $code shares"
}

@StartableByRPC
@InitiatingFlow
class SecuritiesPreparationFlow(val code : String,
                                val quantity : Int,
                                val recipient : Party) : FlowLogic<Pair<TransactionBuilder, List<PublicKey>>>() {
    override fun call(): Pair<TransactionBuilder, List<PublicKey>> {
        val notary = serviceHub.networkMapCache.notaryNodes.first().notaryIdentity
        val recipientKey = recipient
        val builder : TransactionBuilder = TransactionType.General.Builder(notary)

        return prepareTransaction(builder, code, quantity, recipientKey)
    }

    @Suspendable
    private fun prepareTransaction(tx: TransactionBuilder,
                                   code: String,
                                   quantity: Int,
                                   to: AbstractParty) : Pair<TransactionBuilder, List<PublicKey>> {
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val equityStates = serviceHub.vaultQueryService.queryBy<SecurityClaim.State>(criteria)
        val desiredStates = equityStates.states.filter {
            (it.state.data.owner == serviceHub.myInfo.legalIdentity)  &&
                    (it.state.data.code == code) // &&
        }

        val (gathered, gatheredQuantity) = gatherEquity(desiredStates, code, quantity)

        val takeChangeFrom = gathered.firstOrNull()
        val change = if (takeChangeFrom != null && gatheredQuantity > quantity) {
            gatheredQuantity - quantity
        } else {
            null
        }
        val keysUsed = gathered.map { it.state.data.owner.owningKey }

        val states = gathered.groupBy { it.state.data.issuance.party }.map {
            val equities = it.value
            val totalQuantity = equities.map { it.state.data.quantity }.sum()
            deriveState(equities.first().state, equities.first().state.data.issuance,
                    to, code, totalQuantity)
        }.sortedBy { it.data.quantity }

        val outputs = if (change != null) {
            val existingOwner = gathered.first().state.data.owner
            states.subList(0, states.lastIndex) +
                    states.last().let {
                        val spent = it.data.quantity - change
                        deriveState(it, it.data.issuance, it.data.owner, it.data.code, spent)
                    } +
                    states.last().let {
                        deriveState(it, it.data.issuance, existingOwner, it.data.code, change)
                    }
        } else states
        for (state in gathered) {
            tx.addInputState(state)
        }
        for (state in outputs) {
            tx.addOutputState(state)
        }
        val keysList = keysUsed.toList()
        tx.addCommand(SecurityClaim().generateMoveCommand(), keysList)
        return Pair(tx, keysList)

    }

    @Suspendable
    fun deriveState(txState: TransactionState<SecurityClaim.State>,
                    issuance: PartyAndReference,
                    owner: AbstractParty,
                    code: String,
                    quantity: Int) = txState.copy(data = txState.data.copy(issuance = issuance,
            owner = owner,
            code = code,
            quantity = quantity))

    @Throws(InsufficientHoldingException::class)
    @Suspendable
    fun gatherEquity(acceptableEquities: List<StateAndRef<SecurityClaim.State>>,
                     code: String,
                     quantity: Int) : Pair<ArrayList<StateAndRef<SecurityClaim.State>>, Int> {
        require(quantity > 0) { "Cannot gather zero quantity of equities."}
        val gathered = arrayListOf<StateAndRef<SecurityClaim.State>>()
        var gatheredQuantity : Int = 0
        for (c in acceptableEquities) {
            if (c.state.data.code != code) continue
            if (gatheredQuantity >= quantity) break
            gathered.add(c)
            gatheredQuantity += c.state.data.quantity
        }

        if (gatheredQuantity < quantity)
            throw InsufficientHoldingException(quantity - gatheredQuantity, code)

        return Pair(gathered, gatheredQuantity)
    }
}