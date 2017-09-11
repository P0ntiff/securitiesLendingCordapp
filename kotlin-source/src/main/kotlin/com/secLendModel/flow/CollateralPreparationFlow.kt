package com.secLendModel.flow

/**
 * Created by raymondm on 11/09/2017.
 */

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CURRENCY
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.*
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
        if (!SecurityLoan.collateralType.types.contains(collateralType)) {
            throw Exception("Invalid Collateral Type")
        }
        val newTx: TransactionBuilder
        if (collateralType == SecurityLoan.collateralType.cash) {
            //Add cash collateral
            newTx = serviceHub.vaultService.generateSpend(builder,
                    Amount((totalValue).toLong(), CURRENCY),
                    AnonymousParty(to.owningKey)).first
        }

        else if (collateralType == SecurityLoan.collateralType.security) {
            //Add securities collaeral
            //TODO: Add another field for prefered type of security in loanTerms, for now we get any old security.
            newTx = serviceHub.vaultService.generateSpend(builder,
                    Amount((totalValue).toLong(), CURRENCY),
                    AnonymousParty(to.owningKey)).first
        }

        else {
            newTx = tx
        }
        return newTx
    }

}