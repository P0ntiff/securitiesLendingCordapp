package com.secLendModel.contract

import com.secLendModel.schema.SecurityLoanSchemaV1
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.keys
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.TransactionBuilder
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*

/**
 *  SecurityLoan Contract class. --> See "SecurityLoan.State" for state.
 */
class SecurityLoan : Contract {
    override val legalContractReference: SecureHash = SecureHash.zeroHash

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Exit: TypeOnlyCommandData(), Commands
    }

    data class Terms(val lengthOfLoan: Int,
                     val margin: Int,
                     val rebate: Int,
                     val collateralType: FungibleAsset<Cash> //TODO: Figure out what type collateralType is (could be cash, any fungible asset, etc)
                     )
    //TODO: Should the state take in a securityClaim state and not just code, quantity, etc (this is already stored in SecurityClaimState)
    //Think this is the job of the flow -> adding inputs and outputs to the tx, we just verify current state and correct number of in and out
    data class State(val quantity: Int,
                     val code: String,
                     val stockPrice: Amount<Currency>,
                     val lender: Party,
                     val borrower: Party,
                     val terms: Terms,
                     //val stockState: SecurityClaim.State, // This could be added, quantity, code could be removed
                     override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState, QueryableState {
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
        override fun toString(): String{
            //If stockState is used
            //val quantity2 = stockState.quantity
            //val code2 = stockState.code
            return "SecurityLoan($linearId): ${borrower.name} owes ${lender.name} $quantity of $code shares."
        }

        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SecurityLoanSchemaV1)


        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is SecurityLoanSchemaV1 -> SecurityLoanSchemaV1.PersistentSecurityState(
                        //If stockState is used
                        //quantity = stockState.quantity,
                        //code = stockState.code,
                        lender = this.lender.owningKey.toBase58String(),
                        borrower = this.borrower.owningKey.toBase58String(),
                        code = this.code,
                        quantity = this.quantity,
                        //price with 2 decimal places
                        price = this.stockPrice.quantity,
                        id = this.linearId.toString(),
                        //Loan term values also saved to vault
                        length = this.terms.lengthOfLoan,
                        margin = this.terms.margin,
                        rebate = this.terms.rebate)

                else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }

    }}

    override fun verify(tx: TransactionForContract): Unit {
        val command = tx.commands.requireSingleCommand<SecurityLoan.Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {
                //creating the loan state
                "No inputs should be consumed when issuing a secLoan." using (tx.inputs.isEmpty()) //Should be two inputs -> securities and collateral
                "Only one output state should be created when issuing a SecurityLoan." using (tx.outputs.size == 1) //Three outputs -> cash/collateral, securities, securityLoanState
                val secLoan = tx.outputs.single() as State
                "A newly issued secLoan must have a positive amount." using (secLoan.quantity > 0)
                //"A newly issued secLoan must have a positive amount." using (secLoan.stockState.quantity > 0)
                "Shares must have some value" using (secLoan.stockPrice.quantity > 0)
                "The lender and borrower cannot be the same identity." using (secLoan.borrower != secLoan.lender)
                "Both lender and borrower together only may sign secLoan issue transaction." using
                        (command.signers.toSet() == secLoan.participants.map { it.owningKey }.toSet())
            }
            is Commands.Exit -> requireThat{
                //Exit the loan
                val secLoan = tx.inputs.single() as State
                "One input state should be consumed when exiting the secLoan" using (tx.inputs.size==1) //Three inputs: loanState, securities, collateral
                "No outputs should be created" using (tx.outputs.isEmpty()) //Outputs: cash (collateral returned to owner), securities(ownership transferred back to owner)
                "Input should be signed by both borrow and lender" using (command.signers.toSet()
                        == secLoan.participants.map{ it.owningKey }.toSet())
            }

        }
    }

    /** Functions below for generating an issue and exit transaction, based off
     * security claim contract
     */
    fun generateIssue(tx: TransactionBuilder,
                      quantity: Int,
                      code: String,
                      stockPrice: Amount<Currency>,
                      lender: Party,
                      borrower: Party,
                      lengthOfLoan: Int,
                      margin: Int,
                      rebate: Int,
                      linearId: UniqueIdentifier,
                      collateralType: FungibleAsset<Cash>,
                      notary: Party): TransactionBuilder{
        //Confirm this is a creation from no input states
        check(tx.inputStates().isEmpty())
        val terms = Terms(lengthOfLoan, margin, rebate, collateralType)
        val state = TransactionState(State(quantity,code,stockPrice,lender,borrower,terms, linearId), notary)
        tx.addOutputState(state)
        //TODO: check: should we add input and output states here, or is that done in flow and we simply worry about generating the securityLoan state
        //Tx signed by the lender
        tx.addCommand(SecurityLoan.Commands.Issue(), lender.owningKey)
        return tx
    }

    fun generateExit(
            tx: TransactionBuilder,
            secLoan: StateAndRef<SecurityLoan.State>,
            lender: Party,
            borrower: Party): TransactionBuilder {
        //Add the loan state as an input to the exit
        tx.addInputState(secLoan)
        tx.addCommand(SecurityLoan.Commands.Exit(), lender.owningKey)
        return tx
    }

}