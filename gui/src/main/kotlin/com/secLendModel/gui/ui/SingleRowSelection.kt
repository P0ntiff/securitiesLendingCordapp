package com.secLendModel.gui.ui

sealed class SingleRowSelection<out A> {
    class None<out A> : SingleRowSelection<A>()
    class Selected<out A>(val node: A) : SingleRowSelection<A>()
}
