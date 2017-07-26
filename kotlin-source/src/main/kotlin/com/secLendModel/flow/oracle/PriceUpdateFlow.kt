package com.secLendModel.flow.oracle

import com.secLendModel.CURRENCY
import io.atomix.copycat.protocol.QueryRequest
import net.corda.core.contracts.Amount
import net.corda.core.crypto.DigitalSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*


//Called from a flow that requires an update to margin or stockPrice
//Returns a Pair of (stockPrice, transactionBuilder) where the stockPrice is the officially signed
//price of the stock and the transactionBuilder contains the oracle's signature

//TODO: Rethink whether we need a state w/ signature and issue command to store stockPrice or not
//TODO: Is this upper priceUpdateFlow really needed? from tutorial these can be called individually quite easily
//TODO: Confirm this is like the FixQueryFlow and FixSignFlow in tutorial
open class PriceUpdateFlow(val code : String,
                           val partiesInvolved : List<Party>,
                           val oracle : Party,
                           val tx : TransactionBuilder,
                           val partialMerkleTx: FilteredTransaction) : FlowLogic<Pair<Amount<Currency>, TransactionBuilder>>() {

    override fun call() : Pair<Amount<Currency>, TransactionBuilder> {
        val price =  subFlow(PriceQueryFlow(oracle, code))
//        tx.addCommand(price, oracle.owningKey)
        val signature = subFlow(PriceSignFlow(oracle, partialMerkleTx))
        tx.addSignatureUnchecked(signature)
        return Pair(price, tx)
    }



    @InitiatingFlow
    class PriceQueryFlow(val oracle : Party, val code: String) : FlowLogic<Amount<Currency>>() {
        override fun call() : Amount<Currency> {
            //TODO: This isnt getting a specific price (What function is actually called within oracle flow?)
            //Send the code we want a price update for to the oracle
            val request = sendAndReceive<Amount<Currency>>(oracle, code).unwrap {
            //TODO: Any required checks go here
                it
            }
            return request
        }
    }

    @InitiatingFlow
    class PriceSignFlow(val oracle : Party, val partialMerkleTx: FilteredTransaction) : FlowLogic<DigitalSignature.LegallyIdentifiable>() {
        override fun call() : DigitalSignature.LegallyIdentifiable {
            //TODO: Check logic here -> PriceSignFlow should recieve a filteredTx and sign it, not sure how this works though
            val response = sendAndReceive<DigitalSignature.LegallyIdentifiable>(oracle,partialMerkleTx).unwrap {
                //TODO: Any checks needed go here
                it
            }
            return response
            //return sendAndReceive<DigitalSignature.LegallyIdentifiable>(oracle, Unit).unwrap { it }
            //Thinking this is the method that should be used, will need to pass in a filteredTx from the PriceUpdateFlow
        }

    }
}