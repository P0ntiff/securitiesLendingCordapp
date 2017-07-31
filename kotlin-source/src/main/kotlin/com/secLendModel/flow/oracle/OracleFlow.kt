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
data class stockPrice(val value: Pair<String,Amount<Currency>>) : CommandData


//TODO: Confirm this is like FixSignHandler and FixQueryHandler classes in tutorial
object oracleFlow {
    /**
     * Flow for querying the oracle and getting a price back
     */

    @InitiatedBy(PriceUpdateFlow.PriceQueryFlow::class)
    class QueryHandler(val otherParty: Party, val code: String): FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            //Recieve the request for oracle Data from PriceUpdateFlow
            val request = receive<Amount<Currency>>(otherParty).unwrap { it }
            //Query the oracle and get the data we need
            val oracle = serviceHub.cordaService(Oracle::class.java)
            val oracleData = oracle.query(code)
            //Send the oracle data to the otherParty in the loan
            send(otherParty, oracleData)
        }
    }

    @InitiatedBy(PriceUpdateFlow.PriceSignFlow::class)
    class SignHandler (val otherParty: Party) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val request = receive<FilteredTransaction>(otherParty).unwrap { it }
            val oracle = serviceHub.cordaService(Oracle::class.java)
            val signRequest = oracle.sign(request)
            send(otherParty,signRequest);
        }
    }
}





@ThreadSafe
@CordaService
class Oracle(val identity: Party, private val signingKey: PublicKey, val services: ServiceHub) : SingletonSerializeAsToken() {
    constructor(services: PluginServiceHub) : this(
            services.myInfo.serviceIdentities(type).first(),
            services.myInfo.serviceIdentities(type).first().owningKey.keys.first { services.keyManagementService.keys.contains(it) },
            services
    )

    val priceList = addDefaultPrices()

    companion object {
        //TODO ask ben about this JvmField property
        //@JvmField
        val type = ServiceType.corda.getSubType("stock_prices")
    }


    private fun addDefaultPrices(): Set<Pair<String, Amount<Currency>>> {
        return  parseFile(IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("com/secLendModel/example_prices.txt"), Charsets.UTF_8.name()))
    }

    //Parse a file with lines containing prices in the form GBT = 100
    fun parseFile(s: String): Set<Pair<String, Amount<Currency>>> {
        val prices = s.lines().
                map(String::trim).
                // Filter out comment and empty lines.
                filterNot { it.startsWith("#") || it.isBlank() }.
                map(this::parsePrices).
                toSet()
        return prices
    }

    fun parsePrices(s: String): Pair<String, Amount<Currency>> {
        try {
            val (key, pair) = s.split("=").map(String::trim)
            val value = Amount(pair.toLong(), CURRENCY)
            val returnPair = Pair(key, value)
            return returnPair

        } catch(e: Exception) {
            throw IllegalArgumentException("Unable to parse file for prices")
        }

    }


    @Suspendable
    fun query(code: String): Amount<Currency> {
        priceList.forEach { if(it.first == code) return it.second }
        throw IllegalArgumentException("No prices found for security $code")
    }

    fun sign(ftx: FilteredTransaction): DigitalSignature.LegallyIdentifiable {
        if (!ftx.verify()) {
            throw MerkleTreeException("Rate Fix Oracle: Couldn't verify partial Merkle tree.")
        }
        // Performing validation of obtained FilteredLeaves.
        fun commandValidator(elem: Command): Boolean {
            if (!(identity.owningKey in elem.signers))
                throw IllegalArgumentException("Our signature was not present in the comamnd")
            //Get the price sent with the transaction
            try{
                val stockPriceData = elem.value as stockPrice
                val price = stockPriceData.value.second.quantity
                val code = stockPriceData.value.first
                if (query(code).quantity != price) throw IllegalArgumentException("Prices do not match oracles price")
                return true
            } catch(e: Exception){
                throw IllegalArgumentException("Issue in getting stockPrice")
            }


        }

        fun check(elem: Any): Boolean {
            return when (elem) {
                is Command -> commandValidator(elem)
                else -> throw IllegalArgumentException("Oracle received data of different type than expected.")
            }
        }


        val leaves = ftx.filteredLeaves
        if (!leaves.checkWithFun(::check))
            throw IllegalArgumentException()

        // It all checks out, so we can return a signature.
        //
        // Note that we will happily sign an invalid transaction, as we are only being presented with a filtered
        // version so we can't resolve or check it ourselves. However, that doesn't matter much, as if we sign
        // an invalid transaction the signature is worthless.
        val signature = services.keyManagementService.sign(ftx.rootHash.bytes, signingKey)
        return DigitalSignature.LegallyIdentifiable(identity, signature.bytes)
    }


}