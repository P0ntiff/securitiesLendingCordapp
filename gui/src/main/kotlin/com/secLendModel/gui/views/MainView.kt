package com.secLendModel.gui.views

import com.secLendModel.CURRENCY
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import javafx.stage.WindowEvent
import net.corda.client.jfx.utils.ChosenList
import net.corda.client.jfx.utils.map
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.model.CordaViewModel
import tornadofx.*
import com.secLendModel.Simulation
import com.secLendModel.flow.oracle.PriceRequestFlow
import com.secLendModel.flow.oracle.PriceType
import com.secLendModel.flow.securitiesLending.LoanAgreementFlow
import com.secLendModel.flow.securitiesLending.LoanChecks
import com.secLendModel.flow.securitiesLending.LoanIssuanceFlow
import com.secLendModel.flow.securitiesLending.LoanTerms
import javafx.concurrent.Task
import javafx.scene.control.*
import net.corda.client.jfx.model.*
import net.corda.core.contracts.Amount
import net.corda.core.crypto.commonName
import net.corda.core.getOrThrow
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.transactions.TransactionBuilder
import org.omg.PortableServer.THREAD_POLICY_ID
import java.util.concurrent.Delayed

/**
 * The root view embeds the [Shell] and provides support for the status bar, and modal dialogs.
 */
class MainView : View() {
    override val root by fxml<Parent>()

    // Inject components.
    private val userButton by fxid<MenuButton>()
    private val txnsButton by fxid<Button>()
    private val agreementButton by fxid<Button>()
    private val exit by fxid<MenuItem>()
    private val sidebar by fxid<VBox>()
    private val selectionBorderPane by fxid<BorderPane>()

    // Inject data.
    private val myIdentity by observableValue(NetworkIdentityModel::myIdentity)
    private val parties by observableList(NetworkIdentityModel::parties)
    private val selectedView by objectProperty(CordaViewModel::selectedView)
    private val registeredViews by observableList(CordaViewModel::registeredViews)
    private val rpcProxy by observableValue(NodeMonitorModel::proxyObservable)
    private val allNodes by observableList(NetworkIdentityModel::networkIdentities)


    private val menuItemCSS = "sidebar-menu-item"
    private val menuItemArrowCSS = "sidebar-menu-item-arrow"
    private val menuItemSelectedCSS = "$menuItemCSS-selected"

    init {
        // Header
        txnsButton.setOnMouseClicked {
            //Do txns in a new thread as to avoid UI freezing/locking
            val newThread = kotlin.concurrent.thread {
                runTxns()
            }
        }

        //TODO: Setup simulation for loan agreement flow
        agreementButton.setOnMouseClicked {
            //Do txns in a new thread as to avoid UI freezing/locking
            val newThread = kotlin.concurrent.thread {
                val myInfo = rpcProxy.value!!.nodeIdentity().legalIdentity
                val otherParty = parties.filter { it.advertisedServices.isEmpty() && it.legalIdentity != myInfo  && it.legalIdentity.name.commonName == "Alice Corp" }.single().legalIdentity
                val loanTerms = LoanTerms("CBA", 1200, Amount(2535, CURRENCY), otherParty, myInfo,
                        0.05, 0.05, 100, "Cash")
                val loanTermsReturned = rpcProxy.value?.startFlow(LoanAgreementFlow::Borrower, loanTerms)!!.returnValue.getOrThrow()

                runInFxApplicationThread {
                    val dialog = Alert(Alert.AlertType.INFORMATION).apply {
                        headerText = "Loan offer received"
                        contentText = LoanChecks.loanTermsToString(loanTermsReturned)
                        dialogPane.isDisable = false
                        //initOwner(window)
                        show()
                    }
                }
                val loanTerms2 = LoanTerms("CBA", 1200, Amount(2535, CURRENCY), myInfo, otherParty,
                        0.05, 0.05, 100, "Cash")
                val loanTermsReturned2 = rpcProxy.value?.startFlow(LoanAgreementFlow::Borrower, loanTerms2)!!.returnValue.getOrThrow()
            }
        }

        userButton.textProperty().bind(myIdentity.map { it?.legalIdentity?.let { PartyNameFormatter.short.format(it.name) } })
        exit.setOnAction {
            (root.scene.window as Stage).fireEvent(WindowEvent(root.scene.window, WindowEvent.WINDOW_CLOSE_REQUEST))
        }
        // Sidebar
        val menuItems = registeredViews.map {
            // This needed to be declared val or else it will get GCed and listener unregistered.
            val buttonStyle = ChosenList(selectedView.map { selected ->
                if (selected == it) listOf(menuItemCSS, menuItemSelectedCSS).observable() else listOf(menuItemCSS).observable()
            })
            stackpane {
                button(it.title) {
                    graphic = FontAwesomeIconView(it.icon).apply {
                        glyphSize = 30
                        textAlignment = TextAlignment.CENTER
                        fillProperty().bind(this@button.textFillProperty())
                    }
                    Bindings.bindContent(styleClass, buttonStyle)
                    setOnMouseClicked { e ->
                        if (e.button == MouseButton.PRIMARY) {
                            selectedView.value = it
                        }
                    }
                    // Transform to smaller icon layout when sidebar width is below 150.
                    val smallIconProperty = widthProperty().map { (it.toDouble() < 150) }
                    contentDisplayProperty().bind(smallIconProperty.map { if (it) ContentDisplay.TOP else ContentDisplay.LEFT })
                    textAlignmentProperty().bind(smallIconProperty.map { if (it) TextAlignment.CENTER else TextAlignment.LEFT })
                    alignmentProperty().bind(smallIconProperty.map { if (it) Pos.CENTER else Pos.CENTER_LEFT })
                    fontProperty().bind(smallIconProperty.map { if (it) Font.font(10.0) else Font.font(12.0) })
                    wrapTextProperty().bind(smallIconProperty)
                }
                // Small triangle indicator to make selected view more obvious.
                add(FontAwesomeIconView(FontAwesomeIcon.CARET_LEFT).apply {
                    StackPane.setAlignment(this, Pos.CENTER_RIGHT)
                    StackPane.setMargin(this, Insets(0.0, -5.0, 0.0, 0.0))
                    styleClass.add(menuItemArrowCSS)
                    visibleProperty().bind(selectedView.map { selected -> selected == it })
                })
            }
        }
        Bindings.bindContent(sidebar.children, menuItems)
        // Main view
        selectionBorderPane.centerProperty().bind(selectedView.map { it?.root })
    }

