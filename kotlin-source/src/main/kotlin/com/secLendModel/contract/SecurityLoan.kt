package com.secLendModel.contract

import com.secLendModel.CURRENCY
import com.secLendModel.flow.securitiesLending.LoanTerms
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
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
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
        class Update: TypeOnlyCommandData(), Commands
    }

    @CordaSerializable
    data class Terms(val lengthOfLoan: Int,
                     val margin: Double,
                     val rebate: Double //TODO: Figure out what type collateralType is (could be cash, any fungible asset, etc)
                     )

    data class State(val quantity: Int,
                     val code: String,
                     val stockPrice: Amount<Currency>,
                     val lender: Party,
                     val borrower: Party,
                     val terms: Terms,
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
            return "SecurityLoan: ${borrower.name} owes ${lender.name} $quantity of $code shares. ID = ($linearId) Margin = ${terms.margin}"
        }

        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SecurityLoanSchemaV1)


        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is SecurityLoanSchemaV1 -> SecurityLoanSchemaV1.PersistentSecurityState(
                        lender = this.lender.owningKey.toBase58String(),
                        borrower = this.borrower.owningKey.toBase58String(),
                        code = this.code,
                        quantity = this.quantity,
                        price = this.stockPrice,
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
                //Get input and output info
                var secLoanIndex = 0
                tx.outputs.forEach{
                    if (it is SecurityLoan.State) { secLoanIndex = tx.outputs.indexOf(it)}
                }
                val secLoan: SecurityLoan.State = tx.outputs.get(secLoanIndex) as State
                var cashStatesTally : Long = 0
                var securityStatesTally = 0
                tx.outputs.forEach {
                    if (it is Cash.State && it.owner == secLoan.lender) {
                        cashStatesTally += it.amount.quantity
                    }
                    if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                        securityStatesTally += it.quantity
                    }
                }
                //Check we have some inputs -> Not being restrictive at this point in time
                "Inputs should be consumed when issuing a secLoan." using (tx.inputs.isNotEmpty()) //Should be two input types -> securities and collateral(Cash States)
                "Cash states in the outputs sum to the value of the loan + margin" using (Amount(cashStatesTally, CURRENCY) == Amount(secLoan.quantity * secLoan.stockPrice.quantity, CURRENCY)) //+
                    //    secLoan.quantity * secLoan.stockPrice.quantity.toInt()*secLoan.terms.margin)
                "Securities states in the inputs sum to the quantity of the loan" using (securityStatesTally == secLoan.quantity)
                "A newly issued secLoan must have a positive amount." using (secLoan.quantity > 0)
                "Shares must have some value" using (secLoan.stockPrice.quantity > 0)
                "The lender and borrower cannot be the same identity." using (secLoan.borrower != secLoan.lender)
                "Both lender and borrower together only may sign secLoan issue transaction." using
                        (command.signers.toSet() == secLoan.participants.map { it.owningKey }.toSet())
            }
            is Commands.Exit -> requireThat{
                //Exit the loan
                //Get input and output info
                var secLoanIndex = 0
                tx.inputs.forEach{
                    if (it is SecurityLoan.State) { secLoanIndex = tx.outputs.indexOf(it) }
                }
                val secLoan: SecurityLoan.State = tx.inputs.get(secLoanIndex) as State
                var cashStatesTally = 0
                var securityStatesTally = 0
                var secLoanStates = 0
                tx.outputs.forEach {
                    if (it is Cash.State) { cashStatesTally += it.amount.quantity.toInt() }
                    if (it is SecurityClaim.State && it.code == secLoan.code) { securityStatesTally += it.quantity}
                    if (it is SecurityLoan.State) {secLoanStates += 1}
                }
                "Cash states in the output sum to the value of the loan" using (cashStatesTally == secLoan.quantity * secLoan.stockPrice.quantity.toInt())
                "Security states in the output sum to the securities total of the loan" using (securityStatesTally == secLoan.quantity)
                "Secloan state must not be present in the output" using (secLoanStates == 0) //secLoan must be consumed as part of tx
                "Input should be signed by both borrow and lender" using (command.signers.toSet()
                        == secLoan.participants.map{ it.owningKey }.toSet())

            }

            is Commands.Update -> requireThat {
                //Update the loan margin
                "Only one input loan should be present" using (tx.inputs.filterIsInstance<State>().size == 1)
                "Only one output loan should be present" using (tx.outputs.filterIsInstance<State>().size == 1)
                //Check the ID of both loanStates is the same
                val inputLoan = tx.inputs.filterIsInstance<State>().single()
                val outputLoan = tx.outputs.filterIsInstance<State>().single()
                "Linear ID should match" using (inputLoan.linearId == outputLoan.linearId)
                //TODO: Check that state fields are the same besides margin
                "Both lender and borrower must have signed both input and output states." using
                        ((command.signers.toSet() == inputLoan.participants.map { it.owningKey }.toSet()) &&
                                (command.signers.toSet() == outputLoan.participants.map { it.owningKey }.toSet()))

            }
        }
    }

    /** Functions below for generating an issue and exit transaction, based off
     * security claim contract
     */
    fun generateIssue(tx: TransactionBuilder,
                      loanTerms: LoanTerms,
                      borrower: Party,
                      notary: Party): TransactionBuilder{
        val state = TransactionState(State(loanTerms.quantity, loanTerms.code, loanTerms.stockPrice, loanTerms.lender, borrower,
                Terms(loanTerms.lengthOfLoan, loanTerms.margin, loanTerms.rebate)), notary)
        tx.addOutputState(state)
        //TODO: check: should we add input and output states here, or is that done in flow and we simply worry about generating the securityLoan state
        //Tx signed by the lender
        tx.addCommand(SecurityLoan.Commands.Issue(), loanTerms.lender.owningKey, borrower.owningKey)
        return tx
    }

    fun generateExit(
            tx: TransactionBuilder,
            secLoan: StateAndRef<SecurityLoan.State>,
            lender: Party,
            borrower: Party): TransactionBuilder {
        //Add the loan state as an input to the exit
        tx.addInputState(secLoan)
        tx.addCommand(SecurityLoan.Commands.Exit(), lender.owningKey, borrower.owningKey)
        return tx
    }

    fun generateUpdate(tx: TransactionBuilder,
                     marginUpdate: Double,
                     secLoan: StateAndRef<SecurityLoan.State>,
                     lender: Party,
                     borrower: Party): TransactionBuilder{
        tx.addInputState(secLoan)
        //Copy the input state and create a new output state with a changed margin
        tx.addOutputState(TransactionState(secLoan.state.data.copy(terms = secLoan.state.data.terms.copy(margin = marginUpdate)), secLoan.state.notary))
        tx.addCommand(SecurityLoan.Commands.Update(), lender.owningKey, borrower.owningKey)
        return tx
    }

}