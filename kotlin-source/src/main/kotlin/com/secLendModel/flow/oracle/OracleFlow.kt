package com.secLendModel.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.CommandData
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.utilities.unwrap
import java.util.*


/**
 * Created by raymondm on 18/07/2017.
 *
 * Basic flow for a priceUpdate oracle which reads in stock price data from a txt file to get the current price
 */
data class stockPrice(val value: Pair<String, Amount<Currency>>) : CommandData


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
            //Leaving them here incase we need to revert back to this
            //val oracle2 = serviceHub.networkMapCache.getNodesWithService(PriceType.type).single()
            //val oracleService = oracle2.serviceIdentities(PriceType.type).single()
            send(otherParty, oracle.sign(request))
            //send(otherParty, ora)

        }
    }
}