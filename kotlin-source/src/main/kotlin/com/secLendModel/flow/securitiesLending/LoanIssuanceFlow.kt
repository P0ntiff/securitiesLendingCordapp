package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.contracts.asset.sumCashBy
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.CollectSignaturesFlow
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow
import net.corda.flows.SignTransactionFlow

/**
 *  Flow to create a TXN between lender and borrower with the following structure:
 *
 *  Commands: Issue
 *  Inputs:  -Cash Collateral + Margin (from borrower),
 *           -Securities (from lender)
 *  Outputs: -SecurityLoan (owned by lender)
 *           -Cash Collateral + Margin (owned by lender)
 *           -Securities (owned by borrower)
 */
object LoanIssuanceFlow {

    @StartableByRPC
    @InitiatingFlow
    //TODO: Don't hardcode borrower as the initiator (perhaps make a superflow to initiate borrower or lender, depending on what role the initiator and acceptor play)
    open class Initiator(val loanTerms : LoanTerms) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call(): UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val counterParty = loanIssuanceChecks().getCounterParty(loanTerms, serviceHub.myInfo.legalIdentity)
            //STEP 1: Negotiation
            //Negotiate the borrower's desired terms of the loan with the lender
            val agreedTerms = subFlow(LoanAgreementFlow.Borrower(loanTerms))
            send(counterParty, agreedTerms)


            //Now that the two parties have come to agreement on terms to use, begin to build the transaction
            val builder = TransactionType.General.Builder(notary = notary)

            //STEP 2: Put in cash collateral
            // TODO: don't hard code cash as the collateral
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            //If we are lender
            if (loanIssuanceChecks().isLender(loanTerms,serviceHub.myInfo.legalIdentity)){
                val (ptx, keysForSigning) = try {
                    subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, agreedTerms.borrower))
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
                println("Securities Added Initiator ${agreedTerms.code} with Quantity ${agreedTerms.quantity} at price ${agreedTerms.stockPrice} ")
                send(counterParty, ptx)
            }
            else{ //We are the borrower
                val (ptx, cashSigningPubKeys) = serviceHub.vaultService.generateSpend(builder,
                        Amount(((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), CURRENCY),
                        AnonymousParty(agreedTerms.lender.owningKey)
                )
                println("Cash Added on Initiator Side ${((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin))} ")
                send(counterParty, ptx)
            }

            //STEP 6: Check other party has put in the securities and securityLoan as outputs and signed the txn
            val signTransactionFlow = object : SignTransactionFlow(counterParty) {
                //TODO: Edit this checkTransaction to be more generalized
                override fun checkTransaction(stx: SignedTransaction)  = requireThat {
                  //  "Lender must send us the right amount of securities" using
                    //        (stx.tx.outputs.map { it.data }.filterIsInstance<SecurityClaim.State>().filter
                      //      { it.owner.owningKey == myKey && it.code == agreedTerms.code }
                        //            .sumBy { it.quantity } == (agreedTerms.quantity))
                    //val secLoan = stx.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                    //"Lender must have issued us a loan with the agreed terms" using
                            //((secLoan.quantity == agreedTerms.quantity) &&
                            //(secLoan.code == agreedTerms.code) &&
                            //(secLoan.stockPrice == agreedTerms.stockPrice) &&
                            //(secLoan.lender == agreedTerms.lender) &&
                            //(secLoan.borrower == serviceHub.myInfo.legalIdentity) &&
                            //(secLoan.terms.margin == agreedTerms.margin) &&
                            //(secLoan.terms.rebate == agreedTerms.rebate))
                }
            }

            //STEP 7: Sign on our end and return
            return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Initiator::class)
    open class Acceptor(val counterParty : Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call() : SignedTransaction {
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 3: Connect to borrower / initiator
            //TODO: Make this stronger, check these agreed terms really are the agreed terms (i.e from LoanAgreementFlow)
            val agreedTerms = receive<LoanTerms>(counterParty).unwrap { it }
            val builder = receive<TransactionBuilder>(counterParty).unwrap {
                //TODO: Add checks for both security and cash state depending on which party we are
                //Check cash states have been put in to the agreed amount
                //if (it.outputStates().map { it.data }.sumCashBy(AnonymousParty(myKey)).withoutIssuer().quantity.toInt()
                  //      != ((agreedTerms.stockPrice.quantity.toInt() * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toInt()) {
                    //throw FlowException("Borrower did not put in the agreed amount of cash for collateral.")
                //}
                //T
                it
            }

            //STEP 4: Put in security states as inputs and outputs
            //Check which party in the deal we are
            val tx: TransactionBuilder
            if (loanIssuanceChecks().isLender(agreedTerms,serviceHub.myInfo.legalIdentity)){
                //We are lender -> should have recieved cash, adding in stock
                tx = try {
                    subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, agreedTerms.borrower)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }

            }
            else{ //We are the borrower -> shuold have received stock, adding in cash
                 tx = serviceHub.vaultService.generateSpend(builder,
                        Amount(((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), CURRENCY),
                        AnonymousParty(agreedTerms.lender.owningKey)).first
            }
            println("Cash Added on Acceptor ${((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin))} ")
            //STEP 5: Generate securityLoan state as output state and send back to borrower
            val ptx = SecurityLoan().generateIssue(tx, agreedTerms, notary)
            val stx = serviceHub.signInitialTransaction(ptx)
            val fullySignedTX = subFlow(CollectSignaturesFlow(stx))
            return subFlow(FinalityFlow(fullySignedTX)).single()

        }
    }

    class loanIssuanceChecks(){
        //Function for checking if you are the lender in a deal.
        fun isLender(loanTerms: LoanTerms, me: Party): Boolean{
            val lender = loanTerms.lender
            return (me == lender)
        }

        fun getCounterParty(loanTerms: LoanTerms, me: Party): Party{
            if (me == loanTerms.lender){
                return loanTerms.borrower
            }
            else{
                return loanTerms.lender
            }
        }


    }
}

