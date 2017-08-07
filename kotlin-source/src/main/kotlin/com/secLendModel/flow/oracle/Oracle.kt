package com.secLendModel.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.MerkleTreeException
import net.corda.core.identity.Party
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import org.apache.commons.io.IOUtils
import java.util.*
import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
@CordaService
class Oracle(val identity: Party, val services: ServiceHub) : SingletonSerializeAsToken() {
    //TODO: This serviceIdentitiesList is empty and the node actually isnt instantiated
    //constructor(services: PluginServiceHub) : this(services.myInfo.serviceIdentities(PriceType.type).first(), services)
    constructor(services: PluginServiceHub) : this(services.myInfo.legalIdentity, services)
    //val priceList = addDefaultPrices()
    //This was giving an IO error so for testing/debugging ive changed it
    val priceList = setOf<Pair<String, Amount<Currency>>>(Pair("GBT",Amount<Currency>(10000, CURRENCY)), Pair("CBA",Amount<Currency>(8900, CURRENCY)))

    //@JvmField
    //val type = PriceType.type


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
                //else -> throw IllegalArgumentException("Oracle received data of different type than expected.")
                else -> commandValidator(elem as Command);
            }
        }

        val leaves = ftx.filteredLeaves
        //if (!leaves.checkWithFun(::check))
          //  throw IllegalArgumentException()

        // It all checks out, so we can return a signature.
        //
        // Note that we will happily sign an invalid transaction, as we are only being presented with a filtered
        // version so we can't resolve or check it ourselves. However, that doesn't matter much, as if we sign
        // an invalid transaction the signature is worthless.
        val signature = services.keyManagementService.sign(ftx.rootHash.bytes, services.myInfo.legalIdentity.owningKey)
        return DigitalSignature.LegallyIdentifiable(identity, signature.bytes)
    }
}