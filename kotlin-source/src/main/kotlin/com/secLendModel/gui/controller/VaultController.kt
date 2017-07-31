package com.secLendModel.gui.controller

import com.google.common.net.HostAndPort
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.gui.views.LoginScreen
import com.secLendModel.gui.views.PortfolioScreen
import net.corda.client.jfx.model.NodeMonitorModel
import net.corda.core.identity.Party
import tornadofx.*

class VaultController : Controller() {

//    val monitor = NodeMonitorModel()
//
//    fun login(host : String?, port : Int, username : String, password : String) {
//        monitor.register(HostAndPort.fromParts(host, port), username, password)
//        find(LoginScreen::class).replaceWith(PortfolioScreen::class, sizeToScene = true, centerOnScreen = true)
//
//    }
//
//    fun getMe() : Party {
//        return monitor.proxyObservable.value!!.nodeIdentity().legalIdentity
//    }
}