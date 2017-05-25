package com.secLendModel

import com.secLendModel.flow.SelfIssueCashFlow
import com.secLendModel.flow.SelfIssueSecuritiesFlow
import com.secLendModel.state.Security
import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.utilities.ALICE
import net.corda.core.utilities.BOB
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.flows.CashExitFlow
import net.corda.flows.CashIssueFlow
import net.corda.flows.CashPaymentFlow
import net.corda.node.driver.driver
import net.corda.node.services.startFlowPermission
import net.corda.nodeapi.User
import net.corda.node.services.transactions.ValidatingNotaryService
import org.bouncycastle.asn1.x500.X500Name


@JvmField val GBT = Security.getInstance("GBT")

fun main(args: Array<String>) {
    // No permissions required as we are not invoking flows.
    val permissions = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<CashPaymentFlow>(),
            startFlowPermission<CashExitFlow>(),
            startFlowPermission<SelfIssueCashFlow>(),
            startFlowPermission<SelfIssueSecuritiesFlow>()
    )
    val user = User("user1", "test", permissions = permissions)
    driver(isDebug = true) {
        val notary = startNode(DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)),
                customOverrides = mapOf("nearestCity" to "Zurich"))
        val alice = startNode(ALICE.name, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "Milan"))
        val bob = startNode(BOB.name, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "Madrid"))
        val exchange = startNode(X500Name("CN=LSE Ltd,O=LSE Ltd,L=London,C=UK"), rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "London"))

//        startWebserver(alice)
//        startWebserver(bob)
//        startWebserver(exchange)

        val notaryNode = notary.get()
        val aliceNode = alice.get()
        val bobNode = bob.get()
        val exchangeNode = exchange.get()

        arrayOf(notaryNode, aliceNode, bobNode, exchangeNode).forEach {
            println("${it.nodeInfo.legalIdentity} started on ${it.configuration.rpcAddress}")
        }

        val aClient = aliceNode.rpcClientToNode()
        aClient.start(user.username, user.password)
        val aRPC = aClient.proxy()

        val bClient = bobNode.rpcClientToNode()
        bClient.start(user.username, user.password)
        val bRPC = bClient.proxy()

        val eClient = exchangeNode.rpcClientToNode()
        eClient.start(user.username, user.password)
        val eRPC = eClient.proxy()

        println("txn initiated")
        val txn = aRPC.startFlow(::SelfIssueCashFlow, Amount(1000.toLong(), GBP))
        aRPC.startFlow(::SelfIssueSecuritiesFlow, Amount(100.toLong(), Security("GBT", "GBST Holdings Ltd")))
        println("Txn passed")


        waitForAllNodesToFinish()
    }
}
