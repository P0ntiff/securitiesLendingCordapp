package com.secLendModel.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.sun.org.apache.xpath.internal.operations.Bool
import net.corda.contracts.Fix
import net.corda.contracts.FixOf
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.MerkleTreeException
import net.corda.core.crypto.keys
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.SingletonSerializationToken
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import org.apache.commons.io.IOUtils
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*
import javax.annotation.concurrent.ThreadSafe

/**
 * Created by raymondm on 18/07/2017.
 *
 * Basic flow for a priceUpdate oracle which reads in stock price data from a txt file to get the current price
 */
data class stockPrice(val value: Pair<String, Amount<Currency>>) : CommandData


//TODO: Confirm this is like FixSignHandler and FixQueryHandler classes in tutorial
object OracleFlow {
    @InitiatedBy(PriceRequestFlow.PriceQueryFlow::class)
    class QueryHandler(val requester: Party): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {

            //Receive the name of the security requested for a price update (from PriceUpdateFlow.PriceQueryFlow)
            val code = receive<String>(requester).unwrap {
                //TODO: Check we offer a price query service on this security
                it
            }
            //Query the oracle and get the data we need
            val response = serviceHub.cordaService(Oracle::class.java)
            //Send the price information back to the party who requested it
            send(requester, response.query(code))

            //val oracle = serviceHub.networkMapCache.getNodesWithService(PriceType.type).single().serviceIdentities(PriceType.type).single()
        }
    }

    @InitiatedBy(PriceRequestFlow.PriceSignFlow::class)
    class SignHandler (val otherParty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val request = receive<FilteredTransaction>(otherParty).unwrap { it }
            val oracle = serviceHub.cordaService(Oracle::class.java)
            //These calls are no longer used, can change to this method if there was multiple oracles within the network
            //val oracle2 = serviceHub.networkMapCache.getNodesWithService(PriceType.type).single()
            //val oracleService = oracle2.serviceIdentities(PriceType.type).single()
            send(otherParty, oracle.sign(request))
            //send(otherParty, ora)

        }
    }
}