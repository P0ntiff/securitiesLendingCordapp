package com.secLendModel.gui

import tornadofx.getProperty
import tornadofx.property

class Loan {
    var borrower by property<String>()
    fun borrowerProperty() = getProperty(Loan::borrower)

    var lender by property<String>()
    fun lenderProperty() = getProperty(Loan::lender)

    var price by property<String>()
    fun priceProperty() = getProperty(Loan::price)

    var code by property<String>()
    fun codeProperty() = getProperty(Loan::code)

    var quantity by property<String>()
    fun quantityProperty() = getProperty(Loan::quantity)

}