package com.secLendModel.gui.views.cordapps.securitiesLending

import com.google.common.base.Splitter
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.securitiesLending.LoanTerminationFlow
import com.secLendModel.flow.securitiesLending.LoanUpdateFlow
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.model.*
import com.secLendModel.gui.views.bigDecimalFormatter
import com.secLendModel.gui.views.byteFormatter
import com.secLendModel.gui.views.stringConverter
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.VPos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Window
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.ChosenList
import net.corda.client.jfx.utils.isNotNull
import net.corda.client.jfx.utils.map
import net.corda.client.jfx.utils.unique
import net.corda.core.contracts.*
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.getOrThrow
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.node.NodeInfo
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.then
import net.corda.flows.CashFlowCommand
import net.corda.flows.IssuerFlow
import net.corda.node.services.startFlowPermission
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*
import java.math.BigDecimal
import java.util.*
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle

/**
 * Created by raymondm on 14/08/2017.
 */

class UpdateLoanView : Fragment() {
    override val root by fxml<DialogPane>()
    // Components
    private val transactionTypeCB by fxid<ChoiceBox<LoanTransactions>>()
    //private val partyATextField by fxid<TextField>()
    //private val partyALabel by fxid<Label>()
    private val partyBChoiceBox by fxid<ChoiceBox<StateAndRef<SecurityLoan.State>>>()
    private val partyBLabel by fxid<Label>()
    //private val issuerLabel by fxid<Label>()
    //private val issuerTextField by fxid<TextField>()
    //private val issuerChoiceBox by fxid<ChoiceBox<Party>>()
    //private val issueRefLabel by fxid<Label>()
    //private val issueRefTextField by fxid<TextField>()
    //private val currencyLabel by fxid<Label>()
    //private val currencyChoiceBox by fxid<ChoiceBox<Currency>>()
    //private val availableAmount by fxid<Label>()
    //private val amountLabel by fxid<Label>()
    //private val amountTextField by fxid<TextField>()
    //private val amount = SimpleObjectProperty<BigDecimal>()
    private val issueRef = SimpleObjectProperty<Byte>()
    // Inject data
    private val parties by observableList(NetworkIdentityModel::parties)
    private val loanStates by observableList(SecuritiesLendingModel::loanStates)
    private val issuers by observableList(IssuerModel::issuers)
    private val rpcProxy by observableValue(NodeMonitorModel::proxyObservable)
    private val myIdentity by observableValue(NetworkIdentityModel::myIdentity)
    private val notaries by observableList(NetworkIdentityModel::notaries)
    private val cash by observableList(ContractStateModel::cash)
    private val executeButton = ButtonType("Execute", ButtonBar.ButtonData.APPLY)
    private val currencyTypes by observableList(IssuerModel::currencyTypes)
    private val supportedCurrencies by observableList(ReportingCurrencyModel::supportedCurrencies)
    private val loanTypes by observableList(IssuerModel::loanType)


    fun show(window: Window): Unit {
        newTransactionDialog(window).showAndWait().ifPresent { command: Unit ->
            val dialog = Alert(Alert.AlertType.INFORMATION).apply {
                headerText = null
                contentText = "Transaction Started."
                dialogPane.isDisable = false
                initOwner(window)
                show()
            }
            runAsync {

            }.ui {
                val type = when (command is Unit) {
                    true -> "Transaction Finished"
                    false -> "Transactino Started"
                }
                dialog.alertType = Alert.AlertType.INFORMATION
                dialog.dialogPane.isDisable = false
                dialog.dialogPane.content = gridpane {
                    padding = Insets(10.0, 40.0, 10.0, 20.0)
                    vgap = 10.0
                    hgap = 10.0
                    row { label(type) { font = Font.font(font.family, FontWeight.EXTRA_BOLD, font.size + 2) } }

                }
                dialog.dialogPane.scene.window.sizeToScene()
                }.setOnFailed {
                    val ex = it.source.exception
                    when (ex) {
                        is FlowException -> {
                            dialog.alertType = Alert.AlertType.ERROR
                            dialog.contentText = ex.message
                        }
                        else -> {
                            dialog.close()
                            ExceptionDialog(ex).apply { initOwner(window) }.showAndWait()
                        }
                    }
                }
            }
        }


    private fun newTransactionDialog(window: Window) = Dialog<Unit>().apply {
        dialogPane = root
        initOwner(window)
        setResultConverter {
            val defaultRef = OpaqueBytes.of(1)
            val issueRef = if (issueRef.value != null) OpaqueBytes.of(issueRef.value) else defaultRef
            when (it) {
                executeButton -> when (transactionTypeCB.value) {
                    LoanTransactions.Terminate -> {
                            rpcProxy.value?.startFlow(LoanTerminationFlow::Terminator, partyBChoiceBox.value.state.data.linearId)

                    }
                    LoanTransactions.UpdateMargin ->{
                        rpcProxy.value?.startFlow(LoanUpdateFlow::Updator, partyBChoiceBox.value.state.data.linearId)
                    }
                    LoanTransactions.UpdateAll -> {
                        partyBChoiceBox.items.forEach {
                            rpcProxy.value?.startFlow(LoanUpdateFlow::Updator, it.state.data.linearId) as FlowHandle<Unit>
                        }
                    }

               }
                else -> null
            }
        }
        println("Loan Updated with new margin")
    }

    init {
        // Disable everything when not connected to node.
        val notariesNotNullBinding = Bindings.createBooleanBinding({ notaries.isNotEmpty() }, arrayOf(notaries))
        val enableProperty = myIdentity.isNotNull().and(rpcProxy.isNotNull()).and(notariesNotNullBinding)
        root.disableProperty().bind(enableProperty.not())
        //refresh loan states

        // Transaction Types Choice Box
        transactionTypeCB.items = loanTypes

        // Party A textfield always display my identity name, not editable.
        //partyATextField.isEditable = false
        //partyATextField.textProperty().bind(myIdentity.map { it?.legalIdentity?.let { PartyNameFormatter.short.format(it.name) } ?: "" })
        //partyALabel.textProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameA?.let { "$it : " } })
        //partyATextField.visibleProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameA }.isNotNull())

        // Loan Selection
        partyBChoiceBox.apply {
            partyBLabel.textProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameB?.let { "$it : " } })
            items = loanStates
            converter = stringConverter { "Instrument: " + it.state.data.code.toString() +
                 "\n Shares: "+ it.state.data.quantity +
                        "\n Lender: " + PartyNameFormatter.short.format(it.state.data.lender.name) +
                        "\n Borrower: " + PartyNameFormatter.short.format(it.state.data.borrower.name) +
                        "\n Margin: " + it.state.data.terms.margin +
                        "\n Current SP: " + it.state.data.currentStockPrice.quantity}
        }
            // Validate inputs.
            val formValidCondition = arrayOf(
                    //myIdentity.isNotNull(),
                    transactionTypeCB.valueProperty().isNotNull,
                    partyBChoiceBox.visibleProperty().not().or(partyBChoiceBox.valueProperty().isNotNull)

            ).reduce(BooleanBinding::and)

            // Enable execute button when form is valid.
            root.buttonTypes.add(executeButton)
            //Use correct for validation
            root.lookupButton(executeButton).disableProperty().bind(formValidCondition.not())

        }
    }

