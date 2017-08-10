package com.secLendModel

import com.secLendModel.flow.securities.OwnershipTransferFlow
import com.secLendModel.flow.securities.SecuritiesIssueFlow
import com.secLendModel.flow.securities.TradeFlow.Seller
import com.secLendModel.flow.securities.TradeFlow.Buyer
import com.secLendModel.flow.SecuritiesPreparationFlow
import com.secLendModel.flow.oracle.OracleFlow
import com.secLendModel.flow.oracle.PriceRequestFlow
import com.secLendModel.flow.oracle.PriceType
import com.secLendModel.flow.securitiesLending.LoanIssuanceFlow.Initiator
import com.secLendModel.flow.securitiesLending.LoanIssuanceFlow.Acceptor
import com.secLendModel.flow.securitiesLending.LoanUpdateFlow.Updator
import com.secLendModel.flow.securitiesLending.LoanUpdateFlow.UpdateAcceptor
import com.secLendModel.flow.securitiesLending.LoanTerminationFlow.Terminator
import com.secLendModel.flow.securitiesLending.LoanTerminationFlow.TerminationAcceptor
import com.secLendModel.flow.securitiesLending.LoanTerms
import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.currency
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
import net.corda.node.internal.AbstractNode
import net.corda.node.internal.Node
import net.corda.node.services.startFlowPermission
import net.corda.nodeapi.User
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import org.bouncycastle.asn1.x500.X500Name
import java.math.BigDecimal
import java.util.*

//CONSTANTS:
//Legal identities of parties in the network
val EXCHANGE = X500Name("CN=ASX,O=ASX Ltd,L=Sydney,C=AU")
val CENTRALBANK = X500Name("CN=RBA,O=ReserveBankOfAustralia,L=Canberra,C=AU")
val NOTARY = X500Name("CN=Notary Service,O=R3,OU=corda,L=Zurich,C=CH,OU=corda.notary.validating")
val ARNOLD = X500Name("CN=Alice Corp,O=Alice Corp,L=Madrid,C=ES")
val BARRY = X500Name("CN=Bob Plc,O=Bob Plc,L=Rome,C=IT")
//val COLIN = X500Name("CN=Colin Plc,O=Colin Plc,L=Paris,C=FR")
//val ORACLE = X500Name("CN=Oracle SP,O=Oracle SP,L=Brisbane,C=AU")

//Shares to be on issue by exchange
val CODES = listOf(
        "GBT",
        "CBA",
        "RIO",
        "NAB"
)
val STOCKS = listOf(
        "GBST Holdings Ltd Ordinary Fully Paid",
        "Commonwealth Bank of Australia Ordinary Fully Paid",
        "Rio Tinto Ltd Ordinary Fully Paid",
        "National Australia Bank Ltd Ordinary Fully Paid"
)
val MARKET = setOf(ServiceInfo(ServiceType.corda.getSubType("issuer.GBT")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.CBA")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.RIO")),
        ServiceInfo(ServiceType.corda.getSubType("issuer.NAB"))
)

//Currencies to be on issue by the central bank
val CURRENCIES = setOf(
        ServiceInfo(ServiceType.corda.getSubType("issuer.AUD")),
        //ServiceInfo(ServiceType.corda.getSubType("issuer.USD")),
        //ServiceInfo(ServiceType.corda.getSubType("issuer.GBP")),
        ServiceInfo(ServiceType.corda.getSubType("cash"))
)
//Current currency in use
@JvmField val AUD = currency("AUD")
val CURRENCY = AUD

fun main(args: Array<String>) {
    Simulation("Place runtime options here")
}

class Simulation(options : String?) {
    val cashPermissions = allocateCashPermissions()
    val securitiesTradingPermissions = allocateSecuritiesTradingPermissions()
    val securitiesLendingPermissions = allocateSecuritiesLendingPermissions()
    val specialPermissions = allocateSpecialPermissions()
    val oracleRequestPermissions = allocateOracleRequestPermissions()
    val stdUser = User("user1", "test",
            permissions = cashPermissions.plus(securitiesTradingPermissions).plus(securitiesLendingPermissions).plus(oracleRequestPermissions))
    val specialUser = User("manager", "test", permissions = specialPermissions)
    lateinit var notaryNode : NodeHandle
    lateinit var arnoldNode : NodeHandle
    lateinit var barryNode : NodeHandle
//    lateinit var colinNode : NodeHandle
    lateinit var exchangeNode : NodeHandle
    lateinit var centralNode : NodeHandle
//    lateinit var oracleNode : NodeHandle
    val parties = ArrayList<Pair<Party, CordaRPCOps>>()
    val stockMarkets = ArrayList<Pair<Party, CordaRPCOps>>()
    val cashIssuers = ArrayList<Pair<Party, CordaRPCOps>>()

