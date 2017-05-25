package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.state.Security
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.OpaqueBytes

@CordaSerializable
class SelfIssueSecuritiesFlow(val amount: Amount<Security>) : FlowLogic<SecurityClaim.State>() {
        @Suspendable
        override fun call(): SecurityClaim.State {
            val issueRef = OpaqueBytes.of(0)
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val me = serviceHub.myInfo.legalIdentity

            val secIssueTxn = subFlow(SecuritiesIssueFlow(amount, issueRef, me, notary))
            println(secIssueTxn.tx.outputs.single().data.toString())
            return secIssueTxn.tx.outputs.single().data as SecurityClaim.State

        }

    }