
/**
 * Created by raymondm on 12/07/2017.
 */

package com.secLendModel.schema

import com.secLendModel.contract.SecurityLoan
import net.corda.core.contracts.Amount
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.math.BigDecimal
import java.util.*
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

            @Column(name = "price")
            var price: Int,

            @Column(name = "current_price")
            var current_price: Int,

            @Column(name = "linearID")
            var id: String,

            @Column(name = "lengthOfLoan")
            var length: Int,

            @Column(name = "margin")
            var margin: Double,

            @Column(name = "rebate")
            var rebate: Double
    ) : PersistentState()
}