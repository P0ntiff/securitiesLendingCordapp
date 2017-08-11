package com.secLendModel.gui.views.cordapps.securitiesLending

import com.secLendModel.CODES
import com.secLendModel.STOCKS
import com.secLendModel.contract.SecurityClaim
import com.sun.javafx.collections.ObservableListWrapper
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.chart.NumberAxis
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.*
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.withoutIssuer
import net.corda.core.crypto.commonName
import net.corda.core.identity.AbstractParty
import com.secLendModel.gui.formatters.AmountFormatter
import com.secLendModel.gui.formatters.PartyNameFormatter
import com.secLendModel.gui.identicon.identicon
import com.secLendModel.gui.identicon.identiconToolTip
import com.secLendModel.gui.model.*
import com.secLendModel.gui.ui.*
import com.secLendModel.gui.views.*
import com.secLendModel.gui.views.resolveIssuer
import org.fxmisc.easybind.EasyBind
import tornadofx.*
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

class PortfolioViewer : CordaView("Equities Portfolio") {
    // Inject UI elements.
    override val root: BorderPane by fxml()
    override val icon: FontAwesomeIcon = FontAwesomeIcon.ADDRESS_CARD
    // Left pane
    private val leftPane: VBox by fxid()
    private val splitPane: SplitPane by fxid()
    private val totalMatchingLabel: Label by fxid()
    private val claimViewerTable: TreeTableView<ViewerNode> by fxid()
    private val claimViewerTableExchangeHolding: TreeTableColumn<ViewerNode, String> by fxid()
    private val claimViewerTableQuantity: TreeTableColumn<ViewerNode, Int> by fxid()
    // Right pane
    private val rightPane: VBox by fxid()
    private val totalPositionsLabel: Label by fxid()
    private val claimStatesList: ListView<StateRow> by fxid()
    private val toggleButton by fxid<Button>()
    // Inject observables
    private val claimStates by observableList(SecuritiesLendingModel::claimStates)

    private val selectedNode = claimViewerTable.singleRowSelection().map {
        when (it) {
            is SingleRowSelection.Selected -> it.node
            else -> null
        }
    }

    private val view = ChosenList(selectedNode.map {
        when (it) {
            null -> FXCollections.observableArrayList(leftPane)
            else -> FXCollections.observableArrayList(leftPane, rightPane)
        }
    })

    /**
     * This holds the data for each row in the TreeTable.
     */
    sealed class ViewerNode(val states: ObservableList<StateAndRef<SecurityClaim.State>>) {
        class ExchangeNode(val exchange: AbstractParty,
                         states: ObservableList<StateAndRef<SecurityClaim.State>>) : ViewerNode(states)

        class QuantityNode(val quantity: ObservableValue<Int>,
                           states: ObservableList<StateAndRef<SecurityClaim.State>>) : ViewerNode(states)
    }

    /**
     * Holds data for a single state, to be displayed in the list in the side pane.
     */
    data class StateRow(val originated: LocalDateTime, val stateAndRef: StateAndRef<SecurityClaim.State>)

    /**
     * A small class describing the graphics of a single state.
     */
    inner class StateRowGraphic(val stateRow: StateRow) : UIComponent() {
        override val root: Parent by fxml("ClaimStateViewer.fxml")

        val stateIdValueLabel: Label by fxid()
        val instrumentValueLabel: Label by fxid()
        val exchangeValueLabel: Label by fxid()
        val originatedValueLabel: Label by fxid()
        val quantityValueLabel: Label by fxid()

        init {
            val quantity = stateRow.stateAndRef.state.data.quantity
            val resolvedIssuer: AbstractParty = stateRow.stateAndRef.state.data.issuance.party

            stateIdValueLabel.apply {
                text = stateRow.stateAndRef.ref.toString().substring(0, 16) + "...[${stateRow.stateAndRef.ref.index}]"
                graphic = identicon(stateRow.stateAndRef.ref.txhash, 30.0)
                tooltip = identiconToolTip(stateRow.stateAndRef.ref.txhash)
            }
            instrumentValueLabel.text = STOCKS[CODES.indexOf(stateRow.stateAndRef.state.data.code)]
            exchangeValueLabel.textProperty().bind(SimpleStringProperty(resolvedIssuer.nameOrNull()?.let {
                PartyNameFormatter.short.format(it)
            } ?: "Anonymous"))
            exchangeValueLabel.apply { tooltip(resolvedIssuer.nameOrNull()?.let { PartyNameFormatter.full.format(it) } ?: "Anonymous") }
            originatedValueLabel.text = stateRow.originated.toString()
            quantityValueLabel.text = AmountFormatter.formatStock(quantity)
        }
    }

