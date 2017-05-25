package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.serialization.OpaqueBytes
import net.corda.flows.CashIssueFlow
import java.util.*

class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        val issueRef = OpaqueBytes.of(0)
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
        val me = serviceHub.myInfo.legalIdentity

        val cashIssueTxn = subFlow(CashIssueFlow(amount, issueRef, me, notary))
        println(cashIssueTxn.tx.outputs.single().data.toString())
        return cashIssueTxn.tx.outputs.single().data as Cash.State

    }

}