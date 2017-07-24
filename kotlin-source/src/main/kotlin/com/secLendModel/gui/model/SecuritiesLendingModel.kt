package com.secLendModel.gui.model

import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import net.corda.client.jfx.model.Diff
import net.corda.client.jfx.model.NodeMonitorModel
import net.corda.client.jfx.model.observable
import net.corda.client.jfx.utils.fold
import net.corda.client.jfx.utils.map
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.node.services.Vault
import rx.Observable
import tornadofx.*


/**This model is based on ContractStateModel.kt, under corda source code /client/jfx/.../model/
 *
 * Allows live reads to be performed on a node's internal vault w.r.t securityClaims, securityLoans, and cashStates
 */

class SecuritiesLendingModel {
    private val vaultUpdates: Observable<Vault.Update> by observable(NodeMonitorModel::vaultUpdates)

    private val contractStatesDiff: Observable<Diff<ContractState>> = vaultUpdates.map {
        Diff(it.produced, it.consumed)
    }

    //Cash States active in the vault
    private val cashStatesDiff: Observable<Diff<Cash.State>> = contractStatesDiff.map {
        Diff(it.added.filterCashStateAndRefs(), it.removed.filterCashStateAndRefs())
    }
    val cashStates: ObservableList<StateAndRef<Cash.State>> = cashStatesDiff.fold(FXCollections.observableArrayList()) { list: MutableList<StateAndRef<Cash.State>>, statesDiff ->
        list.removeIf { it in statesDiff.removed }
        list.addAll(statesDiff.added)
    }
    val cash = cashStates.map { it.state.data.amount }

    //Security claim states active in the vault
    private val claimStatesDiff: Observable<Diff<SecurityClaim.State>> = contractStatesDiff.map {
        Diff(it.added.filterClaimStateAndRefs(), it.removed.filterClaimStateAndRefs())
    }
    val claimStates: ObservableList<StateAndRef<SecurityClaim.State>> = claimStatesDiff.fold(FXCollections.observableArrayList()) { list: MutableList<StateAndRef<SecurityClaim.State>>, statesDiff ->
        list.removeIf { it in statesDiff.removed }
        list.addAll(statesDiff.added)
    }

    //Security loan states active in the vault
    private val loanStatesDiff: Observable<Diff<SecurityLoan.State>> = contractStatesDiff.map {
        Diff(it.added.filterLoanStateAndRefs(), it.removed.filterLoanStateAndRefs())
    }
    val loanStates: ObservableList<StateAndRef<SecurityLoan.State>> = loanStatesDiff.fold(FXCollections.observableArrayList()) { list: MutableList<StateAndRef<SecurityLoan.State>>, statesDiff ->
        list.removeIf { it in statesDiff.removed }
        list.addAll(statesDiff.added)
    }

    private fun Collection<StateAndRef<ContractState>>.filterCashStateAndRefs(): List<StateAndRef<Cash.State>> {
        return this.map { stateAndRef ->
            @Suppress("UNCHECKED_CAST")
            if (stateAndRef.state.data is Cash.State) {
                // Kotlin doesn't unify here for some reason
                stateAndRef as StateAndRef<Cash.State>
            } else {
                null
            }
        }.filterNotNull()
    }
    private fun Collection<StateAndRef<ContractState>>.filterClaimStateAndRefs(): List<StateAndRef<SecurityClaim.State>> {
        return this.map { stateAndRef ->
            @Suppress("UNCHECKED_CAST")
            if (stateAndRef.state.data is SecurityClaim.State) {
                // Kotlin doesn't unify here for some reason
                stateAndRef as StateAndRef<SecurityClaim.State>
            } else {
                null
            }
        }.filterNotNull()
    }
    private fun Collection<StateAndRef<ContractState>>.filterLoanStateAndRefs(): List<StateAndRef<SecurityLoan.State>> {
        return this.map { stateAndRef ->
            @Suppress("UNCHECKED_CAST")
            if (stateAndRef.state.data is SecurityLoan.State) {
                // Kotlin doesn't unify here for some reason
                stateAndRef as StateAndRef<SecurityLoan.State>
            } else {
                null
            }
        }.filterNotNull()
    }
}

data class Diff<out T : ContractState>(
        val added: Collection<StateAndRef<T>>,
        val removed: Collection<StateAndRef<T>>
)