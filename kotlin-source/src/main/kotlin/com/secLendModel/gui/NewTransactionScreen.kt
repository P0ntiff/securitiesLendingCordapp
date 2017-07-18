package com.secLendModel.gui

import org.controlsfx.control.Notifications
import tornadofx.*


class NewTransactionScreen : View() {
    override val root = Form()
    val loan = Loan()

    init {
        title = "New Loan"

        with(root) {
            fieldset("Information") {
                field("borrower") {
                    textfield().bind(loan.borrowerProperty())
                }

                field("lender") {
                    textfield().bind(loan.lenderProperty())
                }
            }

            fieldset("Stock") {
                field("price") {
                    textfield().bind(loan.priceProperty())
                }
                field("code / quantity") {
                    textfield().bind(loan.codeProperty())
                    textfield().bind(loan.quantityProperty())
                }
            }

            button("Save") {
                setOnAction {
                    Notifications.create()
                            .title("loan saved!")
                            .text("${loan.borrower} owes ${loan.lender}\n ${loan.quantity} ${loan.code} shares at ${loan.price} each")
                            .owner(this)
                            .showInformation()
                }

                // Save button is disabled until every field has a value
                disableProperty().bind(loan.borrowerProperty().isNull.or(loan.lenderProperty().isNull)
                        .or(loan.priceProperty().isNull).or(loan.codeProperty().isNull)
                        .or(loan.quantityProperty().isNull))
            }
        }


    }

}
