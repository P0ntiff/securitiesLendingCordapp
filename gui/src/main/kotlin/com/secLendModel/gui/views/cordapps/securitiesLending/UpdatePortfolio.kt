package com.secLendModel.gui.views.cordapps.securitiesLending

import com.google.common.base.Splitter
import com.secLendModel.CURRENCY
import com.secLendModel.flow.securities.BuyFlow
import com.secLendModel.flow.securities.TradeFlow
import com.secLendModel.flow.securitiesLending.LoanNetFlow
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.VPos
import javafx.scene.control.*
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Window
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.isNotNull
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowException
import net.corda.core.messaging.startFlow
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.model.*
import com.secLendModel.gui.views.stringConverter
import net.corda.core.internal.x500Name
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*

class UpdatePortfolio : Fragment() {
    override val root by fxml<DialogPane>()
    // Components
    private val transactionTypeCB by fxid<ChoiceBox<EquitiesTransaction>>()
    private val otherPartyCB by fxid<ChoiceBox<net.corda.core.node.NodeInfo>>()
    private val otherPartyLabel by fxid<Label>()
    private val securityTypeCB by fxid<ChoiceBox<String>>()
    private val securityTypeLabel by fxid<Label>()
    private val priceTextField by fxid<TextField>()
    private val priceLabel by fxid<Label>()
    private val quantityTextField by fxid<TextField>()
    private val quantityLabel by fxid<Label>()
    // Inject data
    private val parties by observableList(NetworkIdentityModel::parties)
    private val issuers by observableList(IssuerModel::issuers)
    private val rpcProxy by observableValue(NodeMonitorModel::proxyObservable)
    private val myIdentity by observableValue(NetworkIdentityModel::myIdentity)
    private val notaries by observableList(NetworkIdentityModel::notaries)
    private val cash by observableList(ContractStateModel::cash)
    private val executeButton = ButtonType("Execute", ButtonBar.ButtonData.APPLY)
    private val currencyTypes by observableList(IssuerModel::currencyTypes)
    private val supportedCurrencies by observableList(ReportingCurrencyModel::supportedCurrencies)
    private val transactionTypes by observableList(IssuerModel::transactionTypes)


    fun show(window: Window): Unit {
        newTransactionDialog(window).showAndWait().ifPresent { command ->
            val dialog = Alert(Alert.AlertType.INFORMATION).apply {
                headerText = null
                contentText = "Transaction Started."
                dialogPane.isDisable = true
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
            when (it) {
                executeButton -> when (transactionTypeCB.value) {
                    EquitiesTransaction.Buy -> {
                        //TODO: Create a buy flow for equities, currently only sell exists.
                        rpcProxy.value?.startFlow(BuyFlow::Buyer, securityTypeCB.value, quantityTextField.text.toInt(),
                                Amount(priceTextField.text.toLong() * quantityTextField.text.toLong(), CURRENCY), otherPartyCB.value.legalIdentities.first())
                    }
                    EquitiesTransaction.Sell -> {
                        rpcProxy.value?.startFlow(TradeFlow::Seller, securityTypeCB.value, quantityTextField.text.toInt(),
                                Amount(priceTextField.text.toLong() * quantityTextField.text.toLong(), CURRENCY), otherPartyCB.value.legalIdentities.first())
                    }
                    else -> null
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

        // Transaction Types Choice Box
        transactionTypeCB.items = EquitiesTransaction.values().asList().observable()

        // Other Party
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
        // Security Type
        securityTypeLabel.text = "Security"
        securityTypeCB.apply {
            items = listOf<String>(
                    "GBT",
                    "CBA",
                    "RIO",
                    "NAB"
            ).observable()
        }
        // Price
        priceLabel.text = "Price per security"
        quantityLabel.text = "Quantity of securities"
        // Validate inputs.
        val formValidCondition = arrayOf(
                myIdentity.isNotNull(),
                transactionTypeCB.valueProperty().isNotNull,
                otherPartyCB.visibleProperty().not().or(otherPartyCB.valueProperty().isNotNull),
                securityTypeCB.visibleProperty().not().or(securityTypeCB.valueProperty().isNotNull),
                priceTextField.textProperty().isNotEmpty
        ).reduce(BooleanBinding::and)

        // Enable execute button when form is valid.
        root.buttonTypes.add(executeButton)
        root.lookupButton(executeButton).disableProperty().bind(formValidCondition.not())
    }
}
