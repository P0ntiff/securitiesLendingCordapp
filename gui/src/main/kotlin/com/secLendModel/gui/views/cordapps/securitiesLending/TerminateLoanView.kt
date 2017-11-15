package com.secLendModel.gui.views.cordapps.securitiesLending

import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.securitiesLending.LoanTerminationFlow
import com.secLendModel.flow.securitiesLending.LoanPartialTerminationFlowTerminationFlow
import com.secLendModel.flow.securitiesLending.LoanUpdateFlow
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.model.IssuerModel
import com.secLendModel.gui.model.LoanTransactions
import com.secLendModel.gui.model.ReportingCurrencyModel
import com.secLendModel.gui.model.SecuritiesLendingModel
import com.secLendModel.gui.views.stringConverter
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Window
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.isNotNull
import net.corda.client.jfx.utils.map
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowException
import net.corda.core.internal.x500Name
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.OpaqueBytes
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*

/**
 * Created by raymondm on 21/08/2017.
 */

class TerminateLoanView : Fragment() {
    override val root by fxml<DialogPane>()
    // Components
    private val transactionTypeCB by fxid<ChoiceBox<LoanTransactions>>()
    //private val partyATextField by fxid<TextField>()
    //private val partyALabel by fxid<Label>()
    private val secLoanCB by fxid<ChoiceBox<StateAndRef<SecurityLoan.State>>>()
    private val secLoanLabel by fxid<Label>()
    //private val issuerLabel by fxid<Label>()
    //private val issuerTextField by fxid<TextField>()
    //private val issuerChoiceBox by fxid<ChoiceBox<Party>>()
    //private val issueRefLabel by fxid<Label>()
    //private val issueRefTextField by fxid<TextField>()
    //private val currencyLabel by fxid<Label>()
    //private val currencyChoiceBox by fxid<ChoiceBox<Currency>>()
    //private val availableAmount by fxid<Label>()
    private val amountLabel by fxid<Label>()
    private val amountTextField by fxid<TextField>()
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

    /** This section handles the popup window when the transaction is started */
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
                //When the user has selected terminate and a loan, we can begin the termination flow
                executeButton -> when (transactionTypeCB.value) {
                    LoanTransactions.Terminate -> {
                        rpcProxy.value?.startFlow(LoanTerminationFlow::Terminator, secLoanCB.value.state.data.linearId)
                    }
                    LoanTransactions.PartialTerminate -> {
                        rpcProxy.value?.startFlow(LoanPartialTerminationFlowTerminationFlow::PartTerminator, secLoanCB.value.state.data.linearId, amountTextField.characters.toString().toInt())
                    }
                }
                else -> null
            }
        }
    }

    init {
        // Disable everything when not connected to node.
        val notariesNotNullBinding = Bindings.createBooleanBinding({ notaries.isNotEmpty() }, arrayOf(notaries))
        val enableProperty = myIdentity.isNotNull().and(rpcProxy.isNotNull()).and(notariesNotNullBinding)
        root.disableProperty().bind(enableProperty.not())
        //refresh loan states

        // Transaction Types Choice Box
        transactionTypeCB.items = listOf(LoanTransactions.Terminate, LoanTransactions.PartialTerminate).observable()


        // Loan Selection
        secLoanCB.apply {
            secLoanLabel.textProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameB?.let { "$it : " } })
            items = loanStates
            converter = stringConverter { "Instrument: " + it.state.data.code.toString() +
                    "\n Shares: "+ it.state.data.quantity +
                    "\n Lender: " + PartyNameFormatter.short.format(it.state.data.lender.name.x500Name) +
                    "\n Borrower: " + PartyNameFormatter.short.format(it.state.data.borrower.name.x500Name) +
                    "\n Margin: " + it.state.data.terms.margin +
                    "\n Current SP: " + it.state.data.currentStockPrice.quantity}
        }

        //Amount input if required
        amountLabel.text = "Amount to Terminate"
        // Validate inputs.
        val formValidCondition = arrayOf(
                //myIdentity.isNotNull(),
                transactionTypeCB.valueProperty().isNotNull,
                secLoanCB.visibleProperty().not().or(secLoanCB.valueProperty().isNotNull)

        ).reduce(BooleanBinding::and)

        // Enable execute button when form is valid.
        root.buttonTypes.add(executeButton)
        root.lookupButton(executeButton).disableProperty().bind(formValidCondition.not())


    }
}