    init {
        runSimulation()
    }

    fun runSimulation() {
        driver(portAllocation = PortAllocation.Incremental(20000), isDebug = false) {
            //Normal Users
            val arnold = startNode(ARNOLD, rpcUsers = arrayListOf(stdUser))
            val barry = startNode(BARRY, rpcUsers = arrayListOf(stdUser))
            //val colin = startNode(COLIN, rpcUsers = arrayListOf(stdUser))

            //Special Users (i.e asset issuers and oracles)
            val notary = startNode(NOTARY, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            //Stock issuer AND stock price oracle
            val exchange = startNode(EXCHANGE, rpcUsers = arrayListOf(specialUser),
                    advertisedServices = MARKET.plus(ServiceInfo(PriceType.type)))
            //Cash issuer
            val centralBank = startNode(CENTRALBANK, rpcUsers = arrayListOf(specialUser),
                    advertisedServices = CURRENCIES)
//            val oracle = startNode(ORACLE, advertisedServices = setOf(ServiceInfo(PriceType.type)))

            notaryNode = notary.get()
            arnoldNode = arnold.get()
            barryNode = barry.get()
            //colinNode = colin.get()
            exchangeNode = exchange.get()
            centralNode = centralBank.get()
//            oracleNode = oracle.get()

            setUpNodes()
            simulateTransactions()
            waitForAllNodesToFinish()
        }
    }

    private fun simulateTransactions() {
        val stockMarket = stockMarkets.single().second
        val centralBank = cashIssuers.single().second

        //Test cash and equities asset issue
        parties.forEach {
            issueCash(centralBank, it.second, notaryNode.nodeInfo.notaryIdentity)
            issueEquity(stockMarket, it.second, notaryNode.nodeInfo.notaryIdentity)
        }

        //Test they can move stock and cash to another owner
        parties.forEach {
            moveCash(it.second)
            moveEquity(it.second)
        }
        //Test they can DVP trade stock
        parties.forEach {
            tradeEquity(it.second)
            tradeEquity(it.second)
        }

        //Test stock borrows and stock loans
        parties.forEach {
            //Loan out stock to a random counter party, where they initiate the deal
            val id = loanSecurities(it.second, true)
            //Loan out stock to a random counter party, where we initiate the deal
            val id2 = loanSecurities(it.second, false)
            //Borrow stock from a random counter party, where we initiate the deal
            val id3 = borrowSecurities(it.second, true)
            //Borrow stock from a random counter party, where they initiate the deal
            val id4 = borrowSecurities(it.second, false)

            updateMargin(id, it.second)
            updateMargin(id2, it.second)
            updateMargin(id3, it.second)
            updateMargin(id4, it.second)

            terminateLoan(id, it.second)
            terminateLoan(id2, it.second)
            terminateLoan(id3, it.second)
            terminateLoan(id4, it.second)
        }
    }

    private fun setUpNodes() {
        val aClient = arnoldNode.rpcClientToNode()
        val aRPC = aClient.start(stdUser.username, stdUser.password).proxy

        val bClient = barryNode.rpcClientToNode()
        val bRPC = bClient.start(stdUser.username, stdUser.password).proxy

//        val cClient = colinNode.rpcClientToNode()
//        val cRPC = cClient.start(stdUser.username, stdUser.password).proxy

        val eClient = exchangeNode.rpcClientToNode()
        val eRPC = eClient.start(specialUser.username, specialUser.password).proxy

        val cbClient = centralNode.rpcClientToNode()
        val cbRPC = cbClient.start(specialUser.username, specialUser.password).proxy

        parties.addAll(listOf(
                aRPC.nodeIdentity().legalIdentity to aRPC,
                bRPC.nodeIdentity().legalIdentity to bRPC)
//                cRPC.nodeIdentity().legalIdentity to cRPC
        )
        stockMarkets.add((eRPC.nodeIdentity().legalIdentity to eRPC))
        cashIssuers.add((cbRPC.nodeIdentity().legalIdentity to cbRPC))

        arrayOf(notaryNode, arnoldNode, barryNode, exchangeNode, centralNode).forEach {
            println("${it.nodeInfo.legalIdentity} started on ${it.configuration.rpcAddress}")
        }
    }

    private fun allocateCashPermissions() : Set<String> = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<CashPaymentFlow>(),
            startFlowPermission<CashExitFlow>()
    )
    private fun allocateSecuritiesTradingPermissions() : Set<String> = setOf(
            startFlowPermission<Seller>(),
            startFlowPermission<Buyer>(),
            startFlowPermission<OwnershipTransferFlow>(),
            startFlowPermission<SecuritiesPreparationFlow>(),
            startFlowPermission<PriceRequestFlow.PriceQueryFlow>(),
            startFlowPermission<PriceRequestFlow.PriceSignFlow>()
    )
    private fun allocateSecuritiesLendingPermissions() : Set<String> = setOf(
            startFlowPermission<Initiator>(),
            startFlowPermission<Acceptor>(),
            startFlowPermission<Updator>(),
            startFlowPermission<UpdateAcceptor>(),
            startFlowPermission<Terminator>(),
            startFlowPermission<TerminationAcceptor>()
    )
    private fun allocateOracleRequestPermissions() : Set<String> = setOf(
            startFlowPermission<PriceRequestFlow>(),
            startFlowPermission<PriceRequestFlow.PriceQueryFlow>(),
            startFlowPermission<PriceRequestFlow.PriceSignFlow>()
    )
    //Ledger asset issue (cash and securities), and oracle provision permissions
    private fun allocateSpecialPermissions() : Set<String> = setOf(
            startFlowPermission<CashIssueFlow>(),
            startFlowPermission<SecuritiesIssueFlow>(),
            startFlowPermission<OracleFlow.QueryHandler>(),
            startFlowPermission<OracleFlow.SignHandler>()
    )

