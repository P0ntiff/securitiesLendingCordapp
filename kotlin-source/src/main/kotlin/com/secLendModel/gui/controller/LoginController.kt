package com.secLendModel.gui.controller

import com.google.common.net.HostAndPort
import com.secLendModel.gui.views.LoginScreen
import com.secLendModel.gui.views.getModel
import javafx.beans.property.SimpleStringProperty
import net.corda.client.jfx.model.Models
import net.corda.client.jfx.model.NodeMonitorModel
import net.corda.core.messaging.CordaRPCOps
import tornadofx.*



class LoginController : Controller() {

    val monitor = NodeMonitorModel()

    fun login(host : String?, port : Int, username : String, password : String) {
        monitor.register(HostAndPort.fromParts(host, port), username, password)
        println("port is $port and password is $password")
    }

    fun getParties() : CordaRPCOps? {
        return monitor.proxyObservable.get()
    }

}