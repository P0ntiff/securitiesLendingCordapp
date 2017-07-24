package com.secLendModel.gui.views

import com.google.common.net.HostAndPort
import com.secLendModel.gui.controller.LoginController
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Orientation
import javafx.scene.text.FontWeight
import javafx.scene.paint.Color
import net.corda.client.jfx.model.Models
import net.corda.client.jfx.model.NodeMonitorModel
import tornadofx.*

inline fun <reified M : Any> UIComponent.getModel(): M = Models.get(M::class, this.javaClass.kotlin)


class LoginScreen : View("Securities Lending Cordapp") {
    val model = ViewModel()
    val username = model.bind { SimpleStringProperty() }
    val password = model.bind { SimpleStringProperty() }
    val host = model.bind { SimpleStringProperty() }
    val port = model.bind { SimpleIntegerProperty() }
    val loginController : LoginController by inject()


    override val root = form {
        fieldset(labelPosition = Orientation.VERTICAL) {
            fieldset("Host") {
                textfield(host).required()
            }
            fieldset("Port") {
                textfield(port).required()
            }
            fieldset("Username") {
                textfield(username).required()
            }
            fieldset("Password") {
                passwordfield(password).required()
            }
            button("Connect") {
                enableWhen(model.valid)
                isDefaultButton = true
                useMaxWidth = true
                action {
                    loginController.login(host.value, port.value.toInt(), username.value, password.value)
                    find(LoginScreen::class).replaceWith(PortfolioScreen::class, sizeToScene = true, centerOnScreen = true)
                }
            }
        }
    }

    override fun onDock() {
        host.value = ""
        port.value = 0
        username.value = ""
        password.value = ""
        model.clearDecorators()
    }
}
