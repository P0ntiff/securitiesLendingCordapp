
/**
 * Created by raymondm on 12/07/2017.
 */

package com.secLendModel.schema

import com.secLendModel.contract.SecurityLoan
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * An object used to fully qualify the [SecurityLoanSchema] family name (i.e. independent of version).
 */
object SecurityLoanSchema

/**
 * First version of a securityLoan Schema based on the v1 security schema.
 */
object SecurityLoanSchemaV1 : MappedSchema(schemaFamily = SecurityLoanSchema.javaClass, version = 1, mappedTypes = listOf(PersistentSecurityState::class.java)) {
    @Entity
    @Table(name = "securityLoan_states")
    class PersistentSecurityState(
            @Column(name = "lender_key")
            var lender: String,

            @Column(name = "borrower_key")
            var borrower: String,

            @Column(name = "code", length = 3)
            var code: String,

            @Column(name = "quantity")
            var quantity: Int,

            //TODO: Ask what keys would be needed for our secLoan States
            //@Column(name = "issuer_key")
            //var issuerParty: String,

            //@Column(name = "issuer_ref")
            //var issuerRef: ByteArray,

            @Column(name = "price")
            var price: Int,

            @Column(name = "linearID")
            var id: String,

            @Column(name = "length")
            var length: Int,

            @Column(name = "margin")
            var margin: Int,

            @Column(name = "rebate")
            var rebate: Int
    ) : PersistentState()
}