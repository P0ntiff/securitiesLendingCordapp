package com.secLendModel.flow.oracle

import com.secLendModel.CURRENCY
import io.atomix.copycat.protocol.QueryRequest
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.crypto.DigitalSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*
import java.util.function.Predicate


//Called from a flow that requires an update to margin or stockPrice
//Returns a Pair of (stockPrice, transactionBuilder) where the stockPrice is the officially signed
//price of the stock and the transactionBuilder contains the oracle's signature

open class PriceUpdateFlow(val code : String,
                           val partiesInvolved : List<Party>,
                           val oracle : Party,
                           val tx : TransactionBuilder) : FlowLogic<Pair<Amount<Currency>, TransactionBuilder>>() {

    override fun call() : Pair<Amount<Currency>, TransactionBuilder> {
        val price =  subFlow(PriceQueryFlow(oracle, code))
        //stockPrice command data is added to the tx -> contains the code and current ticker price
        val stockPrice = stockPrice(Pair(code,price))
        tx.addCommand(stockPrice,oracle.owningKey)
        //Sign and confirm signatures for the tx
        //TODO: Create our own filtering function to check the sttached signature is from oracle, for now we just accept
        val mtx = tx.toWireTransaction().buildFilteredTransaction(filtering = Predicate{true})
        val signature = subFlow(PriceSignFlow(oracle, mtx, tx))
        tx.addSignatureUnchecked(signature)
        return Pair(price, tx)
    }

    @InitiatingFlow
    class PriceQueryFlow(val oracle : Party, val code: String) : FlowLogic<Amount<Currency>>() {
        override fun call() : Amount<Currency> {
            //Send the code we want a price update for to the oracle (This calls OracleFlow.QueryHandler.
            val request = sendAndReceive<Amount<Currency>>(oracle, code).unwrap {
            //TODO: Any required checks go here
                it
            }
            return request
        }
    }

    @InitiatingFlow
    class PriceSignFlow(val oracle : Party, val partialMerkleTx: FilteredTransaction, val tx: TransactionBuilder) : FlowLogic<DigitalSignature.LegallyIdentifiable>() {
        override fun call() : DigitalSignature.LegallyIdentifiable {
            val response = sendAndReceive<DigitalSignature.LegallyIdentifiable>(oracle,partialMerkleTx).unwrap {
                //Check that it was actually the oracle that signed
                check(it.signer == oracle)
                //check that this signature is contained in the tx
                tx.checkSignature(it)
                it
            }
            return response
        }
    }
}