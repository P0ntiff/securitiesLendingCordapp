package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.CollateralPreparationFlow
import com.secLendModel.flow.InsufficientHoldingException
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.transactions.TransactionBuilder
import com.secLendModel.flow.securitiesLending.LoanChecks
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.*
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.unwrap

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
            //TODO: Have an optional field for uniqueID -> if used then we need to have another flow for generating a loan provided with a unique ID. If its null we generate one and pass it to the generateIssue function
            //val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val counterParty = LoanChecks.getCounterParty(loanTerms, serviceHub.myInfo.legalIdentities.first())
            val flowSession = initiateFlow(counterParty)
            //STEP 1: Negotiation: Negotiate the borrower's desired terms of the loan with the lender
            val agreedTerms = subFlow(LoanAgreementFlow.Borrower(loanTerms))
            //send(counterParty, agreedTerms)
            flowSession.send(agreedTerms)
            //Now that the two parties have come to agreement on terms to use, begin to build the transaction
            //val builder = TransactionType.General.Builder(notary = notary)
            val builder = TransactionBuilder(notary = notary)
            //STEP 2: Put in either cash or securities, depending on which party we are in the deal.
            val myKey = serviceHub.myInfo.legalIdentities.first().owningKey
            val ptx: TransactionBuilder
            //If we are lender
            if (LoanChecks.isLender(loanTerms,serviceHub.myInfo.legalIdentities.first())){
                ptx = try {
                    subFlow(SecuritiesPreparationFlow(builder, agreedTerms.code, agreedTerms.quantity, agreedTerms.borrower)).first
                } catch (e: InsufficientHoldingException) {
                    throw SecurityException("Insufficient holding: ${e.message}", e)
                }
            }
            else{ //If we are the borrower
                //Add in the required collateral
                ptx = subFlow(CollateralPreparationFlow(builder, loanTerms.collateralType,
                        ((agreedTerms.stockPrice.quantity * agreedTerms.quantity) * (1.0 + agreedTerms.margin)).toLong(), agreedTerms.lender))
            }
            //val stx = sendAndReceive<SignedTransaction>(counterParty, ptx).unwrap {
            val stx = flowSession.sendAndReceive<SignedTransaction>(ptx).unwrap { it }
            subFlow(ResolveTransactionsFlow(stx, flowSession))

            //Sign and finalize this transaction.
            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentities.first().owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty)))
            return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
            //return subFlow(signTransactionFlow).tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(Initiator::class)
    open class Acceptor(val counterPartySession : FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call() : SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            //STEP 3: Connect to borrower / initiator
            //TODO: Make this stronger, check these agreed terms really are the agreed terms (i.e from LoanAgreementFlow)
            //val agreedTerms = receive<LoanTerms>(counterParty).unwrap { it }
            val agreedTerms = counterPartySession.receive<LoanTerms>().unwrap { it }
            val builder = counterPartySession.receive<TransactionBuilder>().unwrap { it }

            //STEP 4: Put in security states/collateral as inputs and securityLoan as output
            //Check which party in the deal we are
            val tx : TransactionBuilder
            if (LoanChecks.isLender(agreedTerms, serviceHub.myInfo.legalIdentities.first())) {
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
            val stx = serviceHub.signInitialTransaction(ptx, serviceHub.myInfo.legalIdentities.first().owningKey)
            //subFlow(ResolveTransactionsFlow(stx, counterParty))
            counterPartySession.send(stx)
            //Wait until this txn is commited to the ledger - note this function suspends this flow till this happens.
            return waitForLedgerCommit(stx.id)

            //For implementation with CollectSignaturesFlow see old commit: #1f680fb
            //val fullySignedTX = subFlow(CollectSignaturesFlow(stx))
            //return subFlow(FinalityFlow(fullySignedTX)).single()
        }
    }
}