    /** Grants a cash holding of a digital fiat currency (assumed to be issued by a central bank) to the recipient.
     *  @param centralBank = qualified issuer of digital fiat currency, a node on the network
     *  @param recipient = party receiving cash
     */
    private fun issueCash(centralBank : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) {
        val rand = Random()
        val dollaryDoos = BigDecimal((rand.nextInt(100 + 1 - 1) + 1) * 1000000)     // $1,000,000 to $100,000,000
        val amount = Amount.fromDecimal(dollaryDoos, CURRENCY)

        centralBank.startTrackedFlow(::CashIssueFlow, amount, OpaqueBytes.of(1), recipient.nodeIdentity().legalIdentity, notaryNode).returnValue.getOrThrow()
        println("Cash Issue: ${amount} units of $CURRENCY issued to ${recipient.nodeIdentity().legalIdentity}")
    }

    /** A simple CashPaymentFlow from sender to recipient. Sends a random amount of cash.
     *  @param sender = party sending cash
     */
    private fun moveCash(sender : CordaRPCOps) {
        val rand = Random()
        val dollaryDoos = BigDecimal((rand.nextInt(100 + 1 - 1) + 1) * 10000)   //$10,000 to $1,000,000
        val amount = Amount.fromDecimal(dollaryDoos, CURRENCY)
        val randomRecipient = parties.filter { it.first != sender.nodeIdentity().legalIdentity }[rand.nextInt(parties.size - 1)].first

        sender.startTrackedFlow(::CashPaymentFlow, amount, randomRecipient).returnValue.getOrThrow()
        println("Cash Payment: ${dollaryDoos} units of $CURRENCY sent to ${randomRecipient} from ${sender.nodeIdentity().legalIdentity}")
    }

