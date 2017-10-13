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
 *
 * @param otherParty the party for which we want to net loans with (they can be either the lender or borrower in any of the loans)
 * @param code the code of the security that the loans we are netting use.
 * @param collateralType the type of collateral used in the loans we are looking for (either Cash, GBT, CBA, NAB or RIO at this point)
 * @returns an array of linearIDs relating to the loans retrieved from the vault.
 */

@StartableByRPC
class LoanNetPrepFlow(val otherParty: Party, val code: String, val collateralType: String) : FlowLogic<List<UniqueIdentifier>>() {
    @Suspendable
    override fun call() : List<UniqueIdentifier> {
        //Search for unconsumed states
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)

        //Search for securityLoan states
        val loanStates = serviceHub.vaultQueryService.queryBy<SecurityLoan.State>(criteria)

        //Get states that are between these two parties and are a loan of the security code specified, and use the correct collateral type
        val secLoans = loanStates.states.filter {
            (((it.state.data.lender == otherParty) || (it.state.data.borrower == otherParty)) && (it.state.data.code == code)) && (it.state.data.terms.collateralType == collateralType) }
        val secLoanIDs: ArrayList<UniqueIdentifier> = arrayListOf()

        //Get the linear ID of each loan and add it to an array
        secLoans.forEach {
            secLoanIDs.add(it.state.data.linearId)
        }
        //Throw an issue here (i.e dont continue with the netting) if we have one or no states satisfying the conditions
        if (secLoans.size <= 1) {
            throw FlowException("One or less states found with this party")
        } else if (secLoans.isEmpty()) {
            throw FlowException("No states found matching inputs")
        }
        return secLoanIDs
    }



}