    fun runTxns() {
        //The following simulates specific trades and is accessed by clicking the txns button in the main view of the cordapp.
        val myInfo = rpcProxy.value!!.nodeIdentity().legalIdentity
        val otherParty = parties.filter { it.advertisedServices.isEmpty() && it.legalIdentity != myInfo  && it.legalIdentity.name.commonName == "Commbank" }.single().legalIdentity
        println(otherParty)
        //Loan borrow 1200 CBA w/cash collateral
        val loanTerms = LoanTerms("CBA", 1200, Amount(2535, CURRENCY), otherParty, myInfo,
                0.05, 0.05, 100, "Cash")
        val flow = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms)!!.returnValue.getOrThrow()

        val loanTerms2 = LoanTerms("CBA", 3000, Amount(2122, CURRENCY), myInfo, otherParty,
                0.07, 0.05, 100, "Cash")
        val flow2 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms2)!!.returnValue.getOrThrow()

        //Loan lend 900 RIO w/cash collateral
        val loanTerms3 = LoanTerms("RIO", 900, Amount(2356, CURRENCY), myInfo, otherParty,
                0.05, 0.05, 100, "Cash")
        val flow3 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms3)!!.returnValue.getOrThrow()

        //Loan borrow 850 NAB w/cash collateral
        val loanTerms4 = LoanTerms("NAB", 850, Amount(2410, CURRENCY), otherParty, myInfo,
                0.05, 0.05, 100, "Cash")
        val flow4 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms4)!!.returnValue.getOrThrow()

        //Loan borrow 500 CBA w/GBT collateral
        val loanTerms5 = LoanTerms("CBA", 500, Amount(2750, CURRENCY), otherParty, myInfo,
                0.05, 0.05, 100, "GBT")
        val flow5 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms5)!!.returnValue.getOrThrow()

        //Loan lend 350 CBA w/GBT collateral
        val loanTerms6 = LoanTerms("CBA", 350, Amount(2195, CURRENCY), myInfo, otherParty,
                0.05, 0.05, 100, "GBT")
        val flow6 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms6)!!.returnValue.getOrThrow()

        //Loan lend 250 RIO w/GBT collateral
        val loanTerms7 = LoanTerms("RIO", 250, Amount(2888, CURRENCY), myInfo, otherParty,
                0.05, 0.05, 100, "GBT")
        val flow7 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms7)!!.returnValue.getOrThrow()


        //Loan borrow 550 NAB w/GBT collateral
        val loanTerms8 = LoanTerms("NAB", 550, Amount(3253, CURRENCY), otherParty, myInfo,
                0.05, 0.05, 100, "GBT")
        val flow8 = rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms8)!!.returnValue.getOrThrow()


    }
}
