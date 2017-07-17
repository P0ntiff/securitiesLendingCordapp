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
    open class Borrower(val loanTerms : LoanTerms) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call(): UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 1: Negotiation
            //Negotiate the borrower's desired terms of the loan with the lender
            val agreedTerms = subFlow(LoanAgreementFlow.Borrower(loanTerms))
            send(loanTerms.lender, agreedTerms)
            //Now that the two parties have come to agreement on terms to use, begin to build the transaction
            val builder = TransactionType.General.Builder(notary = notary)

            //STEP 2: Put in cash collateral
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            // TODO: don't hard code cash as the collateral
            val (ptx, cashSigningPubKeys) = serviceHub.vaultService.generateSpend(builder,
                    Amount(((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), CURRENCY),
                    AnonymousParty(agreedTerms.lender.owningKey)
            )
            send(agreedTerms.lender, ptx)

            //STEP 6: Check other party has put in the securities and securityLoan as outputs and signed the txn
            val signTransactionFlow = object : SignTransactionFlow(agreedTerms.lender) {
                override fun checkTransaction(stx: SignedTransaction)  = requireThat {
                    "Lender must send us the right amount of securities" using
                            (stx.tx.outputs.map { it.data }.filterIsInstance<SecurityClaim.State>().filter
                            { it.owner.owningKey == myKey && it.code == agreedTerms.code }
                                    .sumBy { it.quantity } == (agreedTerms.quantity))
                    val secLoan = stx.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                    "Lender must have issued us a loan with the agreed terms" using
                            ((secLoan.quantity == agreedTerms.quantity) &&
                            (secLoan.code == agreedTerms.code) &&
                            (secLoan.stockPrice == agreedTerms.stockPrice) &&
                            (secLoan.lender == agreedTerms.lender) &&
                            (secLoan.borrower == serviceHub.myInfo.legalIdentity) &&
                            (secLoan.terms.margin == agreedTerms.margin) &&
                            (secLoan.terms.rebate == agreedTerms.rebate))
                }
            }

            //STEP 7: Sign on our end and return
            return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Borrower::class)
    open class Lender(val borrower : Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call() : SignedTransaction {
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 3: Connect to borrower / initiator
            //TODO: Make this stronger, check these agreed terms really are the agreed terms (i.e from LoanAgreementFlow)
            val agreedTerms = receive<LoanTerms>(borrower).unwrap { it }
            val builder = receive<TransactionBuilder>(borrower).unwrap {
                //Check cash states have been put in to the agreed amount
                if (it.outputStates().map { it.data }.sumCashBy(AnonymousParty(myKey)).withoutIssuer().quantity.toInt()
                        != ((agreedTerms.stockPrice.quantity.toInt() * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toInt()) {
                    throw FlowException("Borrower did not put in the agreed amount of cash for collateral.")
                }
                it
            }

            //STEP 4: Put in security states as inputs and outputs
            val (tx, keysForSigning) = try {
                subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, borrower))
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }

            //STEP 5: Generate securityLoan state as output state and send back to borrower
            val ptx = SecurityLoan().generateIssue(tx, agreedTerms, borrower, notary)
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


    }
}

