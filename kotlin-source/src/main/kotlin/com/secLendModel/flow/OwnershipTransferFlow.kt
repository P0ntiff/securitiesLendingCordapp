package com.secLendModel.flow

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.contract.SecurityClaim
import com.secLendModel.state.Security
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.contracts.TransactionType
import net.corda.core.crypto.Party
import net.corda.core.crypto.expandedCompositeKeys
import net.corda.core.crypto.toStringShort
import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.flows.CashException
import net.corda.flows.FinalityFlow
import java.security.KeyPair


// A class for transferring ownership of a securityClaim to another owner (recipient)

class OwnershipTransferFlow(val amount : Amount<Security>, val recipient : Party) : FlowLogic<SignedTransaction>() {

    override fun call() : SignedTransaction {
        val builder : TransactionBuilder = TransactionType.General.Builder(null)
        val acceptableStates = unconsumedStatesForSpending<SecurityClaim.State>(amount)
        val (spendTX, keysForSigning) = OnLedgerAsset.generateSpend(tx, amount, recipient,(
                builder,
                amount,
                recipient.owningKey
        )

        keysForSigning.expandedCompositeKeys.forEach {
            val key = serviceHub.keyManagementService.keys[it] ?: throw IllegalStateException("Could not find signing key for ${it.toStringShort()}")
            builder.signWith(KeyPair(it, key))
        }
        val tx = spendTX.toSignedTransaction(checkSufficientSignatures = false)
        subFlow(FinalityFlow(tx, setOf(recipient)))

        return tx
    }

}