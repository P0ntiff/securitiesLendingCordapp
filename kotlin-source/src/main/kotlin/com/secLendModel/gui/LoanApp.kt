package com.secLendModel.gui

import javafx.application.Application
import tornadofx.*

class LoanApp : App() {
    override val primaryView = NewTransactionScreen::class

    init {
        importStylesheet(Styles::class)
    }

}


fun main(args: Array<String>) {
    Application.launch(LoanApp::class.java, *args)

}