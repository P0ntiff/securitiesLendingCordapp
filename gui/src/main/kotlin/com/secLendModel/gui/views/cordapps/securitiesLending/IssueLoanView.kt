package com.secLendModel.gui.views.cordapps.securitiesLending

import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.Oracle
import com.secLendModel.flow.oracle.PriceRequestFlow
import com.secLendModel.flow.oracle.PriceType
import com.secLendModel.flow.securitiesLending.*
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
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Window
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.isNotNull
import net.corda.client.jfx.utils.map
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowException
import net.corda.core.identity.Party
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.node.ServiceEntry
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.TransactionBuilder
import org.controlsfx.dialog.ExceptionDialog
import tornadofx.*
import java.time.LocalDateTime

/**
 * Created by raymondm on 21/08/2017.
 *
 * This is the view that is loaded when a user selects the issue loan button within the loan portfolio view in the
 * cordapp GUI. This view allows users to fill out fields and issue a new loan on the ledger.
 */

class IssueLoanView : Fragment() {
    override val root by fxml<DialogPane>()
    // Components
    private val transactionTypeCB by fxid<ChoiceBox<LoanTransactions>>()
    //private val partyATextField by fxid<TextField>()
    //private val partyALabel by fxid<Label>()
    private val partyBChoiceBox by fxid<ChoiceBox<StateAndRef<SecurityLoan.State>>>()
    private val partyBLabel by fxid<Label>()
    //Fields for loanTerms
    private val codeLabel by fxid<Label>()
    private val codeCB by fxid<ChoiceBox<String>>()
    private val opposingPartyLabel by fxid<Label>()
    private val opposingPartyCB by fxid<ChoiceBox<net.corda.core.node.NodeInfo>>()
    private val collateralTypeLabel by fxid<Label>()
    private val collateralTypeCB by fxid<ChoiceBox<String>>()
    private val typeLabel by fxid<Label>()
    private val typeCB by fxid<ChoiceBox<String>>()
    private val amountLabel by fxid<Label>()
    private val amountTextField by fxid<TextField>()
    private val marginLabel by fxid<Label>()
    private val marginTextField by fxid<TextField>()
    private val rebateLabel by fxid<Label>()
    private val rebateTextField by fxid<TextField>()
    private val lengthLabel by fxid<Label>()
    private val lengthTextField by fxid<TextField>()
    private val issueRef = SimpleObjectProperty<Byte>()
    // Inject data
    private val parties by observableList(NetworkIdentityModel::parties)
    private val allNodes by observableList(NetworkIdentityModel::networkIdentities)
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

    //Shares to be on issue by exchange
    val CODES = listOf(
            "GBT",
            "CBA",
            "RIO",
            "NAB"
    )

    /** Shows the user when a txn is started and then finished (i.e is commited tot he ledger */
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

    /** Function for executing the loan issuance when the user clicks the execute button */
    private fun newTransactionDialog(window: Window) = Dialog<Unit>().apply {
        dialogPane = root
        initOwner(window)
        setResultConverter {
            val defaultRef = OpaqueBytes.of(1)
            val issueRef = if (issueRef.value != null) OpaqueBytes.of(issueRef.value) else defaultRef
            when (it) {
                //When user has selected issue, issue new loan states
                executeButton -> when (transactionTypeCB.value) {
                    LoanTransactions.Issue -> {
                            //Get values, create the loan
                            val lender: Party; val borrower: Party;
                            if (typeCB.value == "Lend") {
                                //I am lending to someone else
                                 lender = rpcProxy.value!!.nodeIdentity().legalIdentity
                                 borrower = opposingPartyCB.value.legalIdentity
                            } else {
                                 lender = opposingPartyCB.value.legalIdentity
                                 borrower = rpcProxy.value!!.nodeIdentity().legalIdentity
                            }
                            //Get stock price from the oracle
                            val oracle = allNodes.filtered { it.advertisedServices.any { it.info.type.equals(PriceType.type) } }
                            val priceTx = TransactionBuilder()
                            val priceQuery = rpcProxy.value?.startFlow(PriceRequestFlow::PriceQueryFlow, codeCB.value )
                            val loanTerms = LoanTerms(codeCB.value, amountTextField.text.toInt(), priceQuery!!.returnValue.get(), lender, borrower,
                                    marginTextField.text.toDouble(), rebateTextField.text.toDouble(), lengthTextField.text.toInt(), collateralTypeCB.value, LocalDateTime.now())
                            rpcProxy.value?.startFlow(LoanIssuanceFlow::Initiator, loanTerms) as FlowHandle<Unit>

                    }
                }
                else -> null
            }
        }
    }

    /** Initial setup of the view, providing  prompts for each text field, loading choiceboxes with the correct options */
    init {
        // Disable everything when not connected to node.
        val notariesNotNullBinding = Bindings.createBooleanBinding({ notaries.isNotEmpty() }, arrayOf(notaries))
        val enableProperty = myIdentity.isNotNull().and(rpcProxy.isNotNull()).and(notariesNotNullBinding)
        root.disableProperty().bind(enableProperty.not())
        //refresh loan states

        // Transaction Types Choice Box
        transactionTypeCB.items = listOf(LoanTransactions.Issue).observable()
        codeLabel.text = "Code: "
        //Get securities from the exchange
        //allNodes.filtered { it.advertisedServices.any { it.info.type.equals(ServiceInfo(PriceType.type)) } }
        codeCB.items = CODES.observable()
        typeLabel.text = "Type: "
        typeCB.items = listOf("Lend", "Borrow").observable()
        amountLabel.text = "Quantity of Securities: "
        marginLabel.text = "Margin: "
        rebateLabel.text = "Rebate: "
        lengthLabel.text = "Length of Loan: "
        opposingPartyLabel.text = "Opposing Party: "
        collateralTypeLabel.text = "Collateral Type"
        //Setup avaialble opposing parties
        val newParties = arrayListOf<net.corda.core.node.NodeInfo>()
        parties.forEach {
            if (it != myIdentity.value) {
                newParties.add(it)
            }
        }
        opposingPartyCB.apply {
            //parties.remove(myIdentity.value)
            items = newParties.observable()
            //items.remove(myIdentity.value)
            converter = stringConverter { it?.legalIdentity?.let { PartyNameFormatter.short.format(it.name) } ?: "" }
        }
        collateralTypeCB.items = CODES.plus("Cash").observable()

        // Validate inputs.
        val formValidCondition = arrayOf(
                //myIdentity.isNotNull(),
                codeCB.valueProperty().isNotNull
        ).reduce(BooleanBinding::and)


        // Enable execute button when form is valid.
        root.buttonTypes.add(executeButton)
        //Use correct for validation
        //Add validation based on transactionType
        root.lookupButton(executeButton).disableProperty().bind(formValidCondition.not())

    }
}