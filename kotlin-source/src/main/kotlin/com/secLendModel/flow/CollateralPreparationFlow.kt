package com.secLendModel.flow

/**
 * Created by raymondm on 11/09/2017.
 */

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.PriceRequestFlow
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.OnLedgerAsset

/** Collateral prep flow for preparing a specified type of collateral for a tx.
 * This flow gets called as a subflow whenever collateral needs to be added to a transaction. It checks the type of
 * collateral and adds the required value of that type to the tx (think a more generalised genereateSpend() allowing
 * use of not just cash collateral
 **/


@StartableByRPC
@InitiatingFlow
/**
 * @param builder the tx builder for this tx
 * @param collateralType the collteral type to prepare. For this cordapp it is one of GBT, CBA, RIO, NAB or Cash
 */
class CollateralPreparationFlow(val builder : TransactionBuilder,
                                val collateralType : String,
                                val totalValue : Long,
                                val recipient : Party) : FlowLogic<TransactionBuilder>() {
    override fun call(): TransactionBuilder {
        val recipientKey = recipient

        return prepareTransaction(builder, collateralType, totalValue, recipientKey)
    }

    @Suspendable
    private fun prepareTransaction(tx: TransactionBuilder,
                                   collateralType: String,
                                   totalValue: Long,
                                   to: AbstractParty) : TransactionBuilder {

        //Check that the collateral type is valid
        if (collateralType != SecurityLoan.collateralType.cash && !SecurityLoan.collateralType.securities.contains(collateralType)) {
            throw Exception("Invalid Collateral Type ${collateralType}")
        }
        val newTx: TransactionBuilder

        //Check the type of collateral and add appropriately
        if (collateralType == SecurityLoan.collateralType.cash) {
            //Add cash collateral
//            newTx = serviceHub.vaultService.generateSpend(tx,
//                    Amount((totalValue).toLong(), CURRENCY),
//                    AnonymousParty(to.owningKey)).first
            Cash.generateSpend(serviceHub, tx,
                    Amount((totalValue).toLong(), CURRENCY),
                    AnonymousParty(to.owningKey))
            newTx = tx
        }
        else {
            //Calculate the price of the security and how many securities are needed to reach the required value.
            val currentPrice = subFlow(PriceRequestFlow.PriceQueryFlow(collateralType))
            println("Collateral price was ${currentPrice.quantity}")
            val quantity = (totalValue / currentPrice.quantity).toInt()
            //Add securities if possible (i.e party has enough holdings of that security)
            try {
                newTx = subFlow(SecuritiesPreparationFlow(tx, collateralType, quantity, to as Party)).first
            } catch (e: InsufficientBalanceException) {
                throw SecurityException("Insufficient holding: ${e.message}", e)
            }


        }
    return newTx
    }
}