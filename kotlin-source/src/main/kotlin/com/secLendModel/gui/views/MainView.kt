package com.secLendModel.gui.views

import javafx.scene.control.TextField
import tornadofx.*

class MainView : View("Securities Lending Cordapp") {
    val controller : TestController by inject()
    var inputField : TextField by singleAssign()

    override val root = vbox {
        label("Input")
        inputField = textfield()
        button("Commit") {
            action {
                controller.test(inputField.text)
                inputField.clear()
            }
        }
    }
}

class TestController : Controller() {
    fun test(input : String) {
        println("Button pressed with input ${input}")
    }

}