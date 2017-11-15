package com.secLendModel.flow.securities

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import net.corda.finance.contracts.asset.Cash
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*

/** A flow to model DVP Trade of Securities and Cash.
 *  Handles the input of security states in a subflow called SecuritiesPreparationFlow
 */
object TradeFlow {
    /** This object is serialised to the network and is the first flow message the seller sends to the buyer.
     *  The "initial market offer" --> responded to by a boolean
     * @param code = ASX/Exchange code of stock to be traded
     * @param quantity = Quantity of stock to be traded
     * @param stockPrice = Unit share price
     * @param sellerKey = Public ID of person proposing the transaction
     */
    @CordaSerializable
    data class MarketOffer(
            val code: String,
            val quantity: Int,
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
     * @param code = A String which refers to the name of the share to be traded (exchange code)
     * @param quantity = An Int referring to how many units of the above share are to be traded
     * @param stockPrice = An Amount<Currency>, containing the price and fiat currency of the listed equity, (per-share price)
     * @param buyer = The Party that is becoming the new owner of the states being sent, and who pays cash for the states
     */
    @InitiatingFlow
    @StartableByRPC
    class Seller(val code: String,
                 val quantity: Int,
                 val stockPrice: Amount<Currency>,
                 val buyer: Party,
                 override val progressTracker: ProgressTracker = Seller.tracker()) : FlowLogic<SignedTransaction>() {
        constructor(code: String, quantity: Int, stockPrice : Amount<Currency>, buyer: Party) :
                this(code, quantity, stockPrice, buyer, tracker())

        companion object {
            object PREPARING : ProgressTracker.Step("Gathering equity states")
            object PROPOSING : ProgressTracker.Step("Sending market sale offer")
            object RESOLVING : ProgressTracker.Step("Sent and now received back, resolving and signing transaction")
            object FINALISING : ProgressTracker.Step("Finalising transaction")
            fun tracker() = ProgressTracker(PREPARING, PROPOSING, RESOLVING, FINALISING)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val ownerKey = serviceHub.myInfo.legalIdentities.first().owningKey

            /**********************************************************************************************************/
            progressTracker.currentStep = PREPARING
            val marketOffer = MarketOffer(
                    code,
                    quantity,
                    stockPrice,
                    ownerKey
            )

            /**********************************************************************************************************/
            progressTracker.currentStep = PROPOSING
            //Check the other party is interested and ready to participate in this transaction
            val flowSession = initiateFlow(buyer)
            val acceptance = flowSession.sendAndReceive<Boolean>(marketOffer)
            //TODO: Have a backup plan if confirmation doesn't return True
            //Input states from vault into transaction and create outputs with recipient as the new owner of the states, along with change sent back to the old owner
            val builder : TransactionBuilder = TransactionBuilder(notary)
            val (tx, keysForSigning) = try {
                subFlow(SecuritiesPreparationFlow(builder, code, quantity, buyer))
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }
            val spendTX = sendAndReceive<SignedTransaction>(buyer, tx)

            /**********************************************************************************************************/
            progressTracker.currentStep = RESOLVING
            //Check txn dependencies and verify signature of counterparty
            val unwrappedSTX = spendTX.unwrap { it }
                //val wtx: WireTransaction = it.verifySignatures(ownerKey, notary.owningKey)
                //Check txn dependency chain ("resolution")
            subFlow(ResolveTransactionsFlow(unwrappedSTX, flowSession))
                //TODO: Check the amount of cash and equity input, and whether it's what we (the seller) asked for


            /**********************************************************************************************************/
            progressTracker.currentStep = FINALISING
            //Sign with our key
            val ourSignature = serviceHub.createSignature(unwrappedSTX, ownerKey)
            val unnotarisedSTX: SignedTransaction = unwrappedSTX + ourSignature
            val finishedSTX = subFlow(FinalityFlow(unnotarisedSTX, setOf(buyer)))
            return finishedSTX
        }
    }

    /** Invoked when listed as the buyer party in a Seller flow (see above). This party pays cash and receives equity in return.
     *
     * @param seller = party initiating the market offer and invitation to trade
     */
    @InitiatedBy(Seller::class)
    class Buyer(val sellerFlow: FlowSession) : FlowLogic<SignedTransaction>() {

        override val progressTracker: ProgressTracker = tracker()
        companion object {
            object CONNECTED : ProgressTracker.Step("Connected to seller, receiving proposal")
            object INPUTTING : ProgressTracker.Step("Inputting cash for sale offer")
            object SIGNING_TX : ProgressTracker.Step("Signing as buyer")
            object SENDING_BACK : ProgressTracker.Step("Sending back transaction to seller")
            fun tracker() = ProgressTracker(CONNECTED, INPUTTING, SIGNING_TX, SENDING_BACK)
        }

        @Suspendable
        override fun call() : SignedTransaction {
            /*********************************************************************************************************/
            progressTracker.currentStep = CONNECTED
            //Receive and unpack the market offer's details
            val tradeRequest = sellerFlow.receive<MarketOffer>().unwrap { it }
            val code = tradeRequest.code
            val quantity = tradeRequest.quantity
            val stockPrice : Amount<Currency> = tradeRequest.stockPrice
            val sellerKey = tradeRequest.sellerKey
            //Let's just accept whatever stock and stockPrice it is for now (i.e return "true" to seller, to represent acceptance)
            //TODO: Check we're interested in buying the security with code 'code', and check price against an acceptable price
            val builder : TransactionBuilder = sellerFlow.sendAndReceive<TransactionBuilder>(true).unwrap { it }
            //Assume that the tx has been legitimately filled with equity states by the seller prior to sending
            //TODO: Enforce the above assumption using a similar 'outputs unwrap' method as in the Seller flow above
/**
**/
            /*********************************************************************************************************/
            progressTracker.currentStep = INPUTTING
            val totalCash = Amount.fromDecimal(stockPrice.toDecimal() * BigDecimal(quantity), CURRENCY)
//            val (ptx, cashSigningPubKeys) = serviceHub.vaultService.
//                    generateSpend(builder,
//                    totalCash,
//                    AnonymousParty(sellerKey)
//                    )
            val (ptx, cashSigningPubKeys) = Cash.generateSpend(serviceHub,builder, totalCash, AnonymousParty(sellerKey))

            /*********************************************************************************************************/
            progressTracker.currentStep = SIGNING_TX
            val currentTime = serviceHub.clock.instant()
            ptx.setTimeWindow(currentTime, 30.seconds)
            val stx = serviceHub.signInitialTransaction(ptx, cashSigningPubKeys)

            /*********************************************************************************************************/
            progressTracker.currentStep = SENDING_BACK
            sellerFlow.send(stx)

            /*********************************************************************************************************/
            //Wait for ledger to arrive back in out transaction store
            return waitForLedgerCommit(stx.id)
        }
    }
}
