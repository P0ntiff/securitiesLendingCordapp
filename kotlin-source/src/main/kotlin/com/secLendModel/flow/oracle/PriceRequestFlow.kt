package com.secLendModel.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.crypto.DigitalSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*
import java.util.function.Predicate


//Called from a flow that requires an update to margin or stockPrice
//Returns a Pair of (stockPrice, transactionBuilder) where the stockPrice is the officially signed
//price of the stock and the transactionBuilder contains the oracle's signature
open class PriceRequestFlow(val code : String,
                           val tx : TransactionBuilder) : FlowLogic<Pair<Amount<Currency>, TransactionBuilder>>() {
    @Suspendable
    override fun call() : Pair<Amount<Currency>, TransactionBuilder> {
        //Get the new price
        val oracle = serviceHub.cordaService(Oracle::class.java)
        val price =  subFlow(PriceQueryFlow(code))

        //stockPrice command data is added to the tx -> contains the code and current ticker price
        val stockPrice = stockPrice(Pair(code, price))
        tx.addCommand(stockPrice, oracle.identity.owningKey)

        //Sign and confirm signatures for the tx
        val mtx = tx.toWireTransaction().buildFilteredTransaction(filtering = Predicate{true})
        val signature = subFlow(PriceSignFlow(oracle.identity, mtx, tx))
        tx.addSignatureUnchecked(signature)
        return Pair(price, tx)
    }
    @StartableByRPC
    @InitiatingFlow
    class PriceQueryFlow(val code: String) : FlowLogic<Amount<Currency>>() {
        @Suspendable
        override fun call() : Amount<Currency> {
            val oracle = serviceHub.cordaService(Oracle::class.java)
            //Send the code we want a price update for to the oracle (This calls OracleFlow.QueryHandler in response)
            val request = sendAndReceive<Amount<Currency>>(oracle.identity, code).unwrap {
            //TODO: Any required checks go here
                it
            }
            return request
        }
    }

    @InitiatingFlow
    class PriceSignFlow(val oracle : Party, val partialMerkleTx: FilteredTransaction, val tx: TransactionBuilder) : FlowLogic<DigitalSignature.LegallyIdentifiable>() {
        @Suspendable
        override fun call() : DigitalSignature.LegallyIdentifiable {
            val response = sendAndReceive<DigitalSignature.LegallyIdentifiable>(oracle, partialMerkleTx)
            return response.unwrap { sig ->
                check(sig.signer.owningKey == oracle.owningKey)
                tx.checkSignature(sig)
                sig
            }

        }
    }
}