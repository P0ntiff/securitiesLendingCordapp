package com.secLendModel.state

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class Security(val code: String,
                    val displayName: String,
                    val defaultFractionDigits: Int = 0) {

    companion object {
        private val registry = mapOf(
                Pair("RIO", Security("RIO", "Rio Tinto Ltd")),
                Pair("GBT", Security("GBT", "GBST Holdings Ltd")),
                Pair("CBA", Security("CBA", "Commonwealth Bank of Australia"))
        )

        fun getInstance(code: String) : Security?
            = registry[code]
    }
}

