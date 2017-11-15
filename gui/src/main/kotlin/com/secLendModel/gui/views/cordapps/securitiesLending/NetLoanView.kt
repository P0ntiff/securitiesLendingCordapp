package com.secLendModel.gui.views.cordapps.securitiesLending

import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.securitiesLending.LoanNetFlow
import com.secLendModel.flow.securitiesLending.LoanNetPrepFlow
import com.secLendModel.flow.securitiesLending.LoanTerminationFlow
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.model.IssuerModel
import com.secLendModel.gui.model.LoanTransactions
import com.secLendModel.gui.model.ReportingCurrencyModel
import com.secLendModel.gui.model.SecuritiesLendingModel
import com.secLendModel.gui.views.stringConverter
import com.sun.org.apache.xalan.internal.lib.NodeInfo
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Window
import net.corda.client.jackson.JacksonSupport
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.isNotNull
import net.corda.client.jfx.utils.map
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowException
import net.corda.core.internal.x500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.OpaqueBytes
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*

/**
 * Created by raymondm on 28/08/2017.
 */


class NetLoanView : Fragment() {
    override val root by fxml<DialogPane>()
    // Components
    private val transactionTypeCB by fxid<ChoiceBox<LoanTransactions>>()
    //private val partyATextField by fxid<TextField>()
    //private val partyALabel by fxid<Label>()
    private val otherPartyCB by fxid<ChoiceBox<net.corda.core.node.NodeInfo>>()
    private val otherPartyLabel by fxid<Label>()
    private val securityTypeCB by fxid<ChoiceBox<String>>()
    private val securityTypeLabel by fxid<Label>()
    private val collateralTypeCB by fxid<ChoiceBox<String>>()
    private val collateralTypeLabel by fxid<Label>()

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
                    LoanTransactions.Net -> {
                        val otherParty = otherPartyCB.value.legalIdentities.first()
                        rpcProxy.value?.startFlow(LoanNetFlow::NetInitiator, otherParty, securityTypeCB.value,
                                collateralTypeCB.value)

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
        transactionTypeCB.items = listOf(LoanTransactions.Net).observable()


        // Loan Selection
        otherPartyLabel.text = "Opposing Party"
        val newParties = arrayListOf<net.corda.core.node.NodeInfo>()
        parties.forEach {
            if (it.legalIdentities.first() != myIdentity.value) {
                newParties.add(it)
            }
        }
        otherPartyCB.apply {
            items = newParties.observable()
            converter = stringConverter { it?.legalIdentities!!.first()?.let {
                PartyNameFormatter.short.format(it.name.x500Name) } ?: "" }
        }
        securityTypeLabel.text = "Security"
        securityTypeCB.apply {
            items = listOf<String>(
                    "GBT",
                    "CBA",
                    "RIO",
                    "NAB"
            ).observable()
        }

        collateralTypeLabel.text = "Collateral Type"
        collateralTypeCB.apply { items = listOf<String>(
                "GBT",
                "CBA",
                "RIO",
                "NAB",
                "Cash"
        ).observable() }
        // Validate inputs.
        val formValidCondition = arrayOf(
                //myIdentity.isNotNull(),
                transactionTypeCB.valueProperty().isNotNull,
                otherPartyCB.visibleProperty().not().or(otherPartyCB.valueProperty().isNotNull),
                securityTypeCB.visibleProperty().not().or(otherPartyCB.valueProperty().isNotNull)

        ).reduce(BooleanBinding::and)

        // Enable execute button when form is valid.
        root.buttonTypes.add(executeButton)
        root.lookupButton(executeButton).disableProperty().bind(formValidCondition.not())


    }
}
