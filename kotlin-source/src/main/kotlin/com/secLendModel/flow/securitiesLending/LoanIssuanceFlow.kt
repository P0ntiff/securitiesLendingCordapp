package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.CollateralPreparationFlow
import com.secLendModel.flow.SecuritiesPreparationFlow
import com.secLendModel.flow.securitiesLending.LoanChecks.getCounterParty
import com.secLendModel.flow.securitiesLending.LoanChecks.isLender
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.flows.*
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
    open class Initiator(val loanTerms : LoanTerms) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call(): UniqueIdentifier {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val counterParty = getCounterParty(loanTerms, serviceHub.myInfo.legalIdentity)

            //STEP 1: Negotiation: Negotiate the borrower's desired terms of the loan with the lender
            val agreedTerms = subFlow(LoanAgreementFlow.Borrower(loanTerms))
            send(counterParty, agreedTerms)
            //Now that the two parties have come to agreement on terms to use, begin to build the transaction
            val builder = TransactionType.General.Builder(notary = notary)

            //STEP 2: Put in either cash or securities, depending on which party we are in the deal.
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            val ptx: TransactionBuilder
            //If we are lender
            if (isLender(loanTerms,serviceHub.myInfo.legalIdentity)){
                 ptx = try {
                    subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, agreedTerms.borrower)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }
            else{ //If we are the borrower
                //Add in the required collateral
                ptx = subFlow(CollateralPreparationFlow(builder, loanTerms.collateralType,
                        ((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), agreedTerms.lender))
            }
            val stx = sendAndReceive<SignedTransaction>(counterParty, ptx).unwrap {
                val wtx: WireTransaction = it.verifySignatures(serviceHub.myInfo.legalIdentity.owningKey, notary.owningKey)
                //Check txn dependency chain ("resolution")
                subFlow(ResolveTransactionsFlow(wtx, counterParty))

                it
            }

            //Sign and finalize this transaction.
            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentity.owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty))).single()
            return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
            //return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Initiator::class)
    open class Acceptor(val counterParty : Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call() : SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            //STEP 3: Connect to borrower / initiator
            //TODO: Make this stronger, check these agreed terms really are the agreed terms (i.e from LoanAgreementFlow)
            val agreedTerms = receive<LoanTerms>(counterParty).unwrap { it }
            val builder = receive<TransactionBuilder>(counterParty).unwrap { it }

            //STEP 4: Put in security states/collateral as inputs and securityLoan as output
            //Check which party in the deal we are
            val tx : TransactionBuilder
            if (isLender(agreedTerms, serviceHub.myInfo.legalIdentity)) {
                //We are lender -> should have recieved cash, adding in stock
                tx = try {
                    subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, agreedTerms.borrower)).first
                } catch (e: InsufficientBalanceException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }
            else { //We are the borrower -> should have received stock, so adding in collateral (cash or securities)
                tx = subFlow(CollateralPreparationFlow(builder, agreedTerms.collateralType,
                            ((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), agreedTerms.lender))

            }

            //Figure out the amount of collateral used
            var collateralQuantity = 0;
            if (agreedTerms.collateralType == "Cash") {
                collateralQuantity =((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toInt()
            } else {
                tx.outputStates().map { it.data }.filterIsInstance<SecurityClaim.State>().forEach {
                    if ((it.owner == agreedTerms.lender) && (it.code == agreedTerms.collateralType)) {
                        collateralQuantity += it.quantity;
                    }
                }
            }

            //STEP 5: Generate securityLoan state as output state and send back to borrower
            val ptx = SecurityLoan().generateIssue(tx, agreedTerms, notary)
            println("Collateral amount of ${collateralQuantity}")
            val stx = serviceHub.signInitialTransaction(ptx, serviceHub.myInfo.legalIdentity.owningKey)
            //subFlow(ResolveTransactionsFlow(stx, counterParty))
            send(counterParty, stx)
            //Wait until this txn is commited to the ledger - note this function suspends this flow till this happens.
            return waitForLedgerCommit(stx.id)

            //For implementation with CollectSignaturesFlow see old commit: #1f680fb
            //val fullySignedTX = subFlow(CollectSignaturesFlow(stx))
            //return subFlow(FinalityFlow(fullySignedTX)).single()
        }
    }
}

