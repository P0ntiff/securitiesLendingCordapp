package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleAsset
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.util.*


/**
 * Created by beng on 13/07/2017.
 */
@CordaSerializable
data class LoanTerms(
        val code : String,
        val quantity : Int,
        val stockPrice : Amount<Currency>,
        val lender : Party,
        val borrower : Party,
        val margin : Double,       //Percent
        val rebate : Double,        //Percent
        val lengthOfLoan: Int   //Length represented in days?
        //val collateralType: FungibleAsset<Any>
)

//Helper functions in securities lending flows
object LoanChecks {
    //Function for checking if you are the lender in a deal.
    @Suspendable
    fun isLender(loanTerms: LoanTerms, me: Party): Boolean {
        val lender = loanTerms.lender
        return (me == lender)
    }

    @Suspendable
    fun getCounterParty(loanTerms: LoanTerms, me: Party): Party {
        if (me == loanTerms.lender) {
            return loanTerms.borrower
        } else {
            return loanTerms.lender
        }
    }

    //Function for managing adding cash and checking requirements for a loanUpdate
    @Suspendable
    fun cashRequired(currentParty: Party, borrower: Party, lender: Party, changeMargin: Double) : Boolean {
        if (currentParty == borrower && changeMargin > 0) return true
        else if (currentParty == lender && changeMargin < 0) return true
        return false
    }

    //converts a SecurityLoan.State to a LoanTerms
    @Suspendable
    fun stateToLoanTerms(state : SecurityLoan.State) : LoanTerms {
        return LoanTerms(state.code, state.quantity, state.stockPrice, state.lender, state.borrower,
                state.terms.margin, state.terms.rebate, state.terms.lengthOfLoan)
    }
}