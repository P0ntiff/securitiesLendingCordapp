package com.secLendModel.plugin

import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.Oracle
import com.secLendModel.flow.securities.TradeFlow.MarketOffer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.TransactionBuilder
import net.corda.webserver.services.WebServerPluginRegistry

class SerializationPlugin : SerializationWhitelist {

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
}

class WebPlugin : WebServerPluginRegistry {

//    override val webApis = listOf(
//            Function(::MiscAPI))

    override val staticServeDirs: Map<String, String> = mapOf(
            // URL is /web/index
            "index" to javaClass.classLoader.getResource("index").toExternalForm(),
            // URL is /web/testcase
            "testcase" to javaClass.classLoader.getResource("testcase").toExternalForm()
    )
}