package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

/** Flow for negotiating the terms of a loan.
 *
 * Should be called as a subflow (and the first thing called) within a more complicated workflow for putting in cash states,
 * security states, and securityLoan states on both sides of a trade between lender and borrower
 *
 *  This is a fLow to model initial agreement of security loan terms between an interested borrower (the initiator) and a
 *  potentially interested lender (the acceptor).
 */

class AgreementException(val error : String?) : FlowException() {
    override fun toString() = "Parties could not come to agreement on terms of loan: $error"
}

object LoanAgreementFlow {
    @StartableByRPC
    @InitiatingFlow
    class Borrower(val loanTerms : LoanTerms) : FlowLogic<LoanTerms>() {
        @Suspendable
        override fun call() : LoanTerms {
            val myKey = serviceHub.myInfo.legalIdentity.owningKey
            if(loanAgreementChecks().isLender(loanTerms, serviceHub.myInfo.legalIdentity)){
                val counterProposal : LoanTerms = sendAndReceive<LoanTerms>(loanTerms.borrower, loanTerms).unwrap { it }
                //Accept counter proposal for now
                //TODO: negotiate terms of loan here
                if (counterProposal == loanTerms) {
                    return loanTerms
                } else {
                    throw AgreementException(null)
                }
            }
            else{
                val counterProposal : LoanTerms = sendAndReceive<LoanTerms>(loanTerms.lender, loanTerms).unwrap { it }
                //Accept counter proposal for now
                //TODO: negotiate terms of loan here
                if (counterProposal == loanTerms) {
                    return loanTerms
                } else {
                    throw AgreementException(null)
                }
            }


        }
    }

    @InitiatedBy(Borrower::class)
    class Lender(val borrower : Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() : Unit {
            val offer = receive<LoanTerms>(borrower).unwrap { it }
            //accept terms of agreement for now
            val offerIsAcceptable = true
            if (offerIsAcceptable) {
                send(borrower, offer)
            } else {
                //TODO: provide a counter proposal here
            }
        }
    }

    class loanAgreementChecks(){
        fun isLender(loanTerms: LoanTerms, me: Party): Boolean{
            val lender = loanTerms.lender
            return (me == lender)
        }
    }
}