    // Wire up UI
    init {
        Bindings.bindContent(splitPane.items, view)

        val searchField = SearchField(claimStates,
                "Code" to { state, text -> state.state.data.code.contains(text, true) }
        )
        root.top = hbox(5.0) {
            button("New Equities Trade", FontAwesomeIconView(FontAwesomeIcon.PLUS)) {
                setOnMouseClicked {
                    if (it.button == MouseButton.PRIMARY) {
                        find<UpdatePortfolio>().show(this@PortfolioViewer.root.scene.window)
                    }
                }
            }
            HBox.setHgrow(searchField.root, Priority.ALWAYS)
            add(searchField.root)
        }

        /**
         * This is where we aggregate the list of states into the TreeTable structure.
         */
        val claimViewerExchangeNodes: ObservableList<TreeItem<out ViewerNode.ExchangeNode>> =
                /**
                 * First we group the states based on the exchange. [memberStates] is all states holding stock issued by [exchange]
                 */
                AggregatedList(searchField.filteredData, { it.state.data.issuance.party }) { exchange, memberStates ->
                    /**
                     * Next we create subgroups based on holding. [memberStates] here is all states holding stock [stock] issued by [exchange] above.
                     * Note that these states will not be displayed in the TreeTable, but rather in the side pane if the user clicks on the row.
                     */
                    val stockNodes = AggregatedList(memberStates, { it.state.data.code }) { stock, memberStates ->
                        /**
                         * We sum the states in the subgroup, to be displayed in the "Quantity" column
                         */
                        val amounts = memberStates.map { it.state.data.quantity }
                        val sumAmount = amounts.foldObservable(0, Int::plus)

                        /**
                         * Finally assemble the actual TreeTable Currency node.
                         */
                        TreeItem(ViewerNode.QuantityNode(sumAmount, memberStates))
                    }

                    /**
                     * Assemble the Exchange node.
                     */
                    val treeItem = TreeItem(ViewerNode.ExchangeNode(exchange, memberStates))

                    /**
                     * Bind the children in the TreeTable structure.
                     *
                     * TODO Perhaps we shouldn't do this here, but rather have a generic way of binding nodes to the treetable once.
                     */
                    treeItem.isExpanded = true
                    val children: List<TreeItem<out ViewerNode.ExchangeNode>> = treeItem.children
                    Bindings.bindContent(children, stockNodes)
                    treeItem
                }

        claimViewerTable.apply {
            root = TreeItem()
            val children: List<TreeItem<out ViewerNode>> = root.children
            Bindings.bindContent(children, claimViewerExchangeNodes)
            root.isExpanded = true
            isShowRoot = false
            // TODO use smart resize
            setColumnPrefWidthPolicy { tableWidthWithoutPaddingAndBorder, _ ->
                Math.floor(tableWidthWithoutPaddingAndBorder.toDouble() / columns.size).toInt()
            }
        }
        val quantityCellFactory = AmountFormatter.intFormatter.toTreeTableCellFactory<ViewerNode, Int>()

        claimViewerTableExchangeHolding.setCellValueFactory {
            val node = it.value.value
            when (node) {
            // TODO: Anonymous should probably be italicised or similar
                is ViewerNode.ExchangeNode -> SimpleStringProperty(node.exchange .let { PartyNameFormatter.short.format(it.nameOrNull()!!) } ?: "Anonymous")
                is ViewerNode.QuantityNode -> node.states.map { it.state.data.code }.first()
            }
        }
        claimViewerTableQuantity.apply {
            setCellValueFactory {
                val node = it.value.value
                when (node) {
                    is ViewerNode.ExchangeNode -> null.lift()
                    is ViewerNode.QuantityNode -> node.quantity.map { it }
                }
            }
            cellFactory = quantityCellFactory
            /**
             * We must set this, otherwise on sort an exception will be thrown, as it will try to compare Amounts of differing currency
             */
            isSortable = false
        }

        // Right Pane.
        totalPositionsLabel.textProperty().bind(claimStatesList.itemsProperty().map {
            val plural = if (it.size == 1) "" else "s"
            "Total ${it.size} position$plural"
        })

        claimStatesList.apply {
            // TODO update this once we have actual timestamps.
            itemsProperty().bind(selectedNode.map { it?.states?.map { StateRow(LocalDateTime.now(), it) } ?: ObservableListWrapper(emptyList()) })
            setCustomCellFactory { StateRowGraphic(it).root }
        }

        // TODO Think about i18n!
        totalMatchingLabel.textProperty().bind(Bindings.size(claimViewerExchangeNodes).map {
            val plural = if (it == 1) "" else "s"
            "Total $it matching issuer$plural"
        })

        toggleButton.setOnAction {
            claimViewerTable.selectionModel.clearSelection()
        }
    }

}
