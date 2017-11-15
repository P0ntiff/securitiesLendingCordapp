package com.secLendModel.flow.oracle

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.TransactionSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*
import java.util.function.Predicate


/** Flow to handle the actual calling of an oracle. Called from a flow that requires an update to margin or stockPrice
 * Returns a Pair of (stockPrice, transactionBuilder) where the stockPrice is the officially signed
 * price of the stock and the transactionBuilder contains the oracle's signature
 * @param code the code of the security for which the price is being requested
 * @param tx a transaction builder for which this new price will be attached to and signed
 * @returns a pair containing: an amount object for the updated price, and the transaction builder with the new price attached and the txn signed*/

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
        val mtx = tx.toWireTransaction(serviceHub).buildFilteredTransaction(filtering = Predicate{true})
        val signature = subFlow(PriceSignFlow(oracle.identity, mtx, tx))
        serviceHub.signInitialTransaction(tx)
        //tx.addSignatureUnchecked(signature)
        return Pair(price, tx)
    }

    /** Flow for the query of a price
     * @param code the code of the security for which the price update is being requested.
     * @returns the updated price of this security as an Amount<Currency>
     */
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

    /** Flow for signing the txn.
     * @param oracle the oracle who is signing the txn.
     * @param partialMerkleTx a partial merkle tree for this txn (used to verify signatures and txn validity)
     * @param tx the txn builder object to be signed.
     * @returns the digital signature of the oracle
     */
    @InitiatingFlow
    class PriceSignFlow(val oracle : Party, val partialMerkleTx: FilteredTransaction, val tx: TransactionBuilder) : FlowLogic<TransactionSignature>() {
        @Suspendable
        override fun call() : TransactionSignature {
            //Send the partially signed tx
            val flowSession = initiateFlow(oracle)
            val response = flowSession.sendAndReceive<TransactionSignature>(partialMerkleTx)

            return response.unwrap { sig ->
                //Validate that the oracle did sign this txn.
                check(sig.by == oracle.owningKey)
                //tx.checkSignature(sig) todo is this needed? for now removed because it is incompatiable with v1
                sig
            }

        }
    }
}