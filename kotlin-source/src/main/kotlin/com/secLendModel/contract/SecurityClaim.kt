package com.secLendModel.contract

import com.secLendModel.schema.SecuritySchemaV1
import net.corda.contracts.asset.OnLedgerAsset
import net.corda.contracts.clause.AbstractConserveAmount
import net.corda.contracts.clause.AbstractIssue
import net.corda.contracts.clause.NoZeroSizedOutputs
import net.corda.core.contracts.*
import net.corda.core.contracts.clauses.AllOf
import net.corda.core.contracts.clauses.FirstOf
import net.corda.core.contracts.clauses.GroupClauseVerifier
import net.corda.core.contracts.clauses.verifyClause
import net.corda.core.crypto.*
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
import java.math.BigInteger
import java.security.PublicKey
import java.util.*
import com.secLendModel.state.Security

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// SecurityClaim
//

// Just a fake program identifier for now. In a real system it could be, for instance, the hash of the program bytecode.
val SECURITY_PROGRAM_ID = SecurityClaim()

/**
 * A SecurityClaim transaction may split and merge money represented by a set of (issuer, depositRef) pairs, across multiple
 * input and output states. Imagine a Bitcoin transaction but in which all UTXOs had a colour
 * (a blend of issuer+depositRef) and you couldn't merge outputs of two colours together, but you COULD put them in
 * the same transaction.
 *
 * The goal of this design is to ensure that money can be withdrawn from the ledger easily: if you receive some money
 * via this contract, you always know where to go in order to extract it from the R3 ledger, no matter how many hands
 * it has passed through in the intervening time.
 *
 * At the same time, other contracts that just want money and don't care much who is currently holding it in their
 * vaults can ignore the issuer/depositRefs and just examine the amount fields.
 */
class SecurityClaim : OnLedgerAsset<Security, SecurityClaim.Commands, SecurityClaim.State>() {

    override val legalContractReference: SecureHash = SecureHash.sha256("https://www.big-book-of-banking-law.gov/SecurityClaim-claims.html")
    override fun extractCommands(commands: Collection<AuthenticatedObject<CommandData>>): List<AuthenticatedObject<SecurityClaim.Commands>>
            = commands.select<SecurityClaim.Commands>()

    interface Clauses {
        class Group : GroupClauseVerifier<State, Commands, Issued<Security>>(AllOf<State, Commands, Issued<Security>>(
                NoZeroSizedOutputs<State, Commands, Security>(),
                FirstOf<State, Commands, Issued<Security>>(
                        Issue(),
                        ConserveAmount())
        )
        ) {
            override fun groupStates(tx: TransactionForContract): List<TransactionForContract.InOutGroup<State, Issued<Security>>>
                    = tx.groupStates<State, Issued<Security>> { it.amount.token }
        }

        class Issue : AbstractIssue<State, Commands, Security>(
                sum = { sumSecurityClaim() },
                sumOrZero = { sumSecurityClaimOrZero(it) }
        ) {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Issue::class.java)
        }

        @CordaSerializable
        class ConserveAmount : AbstractConserveAmount<State, Commands, Security>()
    }

    /** A state representing a Security claim against some party */
    data class State(
            override val amount: Amount<Issued<Security>>,
            override val owner: PublicKey
    ) : FungibleAsset<Security>, QueryableState {
        constructor(deposit: PartyAndReference, amount: Amount<Security>, owner: PublicKey)
                : this(Amount(amount.quantity, Issued(deposit, amount.token)), owner)

        override val exitKeys = setOf(owner, amount.token.issuer.party.owningKey)
        override val contract = SECURITY_PROGRAM_ID
        override val participants = listOf(owner)

        override fun move(newAmount: Amount<Issued<Security>>, newOwner: PublicKey): FungibleAsset<Security>
                = copy(amount = amount.copy(newAmount.quantity), owner = newOwner)

        override fun toString() = "${amount.quantity} shares in ${amount.token.product.code} owned by $owner)"

        override fun withNewOwner(newOwner: PublicKey) = Pair(Commands.Move(), copy(owner = newOwner))

        /** Object Relational Mapping support. */
        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is SecuritySchemaV1 -> SecuritySchemaV1.PersistentSecurityState(
                        owner = this.owner.toBase58String(),
                        companyName = this.amount.token.product.displayName,
                        code = this.amount.token.product.code,
                        issuerParty = this.amount.token.issuer.party.owningKey.toBase58String(),
                        issuerRef = this.amount.token.issuer.reference.bytes
                )
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }

        /** Object Relational Mapping support. */
        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SecuritySchemaV1)
    }

    // Just for grouping
    interface Commands : FungibleAsset.Commands {
        /**
         * A command stating that money has been moved, optionally to fulfil another contract.
         *
         * @param contractHash the contract this move is for the attention of. Only that contract's verify function
         * should take the moved states into account when considering whether it is valid. Typically this will be
         * null.
         */
        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands

        /**
         * Allows new SecurityClaim states to be issued into existence: the nonce ("number used once") ensures the transaction
         * has a unique ID even when there are no inputs.
         */
        data class Issue(override val nonce: Long = newSecureRandom().nextLong()) : FungibleAsset.Commands.Issue, Commands

        /**
         * A command stating that money has been withdrawn from the shared ledger and is now accounted for
         * in some other way.
         */
        data class Exit(override val amount: Amount<Issued<Security>>) : Commands, FungibleAsset.Commands.Exit<Security>
    }

    /**
     * Puts together an issuance transaction from the given template, that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, tokenDef: Issued<Security>, pennies: Long, owner: PublicKey, notary: Party)
            = generateIssue(tx, Amount(pennies, tokenDef), owner, notary)

    /**
     * Puts together an issuance transaction for the specified amount that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, amount: Amount<Issued<Security>>, owner: PublicKey, notary: Party)
            = generateIssue(tx, TransactionState(State(amount, owner), notary), generateIssueCommand())

    override fun deriveState(txState: TransactionState<State>, amount: Amount<Issued<Security>>, owner: PublicKey)
            = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

    override fun generateExitCommand(amount: Amount<Issued<Security>>) = Commands.Exit(amount)
    override fun generateIssueCommand() = Commands.Issue()
    override fun generateMoveCommand() = Commands.Move()

    override fun verify(tx: TransactionForContract)
            = verifyClause(tx, Clauses.Group(), extractCommands(tx.commands))
}

// Small DSL extensions.

/**
 * Sums the SecurityClaim states in the list belonging to a single owner, throwing an exception
 * if there are none, or if any of the SecurityClaim states cannot be added together (i.e. are
 * different currencies or issuers).
 */
