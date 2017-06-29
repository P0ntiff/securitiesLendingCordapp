package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.Security
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityException
import com.secLendModel.CODES
import com.secLendModel.STOCKS
import com.secLendModel.CURRENCY
import net.corda.contracts.asset.OnLedgerAsset
import net.corda.contracts.asset.sumCashBy
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.seconds
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow
import java.security.PublicKey
import java.util.*


object SecuritiesDVPTradeFlow {
    // This object is serialised to the network and is the first flow message the seller sends to the buyer.

    @CordaSerializable
    data class MarketOffer(
            val stock: Amount<Security>,
            val stockPrice: Amount<Currency>,
            val sellerKey: PublicKey
    )

    class AssetMismatchException(val expectedTypeName: String, val typeName: String) : FlowException() {
        override fun toString() = "The submitted asset didn't match the expected type: $expectedTypeName vs $typeName"
    }
    class UnacceptablePriceException(givenPrice: Amount<Currency>) : FlowException("Unacceptable price: $givenPrice")

    /** A flow for trading ownership of a securityClaim to another owner, who pays cash for the trade
     *  Similar to TwoPartyTradeFlow, seller is the initiating party where buyer responds in a flow below.
     *
     * @param stock = An Amount<Security> which is a data class containing the quantity of shares (amount.quantitiy) to be
     * moved, and the code/title of the share to be moved (amount.token.code)
     * @param stockPrice = An Amount<Currency>, containing the price and fiat currency of the listed equity, (per-share price)
     * @param buyer = the party that is becoming the new owner of the states being sent, and pays cash for the states
     */
    @StartableByRPC
    @InitiatingFlow
    class Seller(val buyer: Party,
                 val stock: Amount<Security>,
                 val stockPrice: Amount<Currency>,
                 override val progressTracker: ProgressTracker = Seller.tracker()) : FlowLogic<SignedTransaction>() {
        constructor(buyer: Party, stock: Amount<Security>, stockPrice: Amount<Currency>) :
                this(buyer, stock, stockPrice, tracker())

        companion object {
            object PREPARING : ProgressTracker.Step("Gathering equity states")
            object PROPOSING : ProgressTracker.Step("Sending market sale offer")
            object RESOLVING : ProgressTracker.Step("Sent and now received back, resolving and signing transaction")
            object FINALISING : ProgressTracker.Step("Finalising transaction")
            fun tracker() = ProgressTracker(PREPARING, PROPOSING, RESOLVING, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val notary: NodeInfo = serviceHub.networkMapCache.notaryNodes[0]
            val ownerKey = serviceHub.legalIdentityKey

            /**********************************************************************************************************/
            progressTracker.currentStep = PREPARING
            val amount = Amount(stock.quantity, Security(stock.token.code, STOCKS[CODES.indexOf(stock.token.code)]))
            val marketOffer = MarketOffer(stock,
                    stockPrice,
                    ownerKey)
            val builder = TransactionType.General.Builder(notary.notaryIdentity)
            //Add input and output states for movement of equity
            val desiredStates = getStates()
            val (tx, keysForSigning) = try {
                OnLedgerAsset.generateSpend(
                        builder,
                        amount,
                        buyer,
                        desiredStates,
                        { state, amount, owner -> deriveState(state, amount, owner) },
                        { SecurityClaim().generateMoveCommand() }
                )
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }

            /**********************************************************************************************************/
            progressTracker.currentStep = PROPOSING
            //Tentative since we need to check the other party has put in cash states before we sign it ourselves
            val tentativeSTX = sendAndReceive<SignedTransaction>(buyer, Pair(tx, marketOffer))

            /**********************************************************************************************************/
            progressTracker.currentStep = RESOLVING
            //Check that the partial transaction sent back is legitimate
            val unwrappedSTX = tentativeSTX.unwrap {
                val wtx: WireTransaction = it.verifySignatures(ownerKey, notary.notaryIdentity.owningKey)
                subFlow(ResolveTransactionsFlow(wtx, buyer))
                if (wtx.outputs.map { it.data }.sumCashBy(AnonymousParty(ownerKey)).withoutIssuer() !=
                        Amount(stockPrice.quantity * stock.quantity, GBP)) {
                    throw FlowException("Transaction is not sending the right amount of cash (stockPrice * stockQuantity)")
                }
                it
            }

            /**********************************************************************************************************/
            progressTracker.currentStep = FINALISING
            //Sign with our key
            val ourSignature = serviceHub.createSignature(unwrappedSTX, ownerKey)
            val unnotarisedSTX: SignedTransaction = unwrappedSTX + ourSignature
            val finishedSTX = subFlow(FinalityFlow(unnotarisedSTX)).single()
            return finishedSTX
        }

        @Suspendable
        private fun getStates() : List<StateAndRef<SecurityClaim.State>> {
            /**Old Method
             * val (vault, vaultUpdates) = serviceHub.vaultService.track()
             * val states = vault.states.filterStatesOfType<SecurityClaim.State>().toList()
             */
            /**Less Old Method
             *val states = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java), EnumSet.of(Vault.StateStatus.UNCONSUMED)).toMutableList()
             *val desiredStates : ArrayList<StateAndRef<SecurityClaim.State>> = arrayListOf()
             *for (state in states) {
             *    if (state.state.data.amount.token.product.code == amount.token.code) {
             *        desiredStates.add(state)
             *    }
             *}
             */
            //New Method
            val stockStates = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java),
                    EnumSet.of(Vault.StateStatus.UNCONSUMED))
            val desiredStates = stockStates.filter { (it.state.data.amount.token.product.code == stock.token.code) }
            return desiredStates
        }

