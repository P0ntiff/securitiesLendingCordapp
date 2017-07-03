package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.Security
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityException
import com.secLendModel.CURRENCY
import net.corda.contracts.asset.OnLedgerAsset
import net.corda.core.contracts.*
import net.corda.core.crypto.isFulfilledBy
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.seconds
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.flows.*
import java.security.PublicKey
import java.util.*


object TradeFlow {
    // This object is serialised to the network and is the first flow message the seller sends to the buyer.
    @CordaSerializable
    data class MarketOffer(
        val statesForSale: List<StateAndRef<SecurityClaim.State>>,
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
    @InitiatingFlow
    @StartableByRPC
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
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val ownerKey = serviceHub.legalIdentityKey

            /**********************************************************************************************************/
            progressTracker.currentStep = PREPARING
            val statesForSale = getStates(stock.token.code)
            val marketOffer = MarketOffer(
                    statesForSale,
                    stock,
                    stockPrice,
                    ownerKey
            )

            /**********************************************************************************************************/
            progressTracker.currentStep = PROPOSING
            //Tentative since we need to check the other party has put in cash states before we sign it ourselves
            val tentativeSTX = sendAndReceive<SignedTransaction>(buyer, marketOffer)

            /**********************************************************************************************************/
            progressTracker.currentStep = RESOLVING
            //Check txn dependencies and verify signature of counterparty
            val unwrappedSTX = tentativeSTX.unwrap {
                val wtx: WireTransaction = it.verifySignatures(ownerKey, notary.owningKey)
                //Check txn dependencies ("resolution")
                subFlow(ResolveTransactionsFlow(wtx, buyer))
                //Check the amount of cash and equity input, and whether it's what we (the seller) asked for
                /**var cashSum = 0
                var equitySum = 0
                for (output in wtx.outputs) {
                    if (output.data.contract.legalContractReference == SecureHash.sha256("https://www.big-book-of-banking-law.gov/SecurityClaim.html")) {
                        equitySum += (output.data as SecurityClaim.State).amount.quantity.toInt()
                    } else if (output.data.contract.legalContractReference == SecureHash.sha256("https://www.big-book-of-banking-law.gov/cash-claims.html")) {
                        cashSum += (output.data as SecurityClaim.State).amount.quantity.toInt()
                    }
                }
                if (cashSum != stock.quantity * stockPrice.quantity) || (equitySum != stock.quantity)
                if (wtx.outputs.map { it.data }.sumCashBy(AnonymousParty(ownerKey)).withoutIssuer() !=
                        Amount(stockPrice.quantity * stock.quantity, CURRENCY)) {
                    throw FlowException("Transaction is not sending the right amount of cash (stockPrice * stockQuantity)")
                }*/
                it
            }

            /**********************************************************************************************************/
            progressTracker.currentStep = FINALISING
            //Sign with our key
            val ourSignature = serviceHub.createSignature(unwrappedSTX, ownerKey)
            val unnotarisedSTX: SignedTransaction = unwrappedSTX + ourSignature
//            println("Debug Flag G (Seller-side)")
            val finishedSTX : SignedTransaction
//            try {
//                finishedSTX = subFlow(FinalityFlow(unnotarisedSTX, setOf(buyer))).single()
//                return finishedSTX
//            } catch (e: ClassCastException) {
//                println("ERROR DETECTED!")
//            throw SecurityException("Unable to notarise spend", e)
//            }
            finishedSTX = finalityFlowWorkAround(unnotarisedSTX, buyer)
            try {
                //finishedSTX = subFlow(FinalityFlow(unnotarisedSTX, setOf(buyer))).single()
                serviceHub.recordTransactions(listOf(finishedSTX))
                subFlow(BroadcastTransactionFlow(finishedSTX, setOf(buyer)))
            } catch (e: ClassCastException){
                println("CLASS CAST ERROR EXCEPTION CAUGHT")
                return unnotarisedSTX
//            } catch (f: NotaryException) {
//                println("Notarising error caught")
            }
            return finishedSTX
            //return unnotarisedSTX
        }

        @Suspendable
        private fun getStates(code : String) : List<StateAndRef<SecurityClaim.State>> {
            /**Old Method
             * val (vault, vaultUpdates) = serviceHub.vaultService.track()
             * val states = vault.states.filterStatesOfType<SecurityClaim.State>().toList()
             *
             *Less Old Method
             * val states = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java), EnumSet.of(Vault.StateStatus.UNCONSUMED)).toMutableList()
             * val desiredStates : ArrayList<StateAndRef<SecurityClaim.State>> = arrayListOf()
             * for (state in states) {
             *    if (state.state.data.amount.token.product.code == amount.token.code) {
             *        desiredStates.add(state)
             *    }
             *}
             *Newer Method
             * val stockStates = serviceHub.vaultService.states(setOf(SecurityClaim.State::class.java),
             * EnumSet.of(Vault.StateStatus.UNCONSUMED))
             * val desiredStates = stockStates.filter { (it.state.data.amount.token.product.code == code) }
             */
            //Newer Method
            //val states : Vault.Page<SecurityClaim.State> = serviceHub.vaultQueryService.queryBy(SecurityClaim.State::class.java)
            //Newest Method, experimental
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
            val states = serviceHub.vaultQueryService.queryBy<SecurityClaim.State>(criteria)
            val desiredStates = states.states.filter {
                (it.state.data.owner == serviceHub.myInfo.legalIdentity)  &&
                        (it.state.data.amount.token.product.code == code) // &&
            }
                    //(states.statesMetadata[states.states.indexOf(it)].status == Vault.StateStatus.UNCONSUMED)
            return desiredStates
        }

