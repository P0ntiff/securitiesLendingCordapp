package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.CollateralPreparationFlow
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.unwrap
import net.corda.flows.FinalityFlow
import net.corda.flows.ResolveTransactionsFlow


/**
 * Created by raymondm on 21/08/2017.
 *
 * A simple flow for combining multiple security loan states between two parties into a single loan, effectively
 * netting the position between the parties.
 *
 * Works by first updating loans to get all of them to same share price. Next it calculates the net share position and
 * colalteral position from those loans. Finally the new loan is generated with the correct amount of shares and cash added
 * to cover the repayment of old collateral, as well as the issuance of the new collateral.
 *
 */


object LoanNetFlow {
    @StartableByRPC
    @InitiatingFlow
    class NetInitiator(val otherParty: Party, val code: String, val collateralType: String
    ) : FlowLogic<UniqueIdentifier>() {
        @Suspendable
        override fun call() : UniqueIdentifier {
            //STEP1: Get Loans that are being merged and add them to the tx
            //TX Builder for states to be added to
            val builder = TransactionType.General.Builder(notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)
            val securityLoans: ArrayList<StateAndRef<SecurityLoan.State>> = ArrayList()
            //Also get the linear ID list for the other party
            val linearIDList = subFlow(LoanNetPrepFlow(otherParty, code, collateralType))

            //STEP2: Update each loan within this list to get the current price.
            //Note this wil be in a seperate tx on the ledger for each loan as LoanUpdateFlow initiates its own txn.
            linearIDList.forEach {
                val updatedLoanID = subFlow(LoanUpdateFlow.Updator(it))
                val updatedLoan = subFlow(LoanRetrievalFlow(updatedLoanID))
                builder.addInputState(updatedLoan)
                securityLoans.add(updatedLoan)
                println("Secloan added for net ${updatedLoan.state.data.code} ${updatedLoan.state.data.quantity} price ${updatedLoan.state.data.currentStockPrice}")
            }

            //STEP3: Calculate the output state (i.e the netted loan)
            //Check who is lender of borrower here.
            val lender = securityLoans.first().state.data.lender
            val borrower = securityLoans.first().state.data.borrower
            //Calculate the net amount of shares for this new loan
            val outputShares = securityLoans.map {
                if (it.state.data.borrower == borrower) {
                    -(it.state.data.quantity)
                } else {
                    it.state.data.quantity
                }
            }
            var outputSharesSum = 0
            outputShares.forEach { outputSharesSum += it }
            //Output shaers sum < 0 indicatse that the shares are going from lender to borrower
            if (outputSharesSum < 0) {
                //If we are the lender, we need to add shares as an input state
                if (serviceHub.myInfo.legalIdentity == lender) {
                    subFlow(SecuritiesPreparationFlow(builder,securityLoans.first().state.data.code,Math.abs(outputSharesSum),borrower))
                }
            //Otherwise shares are going from borrower to lender
            } else {
                //if we are borrower, we need to add shares as an input state
                if (serviceHub.myInfo.legalIdentity == borrower) {
                    subFlow(SecuritiesPreparationFlow(builder,securityLoans.first().state.data.code,Math.abs(outputSharesSum),lender))
                }
            }
            //Generate the new output loan
            SecurityLoan().generateLoanNet(builder,lender, borrower, securityLoans, outputSharesSum,
                    serviceHub.networkMapCache.notaryNodes.single().notaryIdentity)

            //STEP 4: Calculate the cash that needs to be added to pay off the original loan collateral, as well as this new loan collateral.
            val outputState = builder.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
            //Add cash as needed
            val outputLender = outputState.lender
            val outputBorrower = outputState.borrower
            val cashNet = securityLoans.map {
                if (it.state.data.borrower == borrower) {
                    //Consider the borrower as negative collateral (i.e they owe collateral)
                    -(it.state.data.quantity * it.state.data.stockPrice.quantity * it.state.data.terms.margin)
                } else {
                    //Consider the lender as positive collateral (i.e they are owed collateral)
                    it.state.data.quantity * it.state.data.stockPrice.quantity * it.state.data.terms.margin
                }
            }
            var cashNetSum = 0.0
            cashNet.forEach { cashNetSum += it }
            val ptx: TransactionBuilder
            //If cash net sum is negative, the borrower owed more collateral then the lender owed.
            //if we are borrower, add cash here, taking into account the cashNetSum
            //TODO: Not sure about this stuff below, is it needed?
            if (serviceHub.myInfo.legalIdentity == outputBorrower) {
                    //Add cash, make sure we dont try add zero cash as this throws an error
                if (cashNetSum < 0.toLong()) {

                    ptx = subFlow(CollateralPreparationFlow(builder, collateralType,
                            Math.abs(cashNetSum).toLong() - (outputState.stockPrice.quantity * outputState.quantity * outputState.terms.margin).toLong(), outputState.lender))
                } else {
                    ptx = builder
                }
            } else {
                ptx = builder
            }


            //STEP 5: Send TxBuilder with output loan state, and possible input securities or cash states. Also send the net cash position so we dont have to calculate again
            //Find out who our counterParty is (either lender or borrower)
            val counterParty = LoanChecks.getCounterParty(LoanChecks.stateToLoanTerms(securityLoans.first().state.data), serviceHub.myInfo.legalIdentity)
            send(counterParty, Pair(ptx, cashNetSum))

            //STEP 8 Receive back signed tx and finalize this update to the loan
            val stx = sendAndReceive<SignedTransaction>(counterParty, builder).unwrap {
                val wtx: WireTransaction = it.verifySignatures(serviceHub.myInfo.legalIdentity.owningKey,
                        serviceHub.networkMapCache.notaryNodes.single().notaryIdentity.owningKey)
                //Check txn dependency chain ("resolution")
                subFlow(ResolveTransactionsFlow(wtx, counterParty))
                it
            }
            val unnotarisedTX = serviceHub.addSignature(stx, serviceHub.myInfo.legalIdentity.owningKey)
            val finishedTX = subFlow(FinalityFlow(unnotarisedTX, setOf(counterParty))).single()
            return finishedTX.tx.outputs.map { it.data }.filterIsInstance<SecurityLoan.State>().single().linearId
        }
    }

