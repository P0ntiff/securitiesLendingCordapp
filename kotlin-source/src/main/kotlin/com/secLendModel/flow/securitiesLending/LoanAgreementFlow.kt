package com.secLendModel.flow.securitiesLending

import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.SecuritiesPreparationFlow
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleAsset
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.security.PublicKey
import java.util.*

/** Should be called as a subflow (and the first thing called) within a more complicated workflow for putting in cash states,
 * security states, and securityLoan states on both sides of a trade between lender and borrower
 *
 *  This is a fLow to model initial agreement of security loan terms between an interested borrower (the initiator) and a
 *  potentially interested lender (the acceptor)
 */
object LoanAgreementFlow {

    class AgreementException(val error : String?) : FlowException() {
        override fun toString() = "Parties could not come to agreement on terms of loan: $error"
    }

    @CordaSerializable
    data class LoanOffer(
            val code : String,
            val quantity : Int,
            val stockPrice : Amount<Currency>,
            val lenderKey : PublicKey,
            val margin : Int,       //Percent
            val rebate : Int,        //Percent
            val lengthOfLoan: Int,   //Length represented in days?
            val collateralType : FungibleAsset<Cash>
    )

    @StartableByRPC
    @InitiatingFlow
    class Borrower(val code: String,
                   val quantity: Int,
                   val stockPrice: Amount<Currency>,
                   val lender: Party,
                   val margin : Int,
                   val rebate : Int,
                   val lengthOfLoan: Int,
                   val collateralType : FungibleAsset<Cash>) : FlowLogic<Unit>() {
        override fun call() : Unit {
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            val loanOffer = LoanOffer(
                    code,
                    quantity,
                    stockPrice,
                    myKey,
                    margin,
                    rebate,
                    lengthOfLoan,
                    collateralType
            )

            val counterProposal : LoanOffer = sendAndReceive<LoanOffer>(lender, loanOffer).unwrap { it }
            //accept counter proposal for now
            //TODO: negotiate terms of loan here
            if (counterProposal.equals(loanOffer)) {
                //TODO: Check with ben this is on the right track for sending a cash value
                val builder = TransactionBuilder()
                val (ptx, cashSigningPubKeys) = serviceHub.vaultService.
                        generateSpend(builder,
                                Amount(stockPrice.quantity * quantity, CURRENCY),
                                AnonymousParty(lender.owningKey)
                        )
                send(lender,ptx)

                return Unit
            } else {
                throw AgreementException(null)
            }

        }
    }

    @InitiatedBy(Borrower::class)
    class Lender(val lender : Party) : FlowLogic<Unit>() {
        override fun call() : Unit {
            val offer = receive<LoanOffer>(lender).unwrap { it }
            //accept terms of agreement for now
            val offerIsAcceptable = true
            if (offerIsAcceptable) {
                send(lender, offer)
            } else {
                //TODO: provide a counter proposal here
            }
            //receive tx builder w/ cash and signature
            val received = receive<TransactionBuilder>(lender)
            //Generate appropriate states for the tx i.e input = securities, output = securityLoan issue







        }

    }
}