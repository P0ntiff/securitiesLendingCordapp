package com.secLendModel.contract

import net.corda.core.flows.FlowException
import net.corda.core.serialization.CordaSerializable

@CordaSerializable


//Simple class for storing the name of a stock/equity (treated as a fungible asset)
data class Security(val code: String,
                    val displayName: String,
                    val defaultFractionDigits: Int = 0) {

    companion object {
        private val registry = mapOf(
                Pair("RIO", Security("RIO", "Rio Tinto Ltd")),
                Pair("GBT", Security("GBT", "GBST Holdings Ltd")),
                Pair("CBA", Security("CBA", "Commonwealth Bank of Australia")),
                Pair("BP", Security("BP", "British Petroleum"))
        )

        fun getInstance(code: String) : Security?
            = registry[code]
    }
}


class SecurityException(message: String, cause: Throwable) : FlowException(message, cause)

