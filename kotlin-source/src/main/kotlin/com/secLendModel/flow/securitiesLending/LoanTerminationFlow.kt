package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * Flow to model the conclusion of a security loan, creating a TXN with the following structure:
 *
 *  Commands: Exit/Terminate
 *  Inputs:  -Cash Collateral + Margin (from lender),
 *           -Securities (from borrower)
 *           -SecurityLoan (owned by lender)
 *  Outputs: -Cash (owned by borrower)
 *           -Securities (owned by lender)
 */

object LoanTerminationFlow {
    @StartableByRPC
    @InitiatingFlow
    open class Borrower(val loanID : UniqueIdentifier) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //TODO: Implement, for now, the opposite logic of the LoanIssuanceFlow
            //STEP 1 Retrieve LoanState from the vault of the borrower
            val secLoan = subFlow(LoanRetrievalFlow(loanID))

            //STEP 2: Prepare the txBuilder for the exit -> add the securityLoan states
            val lender = secLoan.state.data.lender
            val borrower = secLoan.state.data.borrower
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            SecurityLoan().generateExit(builder, secLoan, lender, borrower)

            //STEP 3: Add the security states
            val code = secLoan.state.data.code
            val quantity = secLoan.state.data.quantity
            //Generate the tx sending stocks back to the lender
            val (tx, keysForSigning) = try {
                subFlow(SecuritiesPreparationFlow(builder, code, quantity, lender))
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }

            //STEP 4: Send the tx containing stock and the securityLoan to the lender
            val ptx = sendAndReceive<SignedTransaction>(lender, tx)
            return Unit
        }
    }

    class Lender(val borrower : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {

            //STEP 5: Receieve tx from borrower and add the required cash state -> What is the required cash state
            val ptx = receive<TransactionBuilder>(borrower).unwrap {
                //TODO: Check we want to settle this loan/the total loan time has elapsed
                it }
            val cashToAdd: Long = 5
            //val cashToAdd = (secLoan.state.data.quantity * secLoan.state.data.stockPrice.quantity * Math.abs(changeMargin)).toLong()
            serviceHub.vaultService.generateSpend(ptx,
                    Amount(cashToAdd, CURRENCY),
                    AnonymousParty(borrower.owningKey)
            )

            //STEP 6: Send this tx back to the borrow
            return Unit
        }
    }
}