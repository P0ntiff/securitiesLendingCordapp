package com.secLendModel.plugin

import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.Oracle
import com.secLendModel.flow.securities.TradeFlow.MarketOffer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.TransactionBuilder

class Plugin : SerializationWhitelist {

    /**
     * Whitelisting the required types for serialisation by the Corda node.
     */
    override val whitelist: List<Class<*>> = listOf(
            SecurityClaim::class.java,
            SecurityLoan::class.java,
            TransactionBuilder::class.java,
            Boolean::class.java,
            MarketOffer::class.java,
            Oracle::class.java
    )
//    override fun whitelist(custom: SerializationCustomization): List<Class<*>> {
//        custom.addToWhitelist(SecurityClaim::class.java)
//        custom.addToWhitelist(SecurityLoan::class.java)
//        custom.addToWhitelist(TransactionType.General.Builder::class.java)
//        custom.addToWhitelist(Boolean::class.java)
//        custom.addToWhitelist(MarketOffer::class.java)
//        custom.addToWhitelist(Oracle::class.java)
//        return true
//    }
}