package com.secLendModel.gui.model

import tornadofx.getProperty
import tornadofx.property

class Loan(borrower : String, lender : String, price : Long, code : String, quantity : Int, loanDetails : LoanDetails) {
    var borrower by property(borrower)
    fun borrowerProperty() = getProperty(Loan::borrower)

    var lender by property(lender)
    fun lenderProperty() = getProperty(Loan::lender)

    var price by property(price)
    fun priceProperty() = getProperty(Loan::price)

    var code by property(code)
    fun codeProperty() = getProperty(Loan::code)

    var quantity by property(quantity)
    fun quantityProperty() = getProperty(Loan::quantity)

    var loanDetails by property(loanDetails)
    fun loanDetailsProperty() = getProperty(Loan::loanDetails)
}

class LoanDetails(margin : Double, rebate : Double, lengthOfLoan : Int) {
    var margin by property(margin)
    fun marginProperty() = getProperty(LoanDetails::margin)

    var rebate by property(rebate)
    fun rebateProperty() = getProperty(LoanDetails::rebate)

    var lengthOfLoan by property(lengthOfLoan)
    fun lengthOfLoanProperty() = getProperty(LoanDetails::lengthOfLoan)

}