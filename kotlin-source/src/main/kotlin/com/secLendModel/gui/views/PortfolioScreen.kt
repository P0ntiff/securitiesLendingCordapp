package com.secLendModel.gui.views

import com.secLendModel.gui.controller.VaultController
import com.secLendModel.gui.model.Loan
import com.secLendModel.gui.model.LoanDetails
import com.secLendModel.gui.model.SecuritiesLendingModel
import javafx.beans.value.ObservableValue
import javafx.util.converter.DefaultStringConverter
import javafx.util.converter.IntegerStringConverter
import javafx.util.converter.LongStringConverter
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.map
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import tornadofx.*

fun AbstractParty.resolveIssuer(): ObservableValue<Party?> = Models.get(NetworkIdentityModel::class, javaClass.kotlin).lookup(owningKey).map { it?.legalIdentity }

data class Loan(
    val borrower : Party,
    val lender : Party
)

data class Claim(
    val code : String?,
    val quantity : Int?,
    val issuer : String?
)

class PortfolioScreen : View("Portfolio") {
    val cashStates by observableList(ContractStateModel::cashStates)
    val myIdentity by observableValue(NetworkIdentityModel::myIdentity)
    val claimStates by observableList(SecuritiesLendingModel::claimStates)
    val claims = claimStates.map {
        Claim(it.state.data.code,
                it.state.data.quantity,
                it.state.data.issuance.party.resolveIssuer().toString())
    }.observable()

    override val root = tableview(claims) {
        //DEBUG
        //cashStates.map { println(it) }
        println("My identity is $myIdentity")
        claims.forEach { println("Claim is : $it") }
        //END DEBUG

        setPrefSize(800.0, 600.0)
        isEditable = false

        column("ASX Code", Claim::code).useTextField(DefaultStringConverter())
        column("Quantity", Claim::quantity).useTextField(IntegerStringConverter())

        columnResizePolicy = SmartResize.POLICY
    }
}



//class PortfolioScreen : View("Portfolio") {
//    val loans = listOf(
//            Loan("Alice", "Bob", 78.56.toLong(), "CBA", 8800, LoanDetails(0.05, 0.01, 30)),
//            Loan("Bob", "Alice", 105.56.toLong(), "CBA", 5600, LoanDetails(0.05, 0.01, 30)),
//            Loan("Colin", "Bob", 150.67.toLong(), "RIO", 7000, LoanDetails(0.05, 0.01, 30))
//    ).observable()
//
//    override val root = tableview(loans) {
//        setPrefSize(800.0, 600.0)
//        //alignment = Pos.Center
//        isEditable = true
//        column("Borrower", Loan::borrower).useTextField(DefaultStringConverter())
//        column("Lender", Loan::lender).useTextField(DefaultStringConverter())
//        column("Stock", Loan::code).useTextField(DefaultStringConverter())
//        column("Price", Loan::price).useTextField(LongStringConverter())
//        column("Quantity", Loan::quantity).useTextField(IntegerStringConverter())
//
//        rowExpander(expandOnDoubleClick = true) {
//            paddingLeft = expanderColumn.width
//            tableview
//
//        }
//
//
//        columnResizePolicy = SmartResize.POLICY
//    }
//}
