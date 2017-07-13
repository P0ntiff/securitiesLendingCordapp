package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.contracts.asset.sumCashBy
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.withoutIssuer
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow

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
    open class Borrower(val loanTerms : LoanTerms) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 1: Negotiation
            //Negotiate the borrower's desired terms of the loan with the lender
            val agreedTerms = subFlow(LoanAgreementFlow.Borrower(loanTerms))
            send(loanTerms.lender, agreedTerms)
            //Now that the two parties have come to agreement on terms to use, begin to build the transaction
            val builder = TransactionType.General.Builder(notary = notary)

            //STEP 2: Put in cash collateral
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            //Put in the cash states to represent collateral
            // TODO: add margin in later, and don't hard code cash as the collateral
            val (ptx, cashSigningPubKeys) = serviceHub.vaultService.generateSpend(builder,
                    Amount(agreedTerms.stockPrice.quantity * agreedTerms.quantity, CURRENCY),
                    AnonymousParty(agreedTerms.lender.owningKey)
            )

            //STEP 6: Check other party has put in the securities and securityLoan as outputs and signed the txn
            val halfSignedTransaction = sendAndReceive<SignedTransaction>(agreedTerms.lender, ptx).unwrap {
                val wtx: WireTransaction = it.verifySignatures(myKey, notary.owningKey)
                //Check the other party has put me (the borrower) as the new owner of the correct quantity of the agreed security
                if (wtx.outputs.map { it.data }.filterIsInstance<SecurityClaim.State>().filter { it.owner.owningKey == myKey && it.code == agreedTerms.code }
                        .sumBy { it.quantity } != (agreedTerms.quantity)) {
                    throw FlowException("Lender is not lending us the right amount of securities")
                }
                //Check the other party has issued a loan as output
                val secLoan = wtx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                if ((secLoan.quantity != agreedTerms.quantity) ||
                        (secLoan.code != agreedTerms.code) ||
                        (secLoan.stockPrice != agreedTerms.stockPrice) ||
                        (secLoan.lender != agreedTerms.lender) ||
                        (secLoan.borrower != serviceHub.myInfo.legalIdentity) ||
                        (secLoan.terms.margin != agreedTerms.margin) ||
                        (secLoan.terms.rebate != agreedTerms.rebate)) {
                    throw FlowException("Lender has not correctly issued a loan with the terms that were agreed upon")
                }
                subFlow(ResolveTransactionsFlow(wtx, agreedTerms.lender))

                it
            }

            //STEP 7: Sign on our end
            val ourSignature = serviceHub.createSignature(halfSignedTransaction, myKey)
            val unnotarisedSTX = halfSignedTransaction + ourSignature
            val finishedSTX = subFlow(FinalityFlow(unnotarisedSTX, setOf(agreedTerms.lender))).single()
            return finishedSTX

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
                //Check cash states have been put in
                //TODO: Factor margin in later
                if (it.outputStates().map { it.data }.sumCashBy(AnonymousParty(myKey)).withoutIssuer().quantity.toInt()
                        != (agreedTerms.stockPrice.quantity.toInt() * agreedTerms.quantity)) {
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
            send(borrower, stx)

            return stx
        }
    }
}

