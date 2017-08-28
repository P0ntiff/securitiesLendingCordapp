package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria

/**
 * Created by raymondm on 28/08/2017.
 *
 * Class for preparing a loan net position. This is used within the GUI where a user supplies a party that they want
 * to net their loans with. This queries the vault, and provides them a list of linearIDs that reference
 * the different states between them and the party they are netting with. LoanNetFlow can then be called with this
 * loanID list.
 */

@StartableByRPC
class LoanNetPrepFlow(val otherParty: Party) : FlowLogic<List<UniqueIdentifier>>() {
    @Suspendable
    override fun call() : List<UniqueIdentifier> {
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val loanStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)
        val secLoans = loanStates.states.filter {
            (it.state.data.lender == otherParty) || (it.state.data.borrower == otherParty) }
        val secLoanIDs: ArrayList<UniqueIdentifier> = arrayListOf()
        secLoans.forEach {
            secLoanIDs.add(it.state.data.linearId)
        }
        if (secLoans.size <= 1) {
            throw FlowException("One or less states found with this party")
        } else if (secLoans.isEmpty()) {
            throw FlowException("No states found matching inputs")
        }
        return secLoanIDs
    }



}

