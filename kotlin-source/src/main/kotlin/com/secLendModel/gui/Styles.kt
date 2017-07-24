package com.secLendModel.gui

import tornadofx.*

class Styles : Stylesheet() {
    val zip by cssclass()

    init {
        s(form) {
            padding = box(25.px)
            prefWidth = 450.px

            s(zip) {
                maxWidth = 60.px
                minWidth = maxWidth

            }
        }
    }
}
