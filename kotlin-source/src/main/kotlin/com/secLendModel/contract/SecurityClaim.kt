package com.secLendModel.contract

import com.secLendModel.schema.SecuritySchemaV1
//import net.corda.contracts.clause.AbstractIssue
import net.corda.core.contracts.*
//import net.corda.core.contracts.clauses.*
import net.corda.core.crypto.*
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.crypto.random63BitValue
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.utilities.toBase58String
import java.security.PublicKey

/** SecurityClaim used for within the securityLending cordapp. Each state has an issuer, owner, security type/instrument type.
 * Security claim states can only be issued by nodes with the corresponding securityClaim issue permission and are needed
 * for many of the securityLoan flows
 *
 */

val SECURITY_PROGRAM_ID = SecurityClaim()

/**
 * A SecurityClaim transaction may split and merge claims on stock represented by a set of (issuer, depositRef) pairs, across multiple
 * input and output states.
 */

class SecurityClaim : Contract {
    val legalContractReference: SecureHash = SecureHash.sha256("https://www.big-book-of-banking-law.gov/Security-claims.html")
    //override fun verify(tx: TransactionForContract) = verifyClause(tx, Clauses.Group(), tx.commands.select<SecurityClaim.Commands>())
    //override fun verify(tx: LedgerTransaction) = net.corda.core.contracts.clauses.verifyClause(tx, Clauses.Group(), tx.commands.select<SecurityClaim.Commands>())
    override fun verify(tx: LedgerTransaction) {
        val keysThatSigned = arrayListOf<PublicKey>()
        tx.commands.forEach { keysThatSigned.addAll(it.signers) }
        tx.commands.forEach {
            if (it.value == SecurityClaim.Commands.Move()) {
                //Use move verification
                val owningPubKeys2 = tx.inputsOfType<OwnableState>().map {it.owner.owningKey }.toSet()
                requireThat {
                    "the owning keys are a subset of the signing keys" using keysThatSigned.containsAll(owningPubKeys2)
                }
            } else if (it.value == SecurityClaim.Commands.Issue()) {
                //Use issue verification
                //Can only have one issue command per txn and this must be the only command in that txn
                tx.commands.requireSingleCommand<Commands.Issue>()
            }
        }
    }

    data class Terms(
            val quantity: Int,
            val code: String
    )

    /** A state representing a Security claim against some party */
    data class State(
            val issuance: PartyAndReference,
            override val owner: AbstractParty,
            val code: String,
            val quantity: Int
    ) : OwnableState, QueryableState {
        val contract = SECURITY_PROGRAM_ID
        override val participants: List<AbstractParty> = listOf(owner)

        val token: Issued<Terms>
            get() = Issued(issuance, Terms(quantity, code))

        //override fun withNewOwner(newOwner: AbstractParty) = Pair(Commands.Move(), copy(owner = newOwner))
        override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Commands.Move(), copy(owner = newOwner))
        override fun toString() = "$quantity shares in $code owned by $owner)"

        /** Object Relational Mapping support. */
        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SecuritySchemaV1)
        /** Additional used schemas would be added here (eg. SecurityClaimV2, ...) */

        /** Object Relational Mapping support. */
        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is SecuritySchemaV1 -> SecuritySchemaV1.PersistentSecurityState(
                        issuerParty = this.issuance.party.owningKey.toBase58String(),
                        issuerRef = this.issuance.reference.bytes,
                        owner = this.owner.owningKey.toBase58String(),
                        code = this.code,
                        quantity = this.quantity
                )
            /** Additional schema mappings would be added here (eg. SecurityClaimV2, ...) */
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }
    }

//    interface Clauses {
//        class Group : GroupClauseVerifier<State, Commands, Issued<Terms>>(
//                AnyOf(
//                        Move(),
//                        Issue())) {
//            override fun groupStates(tx: TransactionForContract): List<TransactionForContract.InOutGroup<State, Issued<Terms>>>
//                    = tx.groupStates<State, Issued<Terms>> { it.token }
//        }
//
//        class Issue : AbstractIssue<State, Commands, Terms>(
//                { map { Amount(it.quantity.toLong(), it.token) }.sumOrThrow() },
//                { token -> map { Amount(it.quantity.toLong(), it.token) }.sumOrZero(token) }) {
//            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Issue::class.java)
//
//            override fun verify(tx: TransactionForContract,
//                                inputs: List<State>,
//                                outputs: List<State>,
//                                commands: List<AuthenticatedObject<Commands>>,
//                                groupingKey: Issued<Terms>?): Set<Commands> {
//                val consumedCommands = super.verify(tx, inputs, outputs, commands, groupingKey)
//                commands.requireSingleCommand<Commands.Issue>()
//                return consumedCommands
//            }
//        }
//
//        class Move : Clause<State, Commands, Issued<Terms>>() {
//            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Move::class.java)
//
//            override fun verify(tx: TransactionForContract,
//                                inputs: List<State>,
//                                outputs: List<State>,
//                                commands: List<AuthenticatedObject<Commands>>,
//                                groupingKey: Issued<Terms>?): Set<Commands> {
//                val owningPubKeys2 = inputs.map { it.owner.owningKey }.toSet()
//                val keysThatSigned2 = arrayListOf<PublicKey>()
//                commands.forEach { keysThatSigned2.addAll(it.signers) }
//                //commands.forEach { it.signers.forEach { keysThatSigned2.plus(it) } }
//                //println(owningPubKeys2)
//                //println(keysThatSigned2)
//                //val command = commands.requireSingleCommand<Commands.Move>()
//                //val owningPubKeys = inputs.map { it.owner.owningKey }.toSet()
//                //val keysThatSigned = command.signers.toSet()
//                //println(owningPubKeys)
//                //println(keysThatSigned)
//                requireThat {
//                    "the owning keys are a subset of the signing keys" using keysThatSigned2.containsAll(owningPubKeys2)
//                    "there are no zero sized inputs" using inputs.none { it.quantity == 0 }
//                    // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
//                    // the input ignoring the owner field due to the grouping.
//                }
//                return setOf(commands.first().value)
//                //TODO: Refactor to allow multiple move securities comamnds in one transaction
//
//            }
//        }
//    }

    interface Commands : CommandData {
        data class Move(val contractHash: SecureHash? = null) :  Commands
        data class Issue(val nonce: Long = random63BitValue()) : Commands
    }

    /**
     * Returns a transaction that issues a stock, owned by the issuing parties key.
     */
    fun generateIssue(tx: TransactionBuilder,
                      issuance: PartyAndReference,
                      code: String,
                      quantity: Int,
                      owner: AbstractParty,
                      notary: Party) : TransactionBuilder {
        check(tx.inputStates().isEmpty())
        val state = TransactionState(data = SecurityClaim.State(issuance, owner, code, quantity), notary = notary, contract = "SecurityClaim")
        tx.addOutputState(state)
        tx.addCommand(Commands.Issue(), issuance.party.owningKey)
        return tx
    }

    fun generateMove(tx: TransactionBuilder, stock: StateAndRef<State>, newOwner : AbstractParty) {
        tx.addInputState(stock)
        tx.addOutputState(TransactionState(data = stock.state.data.copy(owner = newOwner), notary = stock.state.notary, contract = "SecurityClaim"))
        tx.addCommand(Commands.Move(), stock.state.data.owner.owningKey)
    }
    fun generateMoveCommand() = Commands.Move()
}