fun Iterable<ContractState>.sumSecurityClaimBy(owner: PublicKey): Amount<Issued<Security>> = filterIsInstance<SecurityClaim.State>().filter { it.owner == owner }.map { it.amount }.sumOrThrow()

/**
 * Sums the SecurityClaim states in the list, throwing an exception if there are none, or if any of the SecurityClaim
 * states cannot be added together (i.e. are different currencies or issuers).
 */
fun Iterable<ContractState>.sumSecurityClaim(): Amount<Issued<Security>> = filterIsInstance<SecurityClaim.State>().map { it.amount }.sumOrThrow()

/** Sums the SecurityClaim states in the list, returning null if there are none. */
fun Iterable<ContractState>.sumSecurityClaimOrNull(): Amount<Issued<Security>>? = filterIsInstance<SecurityClaim.State>().map { it.amount }.sumOrNull()

/** Sums the SecurityClaim states in the list, returning zero of the given Security+issuer if there are none. */
fun Iterable<ContractState>.sumSecurityClaimOrZero(Security: Issued<Security>): Amount<Issued<Security>> {
    return filterIsInstance<SecurityClaim.State>().map { it.amount }.sumOrZero(Security)
}

fun SecurityClaim.State.ownedBy(owner: PublicKey) = copy(owner = owner)
fun SecurityClaim.State.issuedBy(party: AbstractParty) = copy(amount = Amount(amount.quantity, amount.token.copy(issuer = amount.token.issuer.copy(party = party.toAnonymous()))))
fun SecurityClaim.State.issuedBy(deposit: PartyAndReference) = copy(amount = Amount(amount.quantity, amount.token.copy(issuer = deposit)))
fun SecurityClaim.State.withDeposit(deposit: PartyAndReference): SecurityClaim.State = copy(amount = amount.copy(token = amount.token.copy(issuer = deposit)))

infix fun SecurityClaim.State.`owned by`(owner: PublicKey) = ownedBy(owner)
infix fun SecurityClaim.State.`issued by`(party: AbstractParty) = issuedBy(party)
infix fun SecurityClaim.State.`issued by`(deposit: PartyAndReference) = issuedBy(deposit)
infix fun SecurityClaim.State.`with deposit`(deposit: PartyAndReference): SecurityClaim.State = withDeposit(deposit)

// Unit testing helpers. These could go in a separate file but it's hardly worth it for just a few functions.

/** A randomly generated key. */
val DUMMY_SECURITYCLAIM_ISSUER_KEY by lazy { entropyToKeyPair(BigInteger.valueOf(10)) }
/** A dummy, randomly generated issuer party by the name of "Snake Oil Issuer" */
val DUMMY_SECURITYCLAIM_ISSUER by lazy { Party("CN=Snake Oil Issuer,O=R3,OU=corda,L=London,C=UK", DUMMY_SECURITYCLAIM_ISSUER_KEY.public).ref(1) }
///** An extension property that lets you write 100.DOLLARS.SecurityClaim */
//val Amount<Security>.SecurityClaim: SecurityClaim.State get() = SecurityClaim.State(Amount(quantity, Issued(DUMMY_SECURITYCLAIM_ISSUER, token)), NullPublicKey)
///** An extension property that lets you get a SecurityClaim state from an issued token, under the [NullPublicKey] */
//val Amount<Issued<Security>>.STATE: SecurityClaim.State get() = SecurityClaim.State(this, NullPublicKey)
