package com.secLendModel.flow.oracle

import net.corda.core.contracts.Amount
import net.corda.core.crypto.DigitalSignature
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*


//Called from a flow that requires an update to margin or stockPrice
//Returns a Pair of (stockPrice, transactionBuilder) where the stockPrice is the officially signed
//price of the stock and the transactionBuilder contains the oracle's signature

//TODO: Rethink whether we need a state w/ signature and issue command to store stockPrice or not

open class PriceUpdateFlow(val code : String,
                           val partiesInvolved : List<Party>,
                           val oracle : Party,
                           val tx : TransactionBuilder) : FlowLogic<Pair<Amount<Currency>, TransactionBuilder>>() {

    override fun call() : Pair<Amount<Currency>, TransactionBuilder> {
        val price =  subFlow(PriceQueryFlow(oracle))
//        tx.addCommand(price, oracle.owningKey)
        val signature = subFlow(PriceSignFlow(oracle))
        tx.addSignatureUnchecked(signature)

        return Pair(price, tx)
    }



    @InitiatingFlow
    class PriceQueryFlow(val oracle : Party) : FlowLogic<Amount<Currency>>() {
        override fun call() : Amount<Currency> {
            return sendAndReceive<Amount<Currency>>(oracle, Unit).unwrap { it }
        }
    }


    class PriceSignFlow(val oracle : Party) : FlowLogic<DigitalSignature.LegallyIdentifiable>() {
        override fun call() : DigitalSignature.LegallyIdentifiable {
            return sendAndReceive<DigitalSignature.LegallyIdentifiable>(oracle, Unit).unwrap { it }
        }

    }
}