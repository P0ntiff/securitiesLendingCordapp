package com.secLendModel.gui.model

enum class CashTransaction(val partyNameA: String, val partyNameB: String?) {
    Issue("Issuer Bank", "Receiver Bank"),
    Pay("Payer", "Payee"),
    Exit("Issuer Bank", null);
}
