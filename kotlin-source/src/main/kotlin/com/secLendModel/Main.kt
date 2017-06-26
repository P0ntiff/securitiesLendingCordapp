package com.secLendModel

import com.secLendModel.flow.OwnershipTransferFlow
import com.secLendModel.flow.SecuritiesIssueFlow
import com.secLendModel.flow.SelfIssueCashFlow
import com.secLendModel.flow.SelfIssueSecuritiesFlow
import com.secLendModel.contract.Security
import com.secLendModel.contract.SecurityClaim
import net.corda.client.rpc.notUsed
import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.filterStatesOfType
import net.corda.core.getOrThrow
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.utilities.ALICE
import net.corda.core.utilities.BOB
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.flows.CashExitFlow
import net.corda.flows.CashIssueFlow
import net.corda.flows.CashPaymentFlow
import net.corda.node.driver.PortAllocation
import net.corda.node.driver.driver
import net.corda.node.services.startFlowPermission
import net.corda.nodeapi.User
import net.corda.node.services.transactions.ValidatingNotaryService
import org.bouncycastle.asn1.x500.X500Name
import java.util.*

@JvmField val GBT = Security.getInstance("GBT")

//Identities of parties in the network
val BANKA = X500Name("CN=UK Bank Plc,O=UK Bank Plc,L=London,C=UK")
val BANKB = X500Name("CN=USA Bank Corp,O=USA Bank Corp,L=New York,C=US")
val EXCHANGE = X500Name("CN=LSE Ltd,O=LSE Ltd,L=Liverpool,C=UK")
val CENTRALBANK = X500Name("CN=BoE,O=BoE,L=London,C=UK")
val NOTARY = X500Name("CN=Notary Service,O=R3,OU=corda,L=Zurich,C=CH,OU=corda.notary.validating")
val ARNOLD = X500Name("CN=Alice Corp,O=Alice Corp,L=Madrid,C=ES")
val BARRY = X500Name("CN=Bob Plc,O=Bob Plc,L=Rome,C=IT")

//Shares to be on issue by exchange
val MARKET = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.RIO")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.GBT")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.CBA")))
//Currencies to be on issue by central bank
val CURRENCIES = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.GBP")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.USD")),
        ServiceInfo(ServiceType.corda.getSubType("cash")))

fun main(args: Array<String>) {
    val portAllocation = PortAllocation.Incremental(20000)
    // No permissions required as we are not invoking flows.
    val permissions = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<CashPaymentFlow>(),
            startFlowPermission<CashExitFlow>(),
            startFlowPermission<SelfIssueCashFlow>(),
            startFlowPermission<SecuritiesIssueFlow>(),
            startFlowPermission<SelfIssueSecuritiesFlow>(),
            startFlowPermission<OwnershipTransferFlow>()
    )
    val user = User("user1", "test", permissions = permissions)
    driver(portAllocation = portAllocation) {
        val notary = startNode(NOTARY, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
        val arnold = startNode(ARNOLD, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))))
        val barry = startNode(BARRY, rpcUsers = arrayListOf(user),
                advertisedServices = setOf(ServiceInfo(ServiceType.corda.getSubType("cash"))))
        val exchange = startNode(EXCHANGE, rpcUsers = arrayListOf(user),
                advertisedServices = MARKET)
        val centralBank = startNode(CENTRALBANK, rpcUsers = arrayListOf(user),
                advertisedServices = CURRENCIES)

        val notaryNode = notary.get()
        val arnoldNode = arnold.get()
        val barryNode = barry.get()
        val exchangeNode = exchange.get()
        val centralNode = centralBank.get()

        arrayOf(notaryNode, arnoldNode, barryNode, exchangeNode, centralNode).forEach {
            println("${it.nodeInfo.legalIdentity} started on ${it.configuration.rpcAddress}")
        }

        val aClient = arnoldNode.rpcClientToNode()
        val aConnection = aClient.start(user.username, user.password)
        val aRPC = aConnection.proxy

        val bClient = barryNode.rpcClientToNode()
        val bConnection = bClient.start(user.username, user.password)
        val bRPC = bConnection.proxy

        val eClient = exchangeNode.rpcClientToNode()
        val eConnection = eClient.start(user.username, user.password)
        val eRPC = eConnection.proxy

        val cbClient = centralNode.rpcClientToNode()
        val cbConnection = cbClient.start(user.username, user.password)
        val cbRPC = cbConnection.proxy

        println("txns initiated")
        issueCash(cbRPC, aRPC, notaryNode.nodeInfo.notaryIdentity)
        issueCash(cbRPC, bRPC, notaryNode.nodeInfo.notaryIdentity)
        issueEquity(eRPC, aRPC, notaryNode.nodeInfo.notaryIdentity)
        issueEquity(eRPC, bRPC, notaryNode.nodeInfo.notaryIdentity)

        moveCash(aRPC, bRPC)
        moveEquity(aRPC, bRPC)
        
        println("Txns passed")
        //NEW Can now change startFlow() to startTrackedFlow() to get progress tracking

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

    for (code in codes) {
        val figure = rand.nextInt(150 + 1 - 50) + 50
        val amount = Amount((figure * 100).toLong(), Security(code, stocks[codes.indexOf(code)]))

        val tx = exchange.startTrackedFlow(::SecuritiesIssueFlow, amount, OpaqueBytes.of(1), recipientRPC.nodeIdentity().legalIdentity, notaryNode).returnValue.getOrThrow()
        println("${amount.quantity} shares in ${code} (${stocks[codes.indexOf(code)]}) issued to ${recipientRPC.nodeIdentity().legalIdentity}")
    }

    return true
}

fun moveCash(sender : CordaRPCOps, recipient : CordaRPCOps) : Boolean {
    val currency = GBP
    val rand = Random()
    val figure = rand.nextInt(150 + 1 - 50) + 50
    val txn = sender.startFlow(::CashPaymentFlow, Amount(figure.toLong(), currency), recipient.nodeIdentity().legalIdentity)

    println("${figure} units of ${currency} sent to ${recipient.nodeIdentity().legalIdentity} from ${sender.nodeIdentity().legalIdentity}")

    return true
}

fun moveEquity(sender : CordaRPCOps, recipient : CordaRPCOps) : Boolean {
    val rand = Random()
    val codes = arrayListOf("GBT", "CBA", "RIO", "BP")
    val stocks = arrayListOf("GBST Holdings Ltd", "Commonwealth Bank of Australia", "Rio Tinto Ltd", "British Petroleum")
    val stockIndex = rand.nextInt(codes.size - 0) + 0

    val figure = rand.nextInt(150 + 1 - 50) + 50
    val amount = Amount((figure * 1).toLong(), Security(codes[stockIndex], stocks[stockIndex]))

    //get a list of holdings
    val (vault, vaultUpdates) = sender.vaultAndUpdates()
    vaultUpdates.notUsed()
    val states = vault.filterStatesOfType<SecurityClaim.State>()
    val desiredStates : ArrayList<StateAndRef<SecurityClaim.State>> = arrayListOf()
    for (state in states) {
        if (state.state.data.amount.token.product.code == codes[stockIndex]) {
            desiredStates.add(state)
        }
    }
    val txn = sender.startFlow(::OwnershipTransferFlow, amount, desiredStates, recipient.nodeIdentity().legalIdentity)

    println("${amount.quantity} shares in ${amount.token.code} transferred to ${recipient.nodeIdentity().legalIdentity} from ${sender.nodeIdentity().legalIdentity}")


    return true
}