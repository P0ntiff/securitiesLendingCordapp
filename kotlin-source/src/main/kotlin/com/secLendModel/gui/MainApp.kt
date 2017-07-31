package com.secLendModel.gui

import com.secLendModel.gui.views.LoginScreen
import com.secLendModel.gui.views.MainView
import com.secLendModel.gui.views.PortfolioScreen
import javafx.application.Application
import javafx.beans.property.SimpleObjectProperty
import javafx.stage.Stage
import net.corda.client.jfx.model.Models
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*

class MainApp : App(MainView::class) {
    private val loginScreen by inject<LoginScreen>()

    override fun start(stage : Stage) {
        super.start(stage)
        stage.minHeight = 600.0
        stage.minWidth = 800.0

        val hostname = parameters.named["host"]
        val port = asInteger(parameters.named["port"])
        val username = parameters.named["username"]
        val password = parameters.named["password"]
        var isLoggedIn = false

        if ((hostname != null) && (port != null) && (username != null) && (password != null)) {
            try {
                loginScreen.login(hostname, port, username, password)
                isLoggedIn = true
            } catch (e: Exception) {
                ExceptionDialog(e).apply { initOwner(stage.scene.window) }.showAndWait()
            }
        }

        if (!isLoggedIn) {
            stage.hide()
            //loginScreen.login()
            stage.show()
        }
    }

    private fun asInteger(s: String?): Int? {
        try {
            return s?.toInt()
        } catch (e: NumberFormatException) {
            return null
        }
    }

    init {
        importStylesheet(Styles::class)
        // Register views.
        Models.get<CordaViewModel>(MainApp::class).apply {
            registerView<PortfolioScreen>()

            selectedView.set(find<PortfolioScreen>())
        }


    }
}

fun main(args: Array<String>) {
    Application.launch(MainApp::class.java, *args)
}


class CordaViewModel {
    val selectedView = SimpleObjectProperty<View>()
    val registeredViews = mutableListOf<View>().observable()

    inline fun <reified T> registerView() where  T : View {
        // Note: this is weirdly very important, as it forces the initialisation of Views. Therefore this is the entry
        // point to the top level observable/stream wiring! Any events sent before this init may be lost!
        registeredViews.add(find<T>().apply { root })
    }
}