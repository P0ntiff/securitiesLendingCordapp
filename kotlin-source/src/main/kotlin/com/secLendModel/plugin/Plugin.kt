package com.secLendModel.plugin

import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.securities.TradeFlow.MarketOffer
import net.corda.core.contracts.TransactionType
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializationCustomization

class Plugin : CordaPluginRegistry() {

    /**
     * Whitelisting the required types for serialisation by the Corda node.
     */
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        custom.addToWhitelist(SecurityClaim::class.java)
        custom.addToWhitelist(SecurityLoan::class.java)
        custom.addToWhitelist(TransactionType.General.Builder::class.java)
        custom.addToWhitelist(Boolean::class.java)
        custom.addToWhitelist(MarketOffer::class.java)
        return true
    }
}