package com.secLendModel.flow.securitiesLending

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party

/**
 * Created by beng on 13/07/2017.
 */
object LoanIssuanceFlow {
    @StartableByRPC
    @InitiatingFlow
    class Borrower() : FlowLogic<Unit>() {
        override fun call() : Unit {

        }
    }

    @InitiatedBy(Borrower::class)
    class Lender(val lender : Party) : FlowLogic<Unit>() {
        override fun call() : Unit {

        }

    }

}