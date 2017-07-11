package com.secLendModel

import com.secLendModel.flow.OwnershipTransferFlow
import com.secLendModel.flow.SecuritiesIssueFlow
import com.secLendModel.flow.SelfIssueCashFlow
import com.secLendModel.flow.TradeFlow.Seller
import com.secLendModel.flow.TradeFlow.Buyer
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.getOrThrow
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.OpaqueBytes
import net.corda.flows.CashExitFlow
import net.corda.flows.CashIssueFlow
import net.corda.flows.CashPaymentFlow
import net.corda.node.services.startFlowPermission
import net.corda.nodeapi.User
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import org.bouncycastle.asn1.x500.X500Name
import java.util.*

//@JvmField val GBT = Security.getInstance("GBT")

//Identities of parties in the network
val EXCHANGE = X500Name("CN=LSE Ltd,O=LSE Ltd,L=Liverpool,C=UK")
val CENTRALBANK = X500Name("CN=BoE,O=BoE,L=London,C=UK")
val NOTARY = X500Name("CN=Notary Service,O=R3,OU=corda,L=Zurich,C=CH,OU=corda.notary.validating")
val ARNOLD = X500Name("CN=Alice Corp,O=Alice Corp,L=Madrid,C=ES")
val BARRY = X500Name("CN=Bob Plc,O=Bob Plc,L=Rome,C=IT")

//Shares to be on issue by exchange
val MARKET = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.RIO")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.GBT")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.CBA")))
val CODES = arrayListOf("GBT", "CBA")//, "RIO", "BP")
val STOCKS = arrayListOf("GBST Holdings Ltd", "Commonwealth Bank of Australia")//, "Rio Tinto Ltd", "British Petroleum")

//Currencies to be on issue by central bank
val CURRENCIES = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.GBP")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.USD")),
        ServiceInfo(ServiceType.corda.getSubType("cash")))
//Current currency in use
val CURRENCY = GBP

fun main(args: Array<String>) {
    val portAllocation = PortAllocation.Incremental(20000)
    // No permissions required as we are not invoking flows.
    val permissions = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<CashPaymentFlow>(),
            startFlowPermission<CashExitFlow>(),
            startFlowPermission<SelfIssueCashFlow>(),
            startFlowPermission<SecuritiesIssueFlow>(),
            startFlowPermission<OwnershipTransferFlow>(),
            startFlowPermission<SecuritiesPreparationFlow>(),
            startFlowPermission<Seller>(),
            startFlowPermission<Buyer>()
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
        //LINES

        //Third Change
        val aClient = arnoldNode.rpcClientToNode()
        val aRPC = aClient.start(user.username, user.password).proxy

        val bClient = barryNode.rpcClientToNode()
        val bRPC = bClient.start(user.username, user.password).proxy

        val eClient = exchangeNode.rpcClientToNode()
        val eRPC = eClient.start(user.username, user.password).proxy

        val cbClient = centralNode.rpcClientToNode()
        val cbRPC = cbClient.start(user.username, user.password).proxy

        println("TXNS INITIATED")
        issueCash(cbRPC, aRPC, notaryNode.nodeInfo.notaryIdentity)
        issueCash(cbRPC, bRPC, notaryNode.nodeInfo.notaryIdentity)
        issueEquity(eRPC, aRPC, notaryNode.nodeInfo.notaryIdentity)
        issueEquity(eRPC, bRPC, notaryNode.nodeInfo.notaryIdentity)

        //Send some assets around the ledger
        //moveCash(aRPC, bRPC)
        moveEquity(aRPC, bRPC)
        moveEquity(bRPC, aRPC)
        moveEquity(aRPC, bRPC)
        moveEquity(bRPC, aRPC)
        moveEquity(aRPC, bRPC)
        moveEquity(bRPC, aRPC)

        //DVP trades of cash for equity between sellers and buyers
        tradeEquity(aRPC, bRPC)
        tradeEquity(bRPC, aRPC)
        tradeEquity(aRPC, bRPC)
        tradeEquity(bRPC, aRPC)

        println("ALL TXNS SUBMITTED")
        waitForAllNodesToFinish()
    }
}

/** Grants a cash holding of a digital fiat currency (assumed to be issued by a central bank) to the recipient.
 *
 *  @param centralBank = qualified issuer of digital fiat currency, a node on the network
 *  @param recipient = party receiving cash
 */
