package com.secLendModel.plugin

import com.secLendModel.api.HttpApi
import com.secLendModel.contract.Security
import com.secLendModel.contract.SecurityClaim
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializationCustomization
import java.util.function.Function

class Plugin : CordaPluginRegistry() {
    /**
     * A list of classes that expose web APIs.
     */
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::HttpApi))

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
        custom.addToWhitelist(Security::class.java)
        custom.addToWhitelist(SecurityClaim::class.java)
        return true
    }
}