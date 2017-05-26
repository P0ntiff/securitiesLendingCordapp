package com.secLendModel

import com.secLendModel.flow.OwnershipTransferFlow
import com.secLendModel.flow.SecuritiesIssueFlow
import com.secLendModel.flow.SelfIssueCashFlow
import com.secLendModel.flow.SelfIssueSecuritiesFlow
import com.secLendModel.state.Security
import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.crypto.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.OpaqueBytes
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
import java.util.*

@JvmField val GBT = Security.getInstance("GBT")

fun main(args: Array<String>) {
    // No permissions required as we are not invoking flows.
    val permissions = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<CashPaymentFlow>(),
            startFlowPermission<CashExitFlow>(),
            startFlowPermission<SelfIssueCashFlow>(),
            startFlowPermission<SecuritiesIssueFlow>(),
            startFlowPermission<SelfIssueSecuritiesFlow>()
    )
    val user = User("user1", "test", permissions = permissions)
    driver(isDebug = false) {
        val notary = startNode(DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)),
                customOverrides = mapOf("nearestCity" to "Zurich"))
        val alice = startNode(ALICE.name, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "Milan"))
        val bob = startNode(BOB.name, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "Madrid"))
        val exchange = startNode(X500Name("CN=LSE Ltd,O=LSE Ltd,L=Liverpool,C=UK"), rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.RIO")),
                        ServiceInfo(ServiceType.corda.getSubType("issuer.GBT")),
                        ServiceInfo(ServiceType.corda.getSubType("issuer.CBA"))),
                customOverrides = mapOf("nearestCity" to "Liverpool"))
        val centralBank = startNode(X500Name("CN=BoE,O=BoE,L=London,C=UK"), rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.GBP")),
                        ServiceInfo(ServiceType.corda.getSubType("issuer.USD")),
                        ServiceInfo(ServiceType.corda.getSubType("cash"))),
                customOverrides = mapOf("nearestCity" to "London"))

//        startWebserver(alice)
//        startWebserver(bob)
//        startWebserver(exchange)

        val notaryNode = notary.get()
        val aliceNode = alice.get()
        val bobNode = bob.get()
        val exchangeNode = exchange.get()
        val centralNode = centralBank.get()

        arrayOf(notaryNode, aliceNode, bobNode, exchangeNode, centralNode).forEach {
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

        val centClient = centralNode.rpcClientToNode()
        centClient.start(user.username, user.password)
        val centRPC = centClient.proxy()

        println("txns initiated")
        issueCash(centRPC, aRPC, notaryNode.nodeInfo.legalIdentity)
        issueCash(centRPC, bRPC, notaryNode.nodeInfo.legalIdentity)

        issueEquity(eRPC, aRPC, notaryNode.nodeInfo.legalIdentity)
        issueEquity(eRPC, bRPC, notaryNode.nodeInfo.legalIdentity)
        issueEquity(eRPC, aRPC, notaryNode.nodeInfo.legalIdentity)
        issueEquity(eRPC, bRPC, notaryNode.nodeInfo.legalIdentity)
        issueEquity(eRPC, aRPC, notaryNode.nodeInfo.legalIdentity)
        issueEquity(eRPC, bRPC, notaryNode.nodeInfo.legalIdentity)

        moveCash(100, eRPC, aRPC, notaryNode.nodeInfo.legalIdentity)
        
        println("Txns passed")

        waitForAllNodesToFinish()
    }
}

fun issueCash(centralBankRPC : CordaRPCOps, recipientRPC : CordaRPCOps, notaryNode : Party) : Boolean {
    val rand = Random()
    val currency = GBP
    val figure = rand.nextInt(150 + 1 - 50) + 50
    val amount = Amount((figure * 1000).toLong(), currency)

    val tx = centralBankRPC.startFlow(::CashIssueFlow, amount, OpaqueBytes.of(1), recipientRPC.nodeIdentity().legalIdentity, notaryNode)

    println("Cash (${amount}) issued to ${recipientRPC.nodeIdentity().legalIdentity}")

    return true
}

fun issueEquity(exchange : CordaRPCOps, recipientRPC : CordaRPCOps, notaryNode : Party) : Boolean {
    val rand = Random()
    val codes = arrayListOf("GBT", "CBA", "RIO", "BP")
    val stocks = arrayListOf("GBST Holdings Ltd", "Commonwealth Bank of Australia", "Rio Tinto Ltd", "British Petroleum")

    val stockIndex = rand.nextInt(codes.size - 0) + 0
    val figure = rand.nextInt(150 + 1 - 50) + 50
    val amount = Amount((figure * 100).toLong(), Security(codes[stockIndex], stocks[stockIndex]))

    val tx = exchange.startFlow(::SecuritiesIssueFlow, amount, OpaqueBytes.of(1), recipientRPC.nodeIdentity().legalIdentity, notaryNode)

    println("${figure} shares in ${codes[stockIndex]} (${stocks[stockIndex]}) issued to ${recipientRPC.nodeIdentity().legalIdentity}")

    return true
}

fun moveCash(amount : Int, sender : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) : Boolean {
    val currency = GBP
    val txn = sender.startFlow(::CashPaymentFlow, Amount(amount.toLong(), currency), recipient.nodeIdentity().legalIdentity)

    println("${amount} ${currency} sent to ${recipient.nodeIdentity().legalIdentity} from ${sender.nodeIdentity().legalIdentity}")

    return true
}

fun moveEquity(amount : Int, stock : String, sender : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) : Boolean {
    val rand = Random()
    val codes = arrayListOf("GBT", "CBA", "RIO", "BP")
    val stocks = arrayListOf("GBST Holdings Ltd", "Commonwealth Bank of Australia", "Rio Tinto Ltd", "British Petroleum")
    val stockIndex = rand.nextInt(codes.size - 0) + 0

    val figure = rand.nextInt(150 + 1 - 50) + 50
    val amount = Amount((figure * 100).toLong(), Security(codes[stockIndex], stocks[stockIndex]))


    val txn = sender.startFlow(::OwnershipTransferFlow, amount, recipient.nodeIdentity().legalIdentity)

    println("${amount.quantity} shares in ${amount.token.code} transferred to ${recipient.nodeIdentity().legalIdentity} from ${sender.nodeIdentity().legalIdentity}")


    return true
}