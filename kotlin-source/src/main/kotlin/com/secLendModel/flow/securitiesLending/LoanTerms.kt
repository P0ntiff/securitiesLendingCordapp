package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleAsset
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.time.LocalDateTime
import java.util.*


/** Storage container class for storing terms of a loan
 * used within the loan contract to simplfy interaction with the contract. Also contains some helper functions (see LoanChecks)
 * which are useful to getting certain fields of a loan.
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
        val lengthOfLoan: Int,   //Length represented in days?
        val collateralType: String, //A string describing the collateral type. Must be contained in the securityLoan contract field collateralType
        val effectiveDate: LocalDateTime
)

//Helper functions used in securitiesLending flows
object LoanChecks {
    //Function for checking if you are the lender in a deal.
    @Suspendable
    fun isLender(loanTerms: LoanTerms, me: Party): Boolean {
        val lender = loanTerms.lender
        return (me == lender)
    }

    //Function for getting the counterParty to a deal
    @Suspendable
    fun getCounterParty(loanTerms: LoanTerms, me: Party): Party {
        if (me == loanTerms.lender) {
            return loanTerms.borrower
        } else {
            return loanTerms.lender
        }
    }

    //Function for managing whether or not to add cash in response to a loan/margin update
    @Suspendable
    fun cashRequired(currentParty: Party, borrower: Party, lender: Party, changeMargin: Double) : Boolean {
        if (currentParty == borrower && changeMargin > 0) return true
        else if (currentParty == lender && changeMargin < 0) return true
        return false
    }

    //Function for converting from State terms into LoanTerms
    //TODO: Change this hardcoding
    @Suspendable
    fun stateToLoanTerms(state : SecurityLoan.State) : LoanTerms {
        return LoanTerms(state.code, state.quantity, state.stockPrice, state.lender, state.borrower,
                state.terms.margin, state.terms.rebate, state.terms.lengthOfLoan, state.terms.collateralType, state.terms.effectiveDate)
    }

    fun loanTermsToString(loanTerms: LoanTerms): String {
        return "Code: ${loanTerms.code}\nQuantity: ${loanTerms.quantity}\nPrice:${loanTerms.stockPrice.quantity}\n" +
                "Margin: ${loanTerms.margin}\nRebate: ${loanTerms.rebate}\nCollateral Type: ${loanTerms.collateralType}\n" +
                "From ${loanTerms.lender} "
    }
}