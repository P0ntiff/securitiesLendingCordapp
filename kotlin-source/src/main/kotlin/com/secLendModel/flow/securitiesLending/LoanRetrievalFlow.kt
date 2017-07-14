package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria

/**
 * Takes a linearID for a security loan and returns the reference to the state in the calling node's vault..
 */

@StartableByRPC

class LoanRetrievalFlow(val linearID : UniqueIdentifier) : FlowLogic<StateAndRef<SecurityLoan.State>>() {
    @Suspendable
    override fun call() : StateAndRef<SecurityLoan.State> {
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val loanStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)
        val secLoans = loanStates.states.filter {
            (it.state.data.linearId == linearID) }
        if (secLoans.size > 1) {
            throw FlowException("Too many states found")
        } else if (secLoans.isEmpty()) {
            throw FlowException("No states found matching inputs")
        }
        return secLoans.single()
    }



}