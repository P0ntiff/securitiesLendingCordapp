package com.secLendModel.flow.securitiesLending

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
        val margin : Int,       //Percent
        val rebate : Int,        //Percent
        val lengthOfLoan: Int   //Length represented in days?
        //val collateralType: FungibleAsset<Any>
)