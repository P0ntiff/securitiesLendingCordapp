package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.flow.securitiesLending.LoanChecks.isLender
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
            val myKey = serviceHub.myInfo.legalIdentities.first().owningKey
            if(isLender(loanTerms, serviceHub.myInfo.legalIdentities.first())){
                val session = initiateFlow(loanTerms.borrower)
                val counterProposal : LoanTerms = session.sendAndReceive<LoanTerms>(loanTerms).unwrap { it }
                //Accept counter proposal for now
                //TODO: negotiate terms of loan here
                if (counterProposal == loanTerms) {
                    return loanTerms
                } else {
                    throw AgreementException(null)
                }
            }
            else{
                val session = initiateFlow(loanTerms.lender)
                val counterProposal : LoanTerms = session.sendAndReceive<LoanTerms>(loanTerms).unwrap { it }
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
    class Lender(val borrowerSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() : Unit {
            val offer = borrowerSession.receive<LoanTerms>().unwrap { it }
            //accept terms of agreement for now
            val offerIsAcceptable = true
            if (offerIsAcceptable) {
                borrowerSession.send(offer)
            } else {
                //TODO: provide a counter proposal here
            }
        }
    }

}