    @InitiatedBy(NetInitiator::class)
    class NetAcceptor(val counterParty : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 6: Receive txBuilder and the netCashSum, with loanStates and possibly cash from initiator. Add Cash/securities if required
            val builder = receive<Pair<TransactionBuilder, Double>>(counterParty).unwrap {
                //Get the output loan state
                val outputState = it.first.outputStates().map { it.data }.filterIsInstance<SecurityLoan.State>().single()
                val outputSharesSum = outputState.quantity
                val code = outputState.code
                val outputLender = outputState.lender
                val outputBorrower = outputState.borrower
                //Add input stock as needed if we are the lender
                if (serviceHub.myInfo.legalIdentity == outputLender) {
                    //If we are the lender, we need to add shares as an input state
                    subFlow(SecuritiesPreparationFlow(it.first,code,Math.abs(outputSharesSum),outputBorrower))
                } else {
                    //If we are borrower, add the required collateral
                    if (serviceHub.myInfo.legalIdentity == outputBorrower) {
                        //Add cash, make sure we dont try to add zero cash as this throws an error
                        if (it.second > 0.toLong()) {
                            subFlow(CollateralPreparationFlow(it.first, outputState.terms.collateralType,
                                    Math.abs(it.second).toLong()  - (outputState.stockPrice.quantity * outputState.quantity * outputState.terms.margin).toLong()
                                    , outputState.lender))
                        }
                    }
                }
                it
            }

            //STEP 7: Sign Tx and send back to initiator
            val signedTX: SignedTransaction = serviceHub.signInitialTransaction(builder.first)
            send(counterParty, signedTX)
            return Unit
        }
    }


}