fun issueCash(centralBank : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) {
    val rand = Random()
    val dollaryDoos = (rand.nextInt(150 + 1 - 50) + 50).toLong() * 1000000
    val amount = Amount(dollaryDoos, CURRENCY)

    centralBank.startTrackedFlow(::CashIssueFlow, amount, OpaqueBytes.of(1), recipient.nodeIdentity().legalIdentity, notaryNode).returnValue.getOrThrow()
    println("${dollaryDoos} units of $CURRENCY issued to ${recipient.nodeIdentity().legalIdentity}")
}

/** A simple CashPaymentFlow from sender to recipient. Sends a random amount of cash.
 *
 *  @param sender = party sending cash
 *  @param recipient = party receiving cash
 */
fun moveCash(sender : CordaRPCOps, recipient : CordaRPCOps) {
    val rand = Random()
    val dollaryDoos = (rand.nextInt(150 + 1 - 50) + 50).toLong() * 1000
    val amount = Amount(dollaryDoos, CURRENCY)

    sender.startTrackedFlow(::CashPaymentFlow, amount, recipient.nodeIdentity().legalIdentity).returnValue.getOrThrow()
    println("$dollaryDoos units of $CURRENCY sent to ${recipient.nodeIdentity().legalIdentity} from ${sender.nodeIdentity().legalIdentity}")
}

/** Grants holdings of each security issued on the ledger to a party on the ledger.
 *  Similar to CashIssueFlow flow but for securities.
 *
 *  @param exchange = trusted party who issues securities on the network
 *  @param recipient = party gaining ownership of security
 */
fun issueEquity(exchange : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) {
    val rand = Random()
    for (code in CODES) {
        val figure = (rand.nextInt(150 + 1 - 50) + 50) * 100

        exchange.startTrackedFlow(::SecuritiesIssueFlow,
                code,
                figure,
                recipient.nodeIdentity().legalIdentity,
                notaryNode).returnValue.getOrThrow()
        println("$figure shares in $code (${STOCKS[CODES.indexOf(code)]}) issued to ${recipient.nodeIdentity().legalIdentity}")
    }
}

/** Selects a random stock (with a random quantity) for the sender to sell "for free" (i.e receives nothing in return from the recipient).
 *  Similar to CashPaymentFlow flow but for securities.
 *
 *  @param sender = party relinquishing ownership of security
 *  @param recipient = party gaining ownership of security
 */
fun moveEquity(sender : CordaRPCOps, recipient : CordaRPCOps) {
    val rand = Random()
    val stockIndex = rand.nextInt(CODES.size)
    val figure = (rand.nextInt(150 + 1 - 50) + 50)

    sender.startTrackedFlow(::OwnershipTransferFlow, CODES[stockIndex], figure, recipient.nodeIdentity().legalIdentity).returnValue.getOrThrow()
    println("${figure} shares in '${CODES[stockIndex]}' transferred to recipient '" +
            "${recipient.nodeIdentity().legalIdentity}' from sender '${sender.nodeIdentity().legalIdentity}'")
}

/** Selects a random stock (and a random quantity) for the sender to sell to the recipient for cash.
 *  Also selects a random sharePrice for each share sold
 *  Similar to TwoPartyTradeFlow flow but for securities.
 *
 *  @param seller = party relinquishing ownership of security and gaining cash
 *  @param buyer = party gaining ownership of security and paying with cash
 */
fun tradeEquity(seller : CordaRPCOps, buyer : CordaRPCOps) {
    val rand = Random()
    val stockIndex = rand.nextInt(CODES.size - 0) + 0
    val figure = (rand.nextInt(150 + 1 - 50) + 50)

    val dollaryDoos = (rand.nextInt(150 + 1 - 50) + 50).toLong() * 100
    val sharePrice = Amount(dollaryDoos, CURRENCY)

    seller.startFlow(::Seller, CODES[stockIndex], figure, sharePrice, buyer.nodeIdentity().legalIdentity).returnValue.getOrThrow()
    println("Trade Finalised: ${figure} shares in ${CODES[stockIndex]} at ${sharePrice} each sold to buyer '" +
            "${buyer.nodeIdentity().legalIdentity}' by seller '${seller.nodeIdentity().legalIdentity}'")
}