package com.secLendModel.contract

import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.keys
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.security.PublicKey

/**
 *  SecurityLoan Contract class. --> See "SecurityLoan.State" for state.
 */
class SecurityLoan : Contract {
    override val legalContractReference: SecureHash = SecureHash.zeroHash

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Exit: TypeOnlyCommandData(), Commands
    }

    data class Terms(val length: Int,
                     val margin: Int,
                     val rebatePercent: Int,
                     val collateralType: FungibleAsset<Cash> //TODO: Figure out what type collateralType is (could be cash, any fungible asset, etc)
                     )

    data class State(val quantity: Int,
                     val code: String,
                     val lender: Party,
                     val borrower: Party,
                     override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState {
        /**
         *  This property holds a list of the nodes which can "use" this state in a valid transaction. In this case, the
         *  lender or the borrower.
         */
        override val participants: List<AbstractParty> get() = listOf(lender, borrower)

        override fun isRelevant(ourKeys: Set<PublicKey>): Boolean {
            return ourKeys.intersect(participants.flatMap {
                it.owningKey.keys
            }).isNotEmpty()
        }

        override val contract get() = SecurityLoan()

        /**
         * A toString() helper method for displaying in the console.
         */
        override fun toString() = "SecurityLoan($linearId): ${borrower.name} owes ${lender.name} $quantity of $code shares."
    }

    override fun verify(tx: TransactionForContract): Unit {
        val command = tx.commands.requireSingleCommand<SecurityLoan.Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {
                //creating the loan state
                "No inputs should be consumed when issuing a secLoan." using (tx.inputs.isEmpty())
                "Only one output state should be created when issuing a SecurityLoan." using (tx.outputs.size == 1)
                val secLoan = tx.outputs.single() as State
                "A newly issued secLoan must have a positive amount." using (secLoan.quantity > 0)
                "The lender and borrower cannot be the same identity." using (secLoan.borrower != secLoan.lender)
                "Both lender and borrower together only may sign secLoan issue transaction." using
                        (command.signers.toSet() == secLoan.participants.map { it.owningKey }.toSet())
            }
            is Commands.Exit -> requireThat{
                //Exit the loan
            }

        }
    }
}