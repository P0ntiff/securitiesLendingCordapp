package com.secLendModel

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * An object used to fully qualify the [CashSchema] family name (i.e. independent of version).
 */
object SecuritySchema

/**
 * First version of a cash contract ORM schema that maps all fields of the [Cash] contract state as it stood
 * at the time of writing.
 */
object SecuritySchemaV1 : MappedSchema(schemaFamily = SecuritySchema.javaClass, version = 1, mappedTypes = listOf(PersistentSecurityState::class.java)) {
    @Entity
    @Table(name = "securityClaim_states")
    class PersistentSecurityState(
            @Column(name = "owner_key")
            var owner: String,

            @Column(name = "company_name")
            var companyName: String,

            @Column(name = "code", length = 3)
            var code: String,

            @Column(name = "issuer_key")
            var issuerParty: String,

            @Column(name = "issuer_ref")
            var issuerRef: ByteArray
    ) : PersistentState()
}
