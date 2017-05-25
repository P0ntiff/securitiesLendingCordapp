package com.secLendModel.plugin

import com.secLendModel.api.HttpApi
import com.secLendModel.flow.SecuritiesIssueFlow
import com.secLendModel.flow.SelfIssueCashFlow
import com.secLendModel.flow.SelfIssueSecuritiesFlow
import com.secLendModel.flow.TradeFlow
import com.secLendModel.service.Service
import com.secLendModel.state.Security
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.crypto.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.PluginServiceHub
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.serialization.SerializationCustomization
import java.util.function.Function

class Plugin : CordaPluginRegistry() {
    /**
     * A list of classes that expose web APIs.
     */
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::HttpApi))

    /**
     * A list of flows required for this CorDapp.
     */
    override val requiredFlows: Map<String, Set<String>> = mapOf(
            TradeFlow.Initiator::class.java.name to setOf(),
            SelfIssueCashFlow::class.java.name to setOf(Amount::class.java.name),
            SelfIssueSecuritiesFlow::class.java.name to setOf(Amount::class.java.name),
            SecuritiesIssueFlow::class.java.name to setOf(Amount::class.java.name,
                    OpaqueBytes::class.java.name, Party::class.java.name, Party::class.java.name)


    )

    /**
     * A list of long-lived services to be hosted within the node.
     */
    override val servicePlugins: List<Function<PluginServiceHub, out Any>> = listOf(Function(Service::Service))

    /**
     * A list of directories in the resources directory that will be served by Jetty under /web.
     * The secLendModel's web frontend is accessible at /web/secLendModel.
     */
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/secLendModel
            "secLendModel" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )

    /**
     * Whitelisting the required types for serialisation by the Corda node.
     */
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        return true
    }
}