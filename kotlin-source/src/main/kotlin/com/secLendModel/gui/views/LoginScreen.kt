package com.secLendModel.gui.views

import com.google.common.net.HostAndPort
import com.secLendModel.gui.controller.VaultController
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Orientation
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
                    login(host.value, port.value.toInt(), username.value, password.value)
                }
            }
        }
    }

    fun login() {



    }

    fun login(host : String?, port : Int, username : String, password : String) {
        getModel<NodeMonitorModel>().register(HostAndPort.fromParts(host, port), username, password)
    }


        override fun onDock() {
        host.value = ""
        port.value = 0
        username.value = ""
        password.value = ""
        model.clearDecorators()
    }
}
