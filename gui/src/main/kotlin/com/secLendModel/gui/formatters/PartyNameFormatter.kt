package com.secLendModel.gui.formatters

import net.corda.core.internal.commonName
import org.bouncycastle.asn1.x500.X500Name

object PartyNameFormatter {
    val short = object : Formatter<X500Name> {
        override fun format(value: X500Name): String = value.commonName.orEmpty()
    }

    val full = object : Formatter<X500Name> {
        override fun format(value: X500Name): String = value.toString()
    }
}
