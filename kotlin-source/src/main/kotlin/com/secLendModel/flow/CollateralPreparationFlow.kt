package com.secLendModel.flow

/**
 * Created by raymondm on 11/09/2017.
 */

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import com.secLendModel.flow.oracle.Oracle
import com.secLendModel.flow.oracle.PriceRequestFlow
import net.corda.core.contracts.*
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.keys
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import java.security.PublicKey
import java.util.ArrayList

/**
 * This flow gets called as a subflow whenever collateral needs to be added to a transaction. It checks the type of
 * collateral and adds the required value of that type to the tx
 **/


@StartableByRPC
@InitiatingFlow
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
        if (collateralType != SecurityLoan.collateralType.cash && !SecurityLoan.collateralType.securities.contains(collateralType)) {
            throw Exception("Invalid Collateral Type ${collateralType}")
        }
        val newTx: TransactionBuilder
        if (collateralType == SecurityLoan.collateralType.cash) {
            //Add cash collateral
            newTx = serviceHub.vaultService.generateSpend(tx,
                    Amount((totalValue).toLong(), CURRENCY),
                    AnonymousParty(to.owningKey)).first
        }
        else {
            //Add securities collaeral
            val oracle = serviceHub.cordaService(Oracle::class.java)
            //TODO: Add correct amount of securities. How do we decide what security? What if we dont have enough of this security?
            //val currentPrice = subFlow(PriceRequestFlow.PriceQueryFlow(oracle.identity, collateralType))
            val currentPrice = subFlow(PriceRequestFlow.PriceQueryFlow(collateralType))
            println("Collateral price was ${currentPrice.quantity}")
            /** Note: Calling this flow calls heaps of issues due to how the move vertification is setup. Should fix that at some point
             * but for now just avoid calling this flow twice (exactly what happened here)
             */
            val quantity = (totalValue / currentPrice.quantity).toInt()
            newTx = subFlow(SecuritiesPreparationFlow(tx, collateralType, quantity, to as Party)).first


        }
    return newTx
    }
}