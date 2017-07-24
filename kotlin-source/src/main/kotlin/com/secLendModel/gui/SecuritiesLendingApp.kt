package com.secLendModel.gui

import com.secLendModel.gui.views.LoginScreen
import com.secLendModel.gui.views.PortfolioScreen
import javafx.application.Application
import tornadofx.*

class SecuritiesLendingApp : App() {
    private val loginView by inject<LoginScreen>()
    override val primaryView = LoginScreen::class

    init {
        importStylesheet(Styles::class)

    }
}


fun main(args: Array<String>) {
    Application.launch(SecuritiesLendingApp::class.java, *args)

}