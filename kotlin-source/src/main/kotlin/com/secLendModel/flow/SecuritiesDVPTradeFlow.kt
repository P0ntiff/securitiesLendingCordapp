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
import net.corda.core.node.services.unconsumedStates
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

// This object is serialised to the network and is the first flow message the seller sends to the buyer.
@CordaSerializable
data class MarketOffer(
        val stockForSale : List<StateAndRef<SecurityClaim.State>>,
        val code : String,
        val offerPrice: Amount<Currency>,
        val quantityToSell: Long,
        val sellerKey: PublicKey
)

class AssetMismatchException(val expectedTypeName: String, val typeName: String) : FlowException() {
    override fun toString() = "The submitted asset didn't match the expected type: $expectedTypeName vs $typeName"
}
class UnacceptablePriceException(givenPrice: Amount<Currency>) : FlowException("Unacceptable price: $givenPrice")

//Seller is the initiating flow, buyer is the responder
//See TwoPartyTradeFlow.kt
object SecuritiesDVPTradeFlow {
    @StartableByRPC
    @InitiatingFlow
    class Seller(val otherParty: Party,
                 val code: String,
                 val offerPrice: Amount<Currency>,
                 val quantityToSell: Long,
                 override val progressTracker: ProgressTracker = Seller.tracker()) : FlowLogic<SignedTransaction>() {
        constructor(otherParty: Party, code: String, offerPrice: Amount<Currency>, quantityToSell: Long) :
                this(otherParty, code, offerPrice, quantityToSell, tracker())

        companion object {
            object PROPOSING : ProgressTracker.Step("Sending market sale offer")
            object RESOLVING : ProgressTracker.Step("Sent and now received back, resolving and signing transaction")
            object FINALISING : ProgressTracker.Step("Finalising transaction")
            fun tracker() = ProgressTracker(PROPOSING, RESOLVING, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val notary: NodeInfo = serviceHub.networkMapCache.notaryNodes[0]
            val ownerKey = serviceHub.legalIdentityKey

            /*********************************************************************************************************/
            progressTracker.currentStep = PROPOSING
            val assetsAvailableToSell = getSecurities(code)
            val marketOffer = MarketOffer(assetsAvailableToSell,
                    code,
                    offerPrice,
                    quantityToSell,
                    ownerKey)

            //Tentative since we need to check the other party has put in cash states before we sign it ourselves
            val tentativeSTX = sendAndReceive<SignedTransaction>(otherParty, marketOffer)

            /*********************************************************************************************************/
            progressTracker.currentStep = RESOLVING
            //Check that the partially signed transaction sent back is legitimate (i.e states aren't counterfeit)
            val unwrappedSTX = tentativeSTX.unwrap {
                val wtx: WireTransaction = it.verifySignatures(ownerKey, notary.notaryIdentity.owningKey)

                //Check transaction dependencies (resolution)
                subFlow(ResolveTransactionsFlow(wtx, otherParty))

                if (wtx.outputs.map { it.data }.sumCashBy(AnonymousParty(ownerKey)).withoutIssuer() != Amount(offerPrice.quantity * quantityToSell, CURRENCY))
                    throw FlowException("Transaction is not sending the right amount of cash (stockPrice * stockQuantity)")

                it
            }

            /*********************************************************************************************************/
            progressTracker.currentStep = FINALISING
            //Sign with our key
            val ourSignature = serviceHub.createSignature(unwrappedSTX, ownerKey)
            val unnotarisedSTX: SignedTransaction = unwrappedSTX + ourSignature
            val finishedSTX = subFlow(FinalityFlow(unnotarisedSTX)).single()
            return finishedSTX
        }

        @Suspendable
        private fun getSecurities(code : String): List<StateAndRef<SecurityClaim.State>> {
            val states = serviceHub.vaultService.unconsumedStates<SecurityClaim.State>()
            val desiredStates : ArrayList<StateAndRef<SecurityClaim.State>> = arrayListOf()
            for (state in states) {
                if (state.state.data.amount.token.product.code == code) {
                    desiredStates.add(state)
                }
            }
            return desiredStates
        }
    }

    @StartableByRPC
    @InitiatedBy(Seller::class)
    class Buyer(val otherParty: Party,
                override val progressTracker: ProgressTracker = Buyer.tracker()) : FlowLogic<SignedTransaction>() {

        companion object {
            object CONNECTED : ProgressTracker.Step("Connected to seller, receiving proposal")
            object INPUTTING : ProgressTracker.Step("Inputting states for sale offer")
            object SIGNING_TX : ProgressTracker.Step("Signing as buyer")
            object SENDING_BACK : ProgressTracker.Step("Sending back transaction to seller")
            fun tracker() = ProgressTracker(CONNECTED, INPUTTING, SIGNING_TX, SENDING_BACK)
        }

        @Suspendable
        override fun call() : SignedTransaction {
            val notary: NodeInfo = serviceHub.networkMapCache.notaryNodes[0]

            //Receive a pair of (TransactionBuilder, TradeInfo)
            //Unpack it
            val offer = receive<MarketOffer>(otherParty).unwrap { it }
            /*********************************************************************************************************/
            progressTracker.currentStep = CONNECTED
            val stockForSale = offer.stockForSale
            val code = offer.code
            val offerPrice = offer.offerPrice
            val quantityToSell = offer.quantityToSell
            val sellerKey = offer.sellerKey
            //Let's just accept whatever price it is for now
            val acceptablePrice = offerPrice
            //TODO: Check we're interested in buying the security with code 'code'
            //Assume that tx has been legitimately filled with equity states by the seller already
            //TODO: Enforce the above assumption using a similar 'outputs unwrap' method as in the Seller flow above

            //TODO: Resolve the stock being proposed to sell (check they've been fairly issued, going up the chain)
            //subFlow(ResolveTransactionsFlow())
            val amount = Amount(quantityToSell, Security(code, STOCKS[CODES.indexOf(code)]))

            /*********************************************************************************************************/
            progressTracker.currentStep = INPUTTING
            val builder = TransactionType.General.Builder(notary.notaryIdentity)
            //Add input and output states for movement of equity
            val (tx, keysForSigning) = try {
                OnLedgerAsset.generateSpend(
                        builder,
                        amount,
                        serviceHub.myInfo.legalIdentity,
                        stockForSale,
                        { state, amount, owner -> deriveState(state, amount, owner) },
                        { SecurityClaim().generateMoveCommand() }
                )
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }
            //Add own cash states to the transaction, to the value of stockPrice * quantity
//            println("Balance is ${serviceHub.vaultService.cashBalances.values.sumOrNull()}")
//            println("Stock price of ${stockPrice.quantity} multiplied by ${quantity.toLong()} is 'amount to reach' ")
            val (ptx, cashSigningPubKeys) =
                serviceHub.vaultService.generateSpend(
                        tx,
                        Amount(offerPrice.quantity * quantityToSell, CURRENCY),
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
            send(otherParty, stx)

            /*********************************************************************************************************/
            //Wait for ledger to arrive back in our transaction store
            return waitForLedgerCommit(stx.id)
            //logBalance()
        }

        private fun logBalance() {
            val balances = serviceHub.vaultService.cashBalances.entries.map { "${it.key.currencyCode} ${it.value}" }
            println("Remaining balance: ${balances.joinToString()}")
        }

        @Suspendable
        private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
                = txState.copy(data = txState.data.copy(amount = amount, owner = owner))
    }
}