        @Suspendable
        private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
                = txState.copy(data = txState.data.copy(amount = amount, owner = owner))
    }

    /** Invoked when listed as the buyer party in a Seller flow (see above). This party pays cash and receives equity in return.
     *
     * @param seller = party initiating the market offer and invitation to trade
     */
    @InitiatedBy(Seller::class)
    open class Buyer(val seller: Party,
                override val progressTracker: ProgressTracker = Buyer.tracker()) : FlowLogic<SignedTransaction>() {

        companion object {
            object CONNECTED : ProgressTracker.Step("Connected to seller, receiving proposal")
            object INPUTTING : ProgressTracker.Step("Inputting cash states for sale offer")
            object SIGNING_TX : ProgressTracker.Step("Signing as buyer")
            object SENDING_BACK : ProgressTracker.Step("Sending back transaction to seller")
            fun tracker() = ProgressTracker(CONNECTED, INPUTTING, SIGNING_TX, SENDING_BACK)
        }

        @Suspendable
        override fun call() : SignedTransaction {
            //val notary: NodeInfo = serviceHub.networkMapCache.notaryNodes[0]

            /*********************************************************************************************************/
            progressTracker.currentStep = CONNECTED
            //Receive a pair of (TransactionBuilder, TradeInfo) and unpack the market offer's details
            val (dtx, offer) = receive<Pair<TransactionBuilder, MarketOffer>>(seller).unwrap { it }
            val quantity : Long = offer.stock.quantity
            val stockPrice : Amount<Currency> = offer.stockPrice
            val sellerKey = offer.sellerKey
            //Let's just accept whatever stock and stockPrice it is for now
            //i.e acceptablePrice = stockPrice
            //val code : String = offer.stock.token.code
            //TODO: Check we're interested in buying the security with code 'code', and check price against an acceptable price
            //Assume that the tx has been legitimately filled with equity states by the seller prior to sending
            //TODO: Enforce the above assumption using a similar 'outputs unwrap' method as in the Seller flow above

            /*********************************************************************************************************/
            //Add our own cash states to the transaction, to the value of stockPrice * quantity
            progressTracker.currentStep = INPUTTING
//            println("Balance is ${serviceHub.vaultService.cashBalances.values.sumOrNull()}")
//            println("Stock price of ${stockPrice.quantity} multiplied by ${quantity.toLong()} is 'amount to reach' ")
            val (ptx, cashSigningPubKeys) = serviceHub.vaultService.
                    generateSpend(dtx,
                    Amount(stockPrice.quantity * quantity, CURRENCY),
                    AnonymousParty(sellerKey)
                    )

            /*********************************************************************************************************/
            progressTracker.currentStep = SIGNING_TX
            val currentTime = serviceHub.clock.instant()
            ptx.addTimeWindow(currentTime, 30.seconds)
            val stx = serviceHub.signInitialTransaction(ptx, cashSigningPubKeys)

            /*********************************************************************************************************/
            progressTracker.currentStep = SENDING_BACK
            //val vtx = stx.toSignedTransaction(checkSufficientSignatures = false)
            send(seller, stx)

            /*********************************************************************************************************/
            //Wait for ledger to arrive back in out transaction store
            return waitForLedgerCommit(stx.id)
//            serviceHub.recordTransactions(listOf(vtx))
//            logBalance()
        }

        private fun logBalance() {
            val balances = serviceHub.vaultService.cashBalances.entries.map { "${it.key.currencyCode} ${it.value}" }
            println("Remaining balance: ${balances.joinToString()}")
        }
    }
}
