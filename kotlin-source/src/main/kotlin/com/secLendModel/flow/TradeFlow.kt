package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.identity.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow

object TradeFlow {
    /**
     * Can add a constructor to each FlowLogic subclass to pass objects into the flow.
     */
    @InitiatingFlow
    class Initiator: FlowLogic<Unit>() {
        @Suspendable
        override fun call() {

        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterparty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {

        }
    }
}