    /** Grants holdings of each security issued on the ledger to a party on the ledger.
     *  Similar to CashIssueFlow flow but for securities.
     *  @param exchange = trusted party who issues securities on the network
     *  @param recipient = party gaining ownership of security
     */
    private fun issueEquity(exchange : CordaRPCOps, recipient : CordaRPCOps, notaryNode : Party) {
        val rand = Random()
        for (code in CODES) {
            val figure = (rand.nextInt(250 + 1 - 100) + 100) * 1000     //100,000 shares to 250,000 shares

            exchange.startTrackedFlow(::SecuritiesIssueFlow,
                    code,
                    figure,
                    recipient.nodeIdentity().legalIdentity,
                    notaryNode).returnValue.getOrThrow()
            println("Stock Issue: $figure shares in $code (${STOCKS[CODES.indexOf(code)]}) issued to ${recipient.nodeIdentity().legalIdentity}")
        }
    }

    /** Selects a random stock (with a random quantity) for the sender to sell "for free" (i.e receives nothing in return from the recipient).
     *  Similar to CashPaymentFlow flow but for securities.
     *  @param sender = party relinquishing ownership of security
     */
    private fun moveEquity(sender : CordaRPCOps) {
        val rand = Random()
        val stockIndex = rand.nextInt(CODES.size)
        val figure = (rand.nextInt(300 + 1 - 100) + 100) * 100     //10,000 shares to 30,000 shares
        val randomRecipient = parties.filter { it.first != sender.nodeIdentity().legalIdentity }[rand.nextInt(parties.size - 1)].first

        sender.startTrackedFlow(::OwnershipTransferFlow, CODES[stockIndex], figure, randomRecipient).returnValue.getOrThrow()
        println("Equity Transfer: ${figure} shares in '${CODES[stockIndex]}' transferred to recipient '" +
                "${randomRecipient}' from sender '${sender.nodeIdentity().legalIdentity}'")
    }

    /** Selects a random stock (and a random quantity) for the sender to sell to the recipient for cash.
     *  Also selects a random sharePrice for each share sold
     *  Similar to TwoPartyTradeFlow flow but for securities.
     *  @param seller = party relinquishing ownership of security and gaining cash
     */
    private fun tradeEquity(seller : CordaRPCOps) {
        val rand = Random()
        val stockIndex = rand.nextInt(CODES.size - 0) + 0
        //Quantity between 1,000 and 10,000 shares
        val quantity = (rand.nextInt(1000 + 1 - 100) + 100) * 10
        //Price between $50.00 and $110.00  per share (decimal)
        val dollaryDoos : BigDecimal = BigDecimal((rand.nextDouble() + 0.1) * (rand.nextInt(110 + 1 - 50) + 50))
        val sharePrice = Amount.fromDecimal(dollaryDoos, CURRENCY)
        val randomBuyer = parties.filter { it.first != seller.nodeIdentity().legalIdentity }[rand.nextInt(parties.size - 1)].first

        seller.startFlow(::Seller, CODES[stockIndex], quantity, sharePrice, randomBuyer).returnValue.getOrThrow()
        println("Trade Finalised: ${quantity} shares in ${CODES[stockIndex]} at ${sharePrice} each sold to buyer '" +
                "${randomBuyer}' by seller '${seller.nodeIdentity().legalIdentity}'")
    }

    /**Selects random stock and quantity to be loaned out to the borrower. These states are not exited yet
     * and simply shows an example of stock + collateral -> stock(on loan) + collateral(to lender) + securityLoanState
     *
     *  @param me = party lending out the securities
     *  @param BorrowerInitiates = 'true' for borrower to initiate the deal, 'false' for lender to initiate the deal
     */
    private fun loanSecurities(me: CordaRPCOps, BorrowerInitiates : Boolean): UniqueIdentifier {
        val rand = Random()
        val stockIndex = rand.nextInt(CODES.size - 0) + 0
        //Quantity between 10,000 and 50,000 shares
        val quantity = (rand.nextInt(500 + 1 - 100) + 100) * 100
        //Price between $50.00 and $110.00  per share (decimal)
        val dollaryDoos : BigDecimal = BigDecimal((rand.nextDouble() + 0.1) * (rand.nextInt(110 + 1 - 50) + 50))
        val sharePrice = Amount.fromDecimal(dollaryDoos, CURRENCY)
        //Percentage
        val margin : Double = 0.05
        val rebate : Double = 0.01
        //Days
        val length = 30
        val stockOnLoan : UniqueIdentifier
        //Pick a random party to be the borrower
        val randomBorrower = parties.filter { it.first != me.nodeIdentity().legalIdentity }[rand.nextInt(parties.size - 1)].second
        //Storage container for loan terms
        val loanTerms = LoanTerms(CODES[stockIndex], quantity, sharePrice,
                me.nodeIdentity().legalIdentity,
                randomBorrower.nodeIdentity().legalIdentity,
                margin, rebate, length)
        when (BorrowerInitiates) {
            true -> {
                //Counter party initiates the deal
                stockOnLoan = randomBorrower.startFlow(::Initiator, loanTerms).returnValue.getOrThrow()
            }
            false -> {
                //we initiate the deal
                stockOnLoan = me.startFlow(::Initiator, loanTerms).returnValue.getOrThrow()
            }
        }
        println("Loan Finalised: ${quantity} shares in ${CODES[stockIndex]} at ${sharePrice} each loaned to borrower '" +
                "${randomBorrower.nodeIdentity().legalIdentity}' by lender '${me.nodeIdentity().legalIdentity}' at a margin of ${margin}")
        return stockOnLoan
    }

