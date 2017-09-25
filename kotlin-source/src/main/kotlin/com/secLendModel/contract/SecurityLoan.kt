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
        class Net: TypeOnlyCommandData(), Commands
        class PartialExit: TypeOnlyCommandData(), Commands
    }

    /** Simple data class for holding the collateral types accepted in loan terms */
    object collateralType {
        val securities = arrayListOf("GBT", "CBA", "RIO", "NAB")
        val cash = "Cash"
        val security = "Security"
    }

    @CordaSerializable
    data class Terms(val lengthOfLoan: Int,
                     val margin: Double,
                     val rebate: Double,
                     val collateralType: String//TODO: Figure out what type collateralType is (could be cash, any fungible asset, etc)
                     )

    data class State(val quantity: Int,
                     val code: String,
                     val stockPrice: Amount<Currency>,
                     val currentStockPrice: Amount<Currency>,
                     val lender: Party,
                     val borrower: Party,
                     val terms: Terms,
                     val collateralQuantity: Int,
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
                        //price with 2 decimal places
                        price = this.stockPrice.quantity.toInt(),
                        current_price = this.currentStockPrice.quantity.toInt(),
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
        //The following are the verify functions for each command available for a security loan
        //All these 'tests' for a command type must pass for the transactino to be considered valid.

        when (command.value) {
            is Commands.Issue -> requireThat {
                //Get input and output info
                val secLoan = tx.outputs.filterIsInstance<SecurityLoan.State>().single()
                var collateralTally : Long = 0
                var securityStatesTally = 0

                //Validate the states depending on collateral type
                if (secLoan.terms.collateralType == "Cash") {
                    //Do cash check
                    tx.outputs.forEach {
                        if (it is Cash.State && it.owner == secLoan.lender) {
                            collateralTally += it.amount.quantity
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                    }
                } else {
                    //Do securities check
                    tx.outputs.forEach {
                        if (it is SecurityClaim.State && it.owner == secLoan.lender) {
                            //TODO: We need a way to be able to get share price from within this contract
                            //val sharePrice = (PriceRequestFlow.PriceQueryFlow(it.code).call())
                            //This line forces the check to pass
                            collateralTally = ((secLoan.quantity * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong()
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                    }
                }

                //Run validation checks
                "Inputs should be consumed when issuing a secLoan." using (tx.inputs.isNotEmpty()) //Should be two input types -> securities and collateral(Cash States)
                "Collateral states in the outputs sum to the value of the loan + margin" using (Amount(collateralTally, CURRENCY) ==
                        Amount(((secLoan.quantity * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong(), CURRENCY))
                //This Line is no longer true with non-cash collateral "Securities states in the inputs sum to the quantity of the loan" using (securityStatesTally == secLoan.quantity)
                "A newly issued secLoan must have a positive amount." using (secLoan.quantity > 0)
                "Shares must have some value" using (secLoan.stockPrice.quantity > 0)
                "The lender and borrower cannot be the same identity." using (secLoan.borrower != secLoan.lender)
                "Both lender and borrower together only may sign secLoan issue transaction." using
                        (command.signers.toSet() == secLoan.participants.map { it.owningKey }.toSet())
            }
            is Commands.Exit -> requireThat{
                //Exit the loan
                //Get input and output info
                val secLoan = tx.inputs.filterIsInstance<SecurityLoan.State>().single()
                var collateralTally: Long = 0
                var securityStatesTally = 0
                var secLoanStates = 0

                //Validate the states depending on collateral type
                if (secLoan.terms.collateralType == "Cash") {
                    //Do cash check
                    tx.outputs.forEach {
                        if (it is Cash.State && it.owner == secLoan.lender) {
                            collateralTally += it.amount.quantity
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                        if (it is SecurityLoan.State) {secLoanStates += 1}
                    }
                } else {
                    //Do securities check
                    tx.outputs.forEach {
                        //TODO: this also picks up claim states going back to the lender, need to change this
                        if (it is SecurityClaim.State && it.owner == secLoan.lender) {
                            //TODO: We need a way to be able to get share price from within this contract
                            //val sharePrice = subFlow(PriceRequestFlow.PriceQueryFlow(oracle.identity, it.code))
                            collateralTally = ((secLoan.quantity * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong()
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                        if (it is SecurityLoan.State) {secLoanStates += 1}
                    }
                }

                //Run validation checks
                //"Cash states in the output sum to the value of the loan + margin" using (Amount(collateralTally, CURRENCY) ==
                        //Amount(((secLoan.quantity * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong(), CURRENCY))
                //"Security states in the output sum to the securities total of the loan" using (securityStatesTally == secLoan.quantity)
                "Secloan state must not be present in the output" using (secLoanStates == 0) //secLoan must be consumed as part of tx
                "Output must contain some states" using (tx.outputs.isNotEmpty())
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
                "Loans should match, besides margin and currentStockPrice" using ((inputLoan.stockPrice == outputLoan.stockPrice)
                        &&(inputLoan.borrower == outputLoan.borrower)
                        &&(inputLoan.code == outputLoan.code)
                        &&(inputLoan.lender == outputLoan.lender)
                        &&(inputLoan.quantity == outputLoan.quantity)
                        &&(inputLoan.terms.rebate == outputLoan.terms.rebate)
                        &&(inputLoan.terms.lengthOfLoan == outputLoan.terms.lengthOfLoan))
                "Both lender and borrower must have signed both input and output states." using
                        ((command.signers.toSet() == inputLoan.participants.map { it.owningKey }.toSet()) &&
                                (command.signers.toSet() == outputLoan.participants.map { it.owningKey }.toSet()))

            }

            is Commands.Net -> requireThat {
                "Only one output loan should be present" using (tx.outputs.filterIsInstance<SecurityLoan.State>().size == 1)
                val outputLoan = tx.outputs.filterIsInstance<State>().single()
                "Both lender and borrower must have signed both input and output states." using
                        ((command.signers.toSet() == outputLoan.participants.map { it.owningKey }.toSet()))

            }

            is Commands.PartialExit -> requireThat{
                //Exit the loan
                //Get input and output info
                val secLoan = tx.inputs.filterIsInstance<SecurityLoan.State>().single()
                val outputSecLoan = tx.outputs.filterIsInstance<SecurityLoan.State>().single()
                //TODO: Update this to work with non-cash collateral
                var collateralTally: Long = 0
                var securityStatesTally = 0
                var secLoanStates = 0

                //Validate the states depending on collateral type
                if (secLoan.terms.collateralType == "Cash") {
                    //Do cash check
                    tx.outputs.forEach {
                        if (it is Cash.State && it.owner == secLoan.lender) {
                            collateralTally += it.amount.quantity
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                        if (it is SecurityLoan.State) {secLoanStates += 1}
                    }
                } else {
                    //Do securities check
                    tx.outputs.forEach {
                        if (it is SecurityClaim.State && it.owner == secLoan.lender) {
                            //TODO: We need a way to be able to get share price from within this contract
                            //val sharePrice = subFlow(PriceRequestFlow.PriceQueryFlow(oracle.identity, it.code))
                            collateralTally = ((secLoan.quantity * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong()
                        }
                        if (it is SecurityClaim.State && it.code == secLoan.code && it.owner == secLoan.borrower) {
                            securityStatesTally += it.quantity
                        }
                        if (it is SecurityLoan.State) {secLoanStates += 1}
                    }
                }

                //Run validation checks
                //"Collateral states in the output sum to the value of the loan + margin" using (Amount(collateralTally, CURRENCY) ==
                        //Amount((((secLoan.quantity - outputSecLoan.quantity) * secLoan.stockPrice.quantity) * (1.0 + secLoan.terms.margin)).toLong(), CURRENCY))
                //"Security states in the output must be less than the total quantity of the input loan" using (securityStatesTally < secLoan.quantity)
                "Secloan state must be present in the output" using (secLoanStates == 1) //secLoan must be consumed as part of tx
                "Output must contain some states" using (tx.outputs.isNotEmpty())
                "Input should be signed by both borrow and lender" using (command.signers.toSet()
                        == secLoan.participants.map{ it.owningKey }.toSet())

            }

        }
    }

    /** Functions below for generating an issue and exit transaction, based off
     * security claim contract
     */
    fun generateIssue(tx: TransactionBuilder,
                      loanTerms: LoanTerms,
                      notary: Party,
                      collateralQuantity: Int): TransactionBuilder{
        val state = TransactionState(State(loanTerms.quantity, loanTerms.code, loanTerms.stockPrice, loanTerms.stockPrice, loanTerms.lender, loanTerms.borrower,
                Terms(loanTerms.lengthOfLoan, loanTerms.margin, loanTerms.rebate, loanTerms.collateralType), collateralQuantity), notary)
        tx.addOutputState(state)
        //Tx signed by the lender
        tx.addCommand(SecurityLoan.Commands.Issue(), loanTerms.lender.owningKey, loanTerms.borrower.owningKey)
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
                       priceUpdate: Amount<Currency>,
                       marginUpdate: Double,
                       secLoan: StateAndRef<SecurityLoan.State>,
                       lender: Party,
                       borrower: Party): TransactionBuilder{
        tx.addInputState(secLoan)
        //Copy the input state and create a new output state with a changed margin and a changed currentStockPrice
        tx.addOutputState(TransactionState(secLoan.state.data.copy(currentStockPrice = priceUpdate,terms = secLoan.state.data.terms.copy(margin = marginUpdate)), secLoan.state.notary))
        tx.addCommand(SecurityLoan.Commands.Update(), lender.owningKey, borrower.owningKey)
        return tx
    }

    fun generateLoanNet(tx: TransactionBuilder,
                        lender: Party,
                        borrower: Party,
                        secLoanStates: List<StateAndRef<SecurityLoan.State>>,
                        outputSharesSum: Int,
                        notary: Party) {
        //Calculate the net of all the input shares. Borrower is taken as the negative position
        //Therefor if negative total, then the abs(outputShares) goes to borrower
        val secLoan = secLoanStates.first()

        //TODO: rather than using the first share for margin, currently defaulting this to 0.05%. Maybe find a better way to do this i.e loanAgreementFlow
        //TODO: Sort out collateral quantity here
        if (outputSharesSum < 0) {
            //More shares are borrowed by borrower then lent by lender, output state is a borrower to borrower
            tx.addOutputState(TransactionState(State(Math.abs(outputSharesSum), secLoan.state.data.code, secLoan.state.data.currentStockPrice, secLoan.state.data.currentStockPrice,
                    secLoan.state.data.lender, secLoan.state.data.borrower,
                    Terms(secLoan.state.data.terms.lengthOfLoan, 0.05, secLoan.state.data.terms.rebate, secLoan.state.data.terms.collateralType), secLoan.state.data.collateralQuantity), notary))
        } else {
            tx.addOutputState(TransactionState(State(Math.abs(outputSharesSum), secLoan.state.data.code, secLoan.state.data.currentStockPrice, secLoan.state.data.currentStockPrice,
                    secLoan.state.data.borrower, secLoan.state.data.lender,
                    Terms(secLoan.state.data.terms.lengthOfLoan, 0.05, secLoan.state.data.terms.rebate, secLoan.state.data.terms.collateralType), secLoan.state.data.collateralQuantity), notary))
        }
        tx.addCommand(SecurityLoan.Commands.Net(), lender.owningKey, borrower.owningKey)
        //Otherwise there is no output state, we simply terminate both loans. This is checked in flow
    }

    fun generatePartialExit(
            tx: TransactionBuilder,
            originalSecLoan: StateAndRef<SecurityLoan.State>,
            newAmount: Int,
            lender: Party,
            borrower: Party,
            notary: Party): TransactionBuilder {
        //Add the loan state as an input to the exit
        //TODO: sort out collateral quantity here -> currently is the ratio of new amount to old amount times the original quantity of collateral
        tx.addInputState(originalSecLoan)
        tx.addOutputState(TransactionState(State(newAmount, originalSecLoan.state.data.code, originalSecLoan.state.data.currentStockPrice, originalSecLoan.state.data.currentStockPrice,
                originalSecLoan.state.data.lender, originalSecLoan.state.data.borrower,
                Terms(originalSecLoan.state.data.terms.lengthOfLoan, originalSecLoan.state.data.terms.margin, originalSecLoan.state.data.terms.rebate, originalSecLoan.state.data.terms.collateralType),
                originalSecLoan.state.data.collateralQuantity * (newAmount/originalSecLoan.state.data.quantity)), notary))
        tx.addCommand(SecurityLoan.Commands.PartialExit(), lender.owningKey, borrower.owningKey)
        return tx
    }


}