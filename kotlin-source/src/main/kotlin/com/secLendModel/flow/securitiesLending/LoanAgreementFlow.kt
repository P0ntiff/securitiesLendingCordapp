package com.secLendModel.flow.securitiesLending

import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleAsset
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
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
            val collateralType : FungibleAsset<Cash>
    )

    @StartableByRPC
    @InitiatingFlow
    class Borrower(val code: String,
                   val quantity: Int,
                   val stockPrice: Amount<Currency>,
                   val buyer: Party,
                   val margin : Int,
                   val rebate : Int,
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
                    collateralType
            )

            val counterProposal : LoanOffer = sendAndReceive<LoanOffer>(buyer, loanOffer).unwrap { it }
            //accept counter proposal for now
            //TODO: negotiate terms of loan here
            if (counterProposal.equals(loanOffer)) {
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
        }

    }
}