    /**Selects random stock and quantity to be loaned out to the borrower
     *  @param me = party borrowing the securities
     *  @param BorrowerInitiates = 'true' for borrower to initiate the deal, 'false' for lender to initiate the deal
     */
    private fun borrowSecurities(me: CordaRPCOps, BorrowerInitiates : Boolean): UniqueIdentifier {
        val rand = Random()
        val stockIndex = rand.nextInt(CODES.size - 0) + 0
        //Quantity between 10,000 and 50,000 shares
        val quantity = (rand.nextInt(500 + 1 - 100) + 100) * 100
        //Price between $50.00 and $110.00  per share (decimal)
        val dollaryDoos : BigDecimal = BigDecimal((rand.nextDouble() + 0.1) * (rand.nextInt(110 + 1 - 50) + 50))
        val sharePrice = Amount.fromDecimal(dollaryDoos, CURRENCY)
        //Percentage
        val margin : Double = 0.05
        val rebate : Double = 0.01
        //Days
        val length = 30
        val stockOnLoan : UniqueIdentifier
        //Pick a random party to be the lender
        val randomLender = parties.filter { it.first != me.nodeIdentity().legalIdentity }[rand.nextInt(parties.size - 1)].second
        //Storage container for loan terms
        val loanTerms = LoanTerms(CODES[stockIndex], quantity, sharePrice,
                randomLender.nodeIdentity().legalIdentity,
                me.nodeIdentity().legalIdentity,
                margin, rebate, length)
        when (BorrowerInitiates) {
            true -> {
                //We initiate the deal
                stockOnLoan = me.startFlow(::Initiator, loanTerms).returnValue.getOrThrow()
            }
            false -> {
                //Counter party initiates the deal
                stockOnLoan = randomLender.startFlow(::Initiator, loanTerms).returnValue.getOrThrow()
            }
        }
        println("Loan Finalised: ${quantity} shares in ${CODES[stockIndex]} at ${sharePrice} each loaned to borrower '" +
                "${me.nodeIdentity().legalIdentity}' by lender '${randomLender.nodeIdentity().legalIdentity}' at a margin of ${margin}")
        return stockOnLoan
    }

    /**Takes a reference to a SecurityLoan and updates the margin on that security loan. Can be called by either
     * borrower or lender
     * @param id = UniqueIdentifier produced by issuance of a SecurityLoan
     * @param initiator = the party that wants to update the margin with the counterparty on the loan
     *
     */
    private fun updateMargin(id: UniqueIdentifier, initiator: CordaRPCOps): UniqueIdentifier {
        val updatedID = initiator.startFlow(::Updator, id).returnValue.getOrThrow()
        println("Margin updated on loan with old ID: '${id}' and  newID: '${updatedID}'")
        return updatedID
    }

    /**Takes a reference to a SecurityLoan and exits the loan from the ledger, provided both borrower and lender consent.
     * Returns cash collateral to the borrower, and stock holding to the lender.
     * @param id = UniqueIdentifier produced by issuance of a SecurityLoan
     * @param initiator = the party that wants to exit/terminate the loan (can be
     *
     */
    private fun terminateLoan(id: UniqueIdentifier, initiator: CordaRPCOps) {
        initiator.startFlow(::Terminator, id).returnValue.getOrThrow()
        println("Loan with ID '$id' terminated")
    }
}