        @Suspendable
        private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
                = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

        @Suspendable
        private fun finalityFlowWorkAround(tx : SignedTransaction, counterParty : Party) : SignedTransaction {
            fun hasNoNotarySignature(stx: SignedTransaction): Boolean {
                val notaryKey = stx.tx.notary?.owningKey
                val signers = stx.sigs.map { it.by }.toSet()
                return !(notaryKey?.isFulfilledBy(signers) ?: false)
            }
            fun needsNotarySignature(stx: SignedTransaction): Boolean {
                val wtx = stx.tx
                val needsNotarisation = wtx.inputs.isNotEmpty() || wtx.timeWindow != null
                return needsNotarisation && hasNoNotarySignature(tx)
            }
            val notarised = if (needsNotarySignature(tx) && (hasNoNotarySignature(tx))) {
                val notarySignatures = subFlow(NotaryFlow.Client(tx))
                tx + notarySignatures
            } else {
                tx
            }
            //If only getting notary signature, then:
            //return notarised
            //To record in other parties' vaults:
            //subFlow(BroadcastTransactionFlow(notarised, setOf(counterParty, serviceHub.myInfo.legalIdentity)))
            return notarised
        }
    }

    /** Invoked when listed as the buyer party in a Seller flow (see above). This party pays cash and receives equity in return.
     *
     * @param seller = party initiating the market offer and invitation to trade
     */
    @InitiatedBy(Seller::class)
    //TODO: Does open class make a difference?
    class Buyer(val seller: Party) : FlowLogic<SignedTransaction>() {

        override val progressTracker: ProgressTracker = Buyer.tracker()
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
            /*********************************************************************************************************/
            progressTracker.currentStep = CONNECTED
            //Receive a pair of (TransactionBuilder, TradeInfo) and unpack the market offer's details
            val tradeRequest = receive<MarketOffer>(seller).unwrap { it }
            val statesForSale : List<StateAndRef<SecurityClaim.State>>  = tradeRequest.statesForSale
            val stock : Amount<Security> = tradeRequest.stock
            val stockPrice : Amount<Currency> = tradeRequest.stockPrice
            val sellerKey = tradeRequest.sellerKey
            //Let's just accept whatever stock and stockPrice it is for now
            //i.e acceptablePrice = stockPrice
            //val code : String = offer.stock.token.code
            //TODO: Check we're interested in buying the security with code 'code', and check price against an acceptable price
            //Assume that the tx has been legitimately filled with equity states by the seller prior to sending
            //TODO: Enforce the above assumption using a similar 'outputs unwrap' method as in the Seller flow above

            //TODO: Resolve the stock being proposed to sell (do the "resolution" process, going up the dependency chain) --> Check if notary does this in FinalityFlow

            try {
                statesForSale.map { subFlow(ResolveTransactionsFlow(setOf(it.ref.txhash), seller)) }
                //serviceHub.recordTransactions(listOf(finishedSTX))
                //subFlow(BroadcastTransactionFlow(finishedSTX, setOf(buyer)))
            } catch (e: ClassCastException){
                println("CLASS CAST ERROR EXCEPTION CAUGHT")
            }
            /*********************************************************************************************************/
            progressTracker.currentStep = INPUTTING
            val builder = TransactionType.General.Builder(notary.notaryIdentity)
            //Add input and output states for movement of equity
            val (tx, stockSigningPubKeys) = try {
                OnLedgerAsset.generateSpend(
                        builder,
                        stock,
                        serviceHub.myInfo.legalIdentity,
                        statesForSale,
                        { state, amount, owner -> deriveState(state, amount, owner) },
                        { SecurityClaim().generateMoveCommand() }
                )
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }
            println("Transaction proposal: ${stock.quantity} shares in ${stock.token.code} sold for ${stockPrice} by seller ${seller.name} ")
            val (ptx, cashSigningPubKeys) = serviceHub.vaultService.
                    generateSpend(tx,
                    Amount(stockPrice.quantity * stock.quantity, CURRENCY),
                    AnonymousParty(sellerKey)
                    )
            println("\nDumping inputs\n----------------")
            ptx.inputStates().forEach { print(it.toString()); print("seller key is ${sellerKey.toString()}") }
            println("\nDumping outputs\n---------------")
            ptx.outputStates().forEach { println(it.toString()) }
            /*********************************************************************************************************/
            progressTracker.currentStep = SIGNING_TX
            val currentTime = serviceHub.clock.instant()
            ptx.addTimeWindow(currentTime, 30.seconds)
            val stx = serviceHub.signInitialTransaction(ptx, cashSigningPubKeys)

            /*********************************************************************************************************/
            progressTracker.currentStep = SENDING_BACK
            send(seller, stx)

            /*********************************************************************************************************/
            //Wait for ledger to arrive back in out transaction store
            //serviceHub.recordTransactions(listOf(stx))
//            return waitForLedgerCommit(stx.id)
            return stx
//            logBalance()
        }

        @Suspendable
        private fun deriveState(txState: TransactionState<SecurityClaim.State>, amount: Amount<Issued<Security>>, owner: AbstractParty)
                = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

    }
}
