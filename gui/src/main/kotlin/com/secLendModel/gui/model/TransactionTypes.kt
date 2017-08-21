package com.secLendModel.gui.model

enum class CashTransaction(val partyNameA: String, val partyNameB: String?) {
    Issue("Issuer Bank", "Receiver Bank"),
    Pay("Payer", "Payee"),
    Exit("Issuer Bank", null);
}

enum class EquitiesTransaction(val partyNameA: String, val partyNameB: String?) {
    Buy("Buyer", "Seller"),
    Sell("Seller", "Buyer")
}
enum class LoanTransactions(val partyNameA: String, val partyNameB: String?) {
    Terminate("Me", "Loan"),
    Update("Me", "Loan"),
    UpdateAll("Me", "Loan"),
    Issue("Me", "Loan")
}
