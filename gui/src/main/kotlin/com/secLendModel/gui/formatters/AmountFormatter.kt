package com.secLendModel.gui.formatters

import net.corda.core.contracts.Amount
import java.text.DecimalFormat
import java.util.*

/**
 * A note on formatting: Currently we don't have any fancy locale/use-case-specific formatting of amounts. This is a
 * non-trivial problem that requires substantial work.
 * Libraries to evaluate: IBM ICU currency library, github.com/mfornos/humanize, JSR 354 ref. implementation
 */

object AmountFormatter {
    //TODO this is hardcoded for $ dollars (can be changed to pounds symbol, euro symbol, etc.)
    val boring = object : Formatter<Amount<Currency>> {
        override fun format(value: Amount<Currency>): String {
            var df : DecimalFormat = DecimalFormat("#, ###.00")
            val builder : String = df.format(value.toDecimal()) + value.token.toString()
            return builder
        }
    }
    fun formatStock(value: Int) = String.format("%,d", value.toLong())


}
