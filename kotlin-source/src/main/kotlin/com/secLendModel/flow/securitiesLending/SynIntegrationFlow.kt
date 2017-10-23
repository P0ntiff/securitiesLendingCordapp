package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CODES
import com.secLendModel.ISIN
import com.secLendModel.STOCKS
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.location
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import org.apache.commons.io.IOUtils
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Created by raymondm on 16/10/2017.
 *
 * This Syn Integration flow is used to integrate the Cordapp with the Syn system. It allows the cordapp to generate
 * loan txns and then send this request off to syn - who will do the actual transfer of assets. Once this is processed
 * this flow will commit the txn to the corda ledger. Essentially mirroring the real world asset transfer.
 */

object SynIntegrationFlow {
    @StartableByRPC
    @InitiatingFlow
    open class SynIssueLoan(val loanTerms: LoanTerms) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 1: Generate the file to indicate loan issuance
            val myIdentity = serviceHub.myInfo.legalIdentity
            val time = LocalDateTime.now()
            //TODO check this but because of some fields in the syn file, the loan needs to be sent on corda first.
            //todo other option is generate a loanID here, then if syn accepts the loan we generate a loan with a specific ID? Could work.
            val loanID : UniqueIdentifier = subFlow(LoanIssuanceFlow.Initiator(loanTerms))
            SynIntegrationFlow.messageProcessor().getSynMessageIssue(loanTerms, myIdentity, time, loanID)

            //STEP 2: Wait for Syn to respond, on yes continue the process, on no exit
            //SynIntegrationFlow.messageProcessor().readFromSyn()

            //STEP 3: Conduct the actual loan issuance and commit to ledger
            //subFlow(LoanIssuanceFlow.Initiator(loanTerms))

            return
        }

    }


    open class ExitLoan(val loanTerms: LoanTerms, val LoanID : UniqueIdentifier) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 1: Generate the file to indicate loan exit
            val myIdentity = serviceHub.myInfo.legalIdentity
            val time = LocalDateTime.now()
            SynIntegrationFlow.messageProcessor().getSynMessageExit(loanTerms, myIdentity, time, LoanID)

            //STEP 3: Wait for Syn to respond, on yes continue the process
            SynIntegrationFlow.messageProcessor().readFromSyn()

            //STEP 3: Conduct the actual loan Exit.
            return
        }

    }

    class messageProcessor() {

        fun codeToString(code: String) : String {
            val index = CODES.indexOf(code)
            if (index != -1 && index < STOCKS.size) {
                return STOCKS[index]
            } else {
                return "Invalid Code"
            }
        }

        fun codeToISIN(code: String) : String {
            val index = CODES.indexOf(code)
            if (index != -1 && index < ISIN.size) {
                return ISIN[index]
            } else {
                return "Invalid Code"
            }
        }


        fun getLoanTerms(synMessage: String) {

        }

        fun getSynMessageIssue(loanTerms : LoanTerms, myIdentity: Party, time: LocalDateTime, LoanID : UniqueIdentifier) {
            //FOR TESTING WRITE TO THIS FILE AND CAN COMPARE
            val seperator = "|"
            val defaultCurrency = "AUD"
            //dat file format required by syn
            val writer = PrintWriter("examplesyn.dat")
            //Write the header
            writer.append("0|Activity|DBAUS|20160307||ACG|NEW||DB_Global1.csv|DBAUS\n")
            val dateString = time.year.toString()+""+time.dayOfMonth.toString()+""+time.monthValue.toString()
            val timeString = time.format(DateTimeFormatter.ISO_TIME).toString()
            println(dateString)
            println(timeString)
            //generates a syn message from a specific set of loanTerms
            //Format of a syn message is txt file.
            writer.append("1"+seperator) //Record type default is one
            writer.append(dateString+seperator) //Effective date TODO Loan could potentially store the start date, probably not needed
            writer.append(""+seperator) //Maturity date or term date if the loan is fixed length //TODO Loan Terms should have a fixed length bool field or we just say none of thme are fixed length?
            writer.append(""+seperator) //Date of final repayment starts as blank, only present when fully repaid
            writer.append(""+seperator) //Security settlement date -> Only present if settled
            writer.append(""+seperator) //Cash settlement date -> blank if not yet settled or if non cash or cash DVP trade
            writer.append(loanTerms.quantity.toString()+seperator) //Active quantity
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Activity value
            writer.append(defaultCurrency+seperator) //Loan value currency code
            writer.append(loanTerms.stockPrice.quantity.toString()+seperator) //Activity price
            writer.append(LoanID.toString()+seperator) //ID for this loan -> note this means the loan is issued before these details are sent to syn, if syn rejects could be some problems
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Market value of loan in market currency //TODO: What is unit of quotation (guessing this is local currency)
            writer.append("PL"+seperator) //Activity type -> currently is pending trade, after DVP on Corda this is updated
            writer.append("0"+seperator) //Activity loan rate -> //TODO Should add this loanRate/Fee term to the loanTerms
            writer.append("B"+seperator) //TODO: What is posting transaction type
            writer.append("0"+seperator) //Minimum fee
            writer.append(defaultCurrency+seperator) //Minimum fee currency
            writer.append(((loanTerms.margin+1) * 100).toString()+seperator) //Required margin (as a percent It seems)
            writer.append("0"+seperator) //Cash prepayment rate
            writer.append(dateString+seperator) //Trade date
            writer.append(dateString+seperator) //Security settlement due date
            writer.append("CHESS"+seperator) //Security settlement mode
            writer.append(dateString+seperator) //Cash settlement due date
            writer.append("WIRE"+seperator) //Cash settlement mode
            writer.append("E"+seperator) //Security main code type -> A-Sedol, B-ISIN, C-Cusip,D-Quick, E-Ticker, F-In-House cross reference //TODO: Okay to use ticket here?
            writer.append(loanTerms.code+seperator) //Security main code
            writer.append(loanTerms.code+seperator) //Security ticket (in this case same as main code)
            writer.append(loanTerms.code+seperator) //Security in house (in this case still the same)
            writer.append(codeToISIN(loanTerms.code)+seperator) //Security ISIN Code
            writer.append(""+seperator) //Security quick code //TODO Whats this
            writer.append(""+seperator) //Security SEDOL code //TODO Whats this
            writer.append(""+seperator) //Security CUSPID code //TODO Whats this
            writer.append(""+seperator) //Security pricing identifier code //TODO Whats this
            writer.append("COM"+seperator) //Security class -> using common but not sure what the other classes are
            writer.append("N"+seperator) //Security bond indicator -> no as we are using regular securities at this point
            writer.append(codeToString(loanTerms.code)+seperator)  //Security company name
            writer.append(codeToString(loanTerms.code)+seperator) //Security Issue name
            writer.append(defaultCurrency+seperator)
            writer.append("0"+seperator) // Accured Interest
            writer.append(""+seperator) //Internal comment
            writer.append(""+seperator) //External comment
            writer.append("KNIGPET"+seperator) //Dealer identifier //TODO What is this -> have copied from the example one for now
            writer.append("N"+seperator) //Ammenedment
            writer.append(((loanTerms.margin+1) * 100).toString()+seperator) //Net dividend percentage //TODO How do i calculate this? For now im just doing total percentage
            writer.append("0"+seperator) // Overseas tax percentage
            writer.append("0"+seperator) // Domestic tax percentage
            writer.append(""+seperator) // Fund or cost centre identifier
            writer.append(""+seperator) // Fund or cost cetnre cross reference code
            writer.append(dateString+seperator) // Activity input date -> entry date of activity. TODO Make sure activity input date can be the same as effective date (aka this instance of time)
            writer.append(""+seperator) // Fund or cost centre major
            writer.append(timeString+seperator) // Acitivty time relative to activity input date
            writer.append(""+seperator) //Finder code
            writer.append("0"+seperator) //Fomder fee rate
            writer.append(LoanID.toString()+seperator) //System generated unique identifier -> currently using the loans unique ID in corda
            writer.append("T"+seperator) // Trade / Collateral indicator
            writer.append(""+seperator) // Link reference //TODO Whats this
            if (loanTerms.collateralType == "Cash") {
                writer.append("C"+seperator) //Collateral type C = cash, N = non cash
            } else {
                writer.append("N"+seperator) //Collateral type C = cash, N = non cash
            }
            if (loanTerms.borrower == myIdentity) {
                writer.append("B"+seperator) // Borrow/Loan indicator -> B is us borrowing
            } else {
                writer.append("L"+seperator) // Borrow/Loan indicator -> L is us lending
            }
            writer.append("D"+seperator) //Debit/Credit indicator -> new loan is a debit, and a return is a credit
            writer.append(""+seperator) // User defined table entered at Trade input //TODO What should i do here
            writer.append("Y"+seperator) //Callable indicator -> can this be called over recodrd date
            writer.append("Y"+seperator) //DvP indicator
            writer.append("F"+seperator) //First/Last day indicator -> Interest payment calculation type F-First Day, L-Last Day, B-Both, FS-First SEN, LS-Last SEN, BS-Both SEN.  ‘S’ indicates SEN rate todo
            writer.append(""+seperator) //Fail code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.toString()+seperator) //Counter party name
            writer.append(""+seperator) //Counter party swift code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party cross reference
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party major code
            writer.append(""+seperator) //Own Switf BIC code
            writer.append("AUDWIRE"+seperator) //Cash clearer code TODO this is defaulted from the example issue, same as below
            writer.append(""+seperator) //Cash clearer switft BIC
            writer.append(""+seperator) //Cash clearer account num
            writer.append(""+seperator) //cash clearer sub account
            writer.append(""+seperator) //cash clearer account ref
            writer.append("Stephanie Wright"+seperator) //cash clearer contact
            writer.append("DEUTSCHE BANK AG"+seperator) //Cash clearer name
            writer.append("AUD"+seperator) //Security clearer code
            writer.append(""+seperator) //Security clearer swift BIC
            writer.append(""+seperator) //Security clearer account number
            writer.append(""+seperator) //Security clearer sub-account
            writer.append(""+seperator) //Security clearer account reference
            writer.append("Stephanie Wright"+seperator) //Security clearer contact
            writer.append("DEUTSCHE BANK AG"+seperator) //Security clearer name
            writer.append("AUD"+seperator) //CL cash clearer code
            writer.append(""+seperator) //CL cash clearer swift BIC
            writer.append("6102473"+seperator) //CL Cash clearer account number
            writer.append(""+seperator) // CL Cash clearer sub-account
            writer.append(""+seperator) //CL cash clearer account reference
            writer.append("KATE DALE"+seperator) //CL cash clearer contact
            writer.append("JP MORGAN CHASE BANK (SYDNEY BRANCH)"+seperator) //CL cash clearer name
            writer.append("AUD"+seperator) //CL security clearer code
            writer.append(""+seperator) //CL security clearer swift BIC
            writer.append("6102473"+seperator) //CL security clearer account number
            writer.append(""+seperator) //CL security clearer sub-account
            writer.append(""+seperator) //CL security clearer account reference
            writer.append("KATE DALE"+seperator) //CL security clearer contact
            writer.append("JP MORGAN CHASE BANK (SYDNEY BRANCH)"+seperator) //CL security clearer name
            writer.append(loanTerms.rebate.toString()+seperator) //Current trade rate, fee or a rebate
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Initial loan value
            writer.append(loanTerms.quantity.toString()+seperator) //Initial loan quantity
            writer.append("N"+seperator) //Recalled indicator
            writer.append("N"+seperator) //Cash pool settlment
            writer.append("KNIGPET"+seperator) //User ID originator of the activity from the SB+ userid todo what is this actually, in example they use KNIGPET
            writer.append(""+seperator) //Authorization user ID
            writer.append(dateString+seperator) //Authorization date
            writer.append(timeString+seperator) //Authorization time
            writer.append(""+seperator) //Final return flag todo whats this
            writer.append("N"+seperator) //Own counterparty security indicator -> Y if instruction details are overrideen on trade or return
            writer.append("N"+seperator) //Own counterparty cash indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append("N"+seperator) //counterparty security indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append("N"+seperator) //counterparty cash indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append(""+seperator) //Intercompany ref -> only present for trades set up through intercompany processing
            writer.append(""+seperator) //Location code -> Lenders Module only.  Populated for fund level activities and for principal borrow and principal collateral items
            writer.append(""+seperator) //Location cross ref -> Lenders Module only.  Populated for fund level activities and for principal borrow and principal collateral items
            writer.append("N"+seperator) //Repo type trade ->Y/N.  Set to ‘Y’ if trade was entered through Repo Trade Input or is a US Dollar Repo Trade.  Set to ’N’ if not Rep type trade
            writer.append("N"+seperator) //Monthly billing -> Y/N.  Only used if Repo Type Trade = ‘Y’.  Set to ‘Y’ if the Repo trade is to be billed monthly.  Leave blank for non-repo trades.
            writer.append("N"+seperator) //System settled -> Y/N.  Only set to ’Y’ if the Settlement Activity has been created automatically as a result of Maturity options processing.  Only applicable to SL, CL, SR, CR, RI
            writer.append("0"+seperator) //Total accural -> Zero if Repo Type Trade = ‘N’ or if no Term Date.  If Return Activity then this is the accrued interest associated with the Return.  Zero if no associated interest.  Can be –ve.
            writer.append("N"+seperator) //Associated transaction -> Y/N.  Set to ‘Y’ for a ‘PR’ or ‘XPR’ which has an associated ‘PI’ or ‘XPI’.  Also set to ‘Y’ for a ‘PI’ or ‘XPI’ which has an associated ‘PR’ or ‘XPR’/
            writer.append("0"+seperator) //Coupon accural days TODO what is this
            writer.append("N"+seperator) //Security settlement suppressed -> Y/N.  Only set to ‘Y’ if ‘N’ entered to Settlement instructions flag on Trade Authorisation screen, only applicable to PL, XPL, PR, and XPR.
            writer.append(""+seperator) //Crest own data participant identifier
            writer.append(""+seperator) //Crest counterparty data participant identifier
            writer.append(""+seperator) //Crest account identifier
            writer.append(""+seperator) //Creat agent indicator
            writer.append(""+seperator) //Crest trade system or origin
            writer.append(""+seperator) //Crest NC condition
            writer.append(""+seperator) //Crest bargin conditions
            writer.append("0"+seperator) //Crest priority
            writer.append(""+seperator) //Crest participant country of residence
            writer.append(""+seperator) //crest cash movement type
            writer.append(""+seperator) //crest payment type
            writer.append(""+seperator) //crest security category identifier
            writer.append(""+seperator) //crest concentration limit
            writer.append("0"+seperator) //crest dbv consideration
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty security clearer address line 1 //TODO:  Check what to do here for these 4
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty security clearer address line 2
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty cash clearer address line 1
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty cash clearer address line 2
            writer.append(""+seperator) //From repo trade ref
            writer.append(""+seperator) //to repo trade ref
            writer.append("0"+seperator) //new price after mark -> copied from example,
            writer.append("1"+seperator) //num of fund/locations or cost centres
            writer.append("0"+seperator) //num of this fund/location or cost cetnre
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //cash activity quantity
            writer.append(""+seperator) //dividend ex date
            writer.append(""+seperator) //dividend record date
            writer.append(""+seperator) //dividend payment date
            writer.append(""+seperator) //dividend currency
            writer.append("0"+seperator) //gross rate per share
            writer.append("0"+seperator) //net rate per share
            writer.append("0"+seperator) //coupon rate for bonds
            writer.append("0"+seperator) //dividend claim value
            writer.append("0"+seperator) //overseas tax max
            writer.append("0"+seperator) //domestic tax max
            writer.append(""+seperator) //pay/receieve indicator
            writer.append(""+seperator) //dividend paid date
            writer.append("0"+seperator) //dividend paid amount
            writer.append(""+seperator) //dividend payment type
            writer.append("0"+seperator) //start clean price
            writer.append("0"+seperator) //start clean principle
            writer.append("0"+seperator) //start coupon accrual
            writer.append("0"+seperator) //end clean price
            writer.append("0"+seperator) //end clean principle
            writer.append("0"+seperator) //end coupon accrual
            writer.append("0"+seperator) //inclusive coupon payment amount
            writer.append("0"+seperator) //inclusive coupon re-investment interest
            writer.append("0"+seperator) //rolled accrual value
            writer.append(dateString+seperator) //original trade settlement due date -> due date of the initial trade //TODO: Date stuff again
            writer.append("0"+seperator) //cash pool value
            writer.append("Y"+seperator) //Instructions required Y/N
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //market value of loan in loan currency
            writer.append(""+seperator) //activity rate sign
            writer.append(""+seperator) //pre payment rate sign
            writer.append(""+seperator) //current rate sign
            writer.append(""+seperator) //finder fee rate sign
            writer.append(""+seperator) //Security class cross ref
            writer.append("N"+seperator) //Auto settled indicator
            writer.append("Y"+seperator) //mark trade indicator
            writer.append(""+seperator) //agency reference //TODO this
            writer.append("N"+seperator) //matched investment indicator
            writer.append("0"+seperator) //own security agency type -> 0-No Link, 1-Euroclear, 2-Cedel, 3-Kassenverein, 4-Citibank, 5-DTC, 6-Telex, 7-Polaris, 8-JP Morgan, 9-SWIFT, 10-Talisman, 11-Canadian Depository (CDS), 12-Debt Clearing System (DCS), 13-CGO (Central Gifts Office), 14-CREST, 15-Bank of New York (BONY), 16-Generic BULK instructions
            writer.append("0"+seperator) //own cash agency type -> same as above
            writer.append("105"+seperator) //non-cash collateral haircut percentage //TODO: This either needs to be added as a field to the loan or calculated from loan
            writer.append("DBAUS"+seperator) //own security clearer special instructions //TODO: Again this is just default copied from the example
            writer.append("DBAUS"+seperator) //own cash clearer special instructions
            writer.append(""+seperator) //counterparty security clearer special instructions
            writer.append(""+seperator) //counterparty cash clearer special instructions
            writer.append(loanTerms.stockPrice.quantity.toString()+seperator) //activity price to 7dp
            writer.append("0"+seperator) //new price after mark to 7dp -> price hasnt changed
            writer.append("T"+seperator) //activity file level -> Detail record level.  Can be T-Trade, F-Fund, C-Cost Centre
            writer.append("0"+seperator) //accrual paid
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //activity quantity to 2dp
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //initial quantity to 2dp
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //cash activity quantity to 2dp
            writer.append("AUS"+seperator) //security country of issue

            //Seems these two are on the last line
            writer.append("\n");
            writer.append("9"+seperator) //redenomination flag 1-Currency Only, 2-Currency and Quantity, 3-Quantity only TODO dont understand what this is or why the examples have 9 on there
            writer.append("1") //internal comments line 2 -> this seems to be the total num of txns to process in this one .dat file


            //Close writer when done
            writer.close()
        }

        //TODO this is currently the same as issue so need to change a few fields
        fun getSynMessageExit(loanTerms : LoanTerms, myIdentity: Party, time: LocalDateTime, LoanID : UniqueIdentifier) {
            //FOR TESTING WRITE TO THIS FILE AND CAN COMPARE
            val seperator = "|"
            val defaultCurrency = "AUD"
            //dat file format required by syn
            val writer = PrintWriter("examplesyn.dat")
            //Write the header
            writer.append("0|Activity|DBAUS|20160307||ACG|NEW||DB_Global1.csv|DBAUS\n")
            val dateString = time.year.toString()+""+time.dayOfMonth.toString()+""+time.monthValue.toString()
            val timeString = time.format(DateTimeFormatter.ISO_TIME).toString()
            println(dateString)
            println(timeString)
            //generates a syn message from a specific set of loanTerms
            //Format of a syn message is txt file.
            writer.append("1"+seperator) //Record type default is one
            writer.append(dateString+seperator) //Effective date TODO Loan could potentially store the start date, probably not needed
            writer.append(""+seperator) //Maturity date or term date if the loan is fixed length //TODO Loan Terms should have a fixed length bool field or we just say none of thme are fixed length?
            writer.append(""+seperator) //Date of final repayment starts as blank, only present when fully repaid
            writer.append(""+seperator) //Security settlement date -> Only present if settled
            writer.append(""+seperator) //Cash settlement date -> blank if not yet settled or if non cash or cash DVP trade
            writer.append(loanTerms.quantity.toString()+seperator) //Active quantity
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Activity value
            writer.append(defaultCurrency+seperator) //Loan value currency code
            writer.append(loanTerms.stockPrice.quantity.toString()+seperator) //Activity price
            writer.append(LoanID.toString()+seperator) //ID for this loan -> note this means the loan is issued before these details are sent to syn, if syn rejects could be some problems
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Market value of loan in market currency //TODO: What is unit of quotation (guessing this is local currency)
            writer.append("PL"+seperator) //Activity type -> currently is pending trade, after DVP on Corda this is updated
            writer.append("0"+seperator) //Activity loan rate -> //TODO Should add this loanRate/Fee term to the loanTerms
            writer.append("B"+seperator) //TODO: What is posting transaction type
            writer.append("0"+seperator) //Minimum fee
            writer.append(defaultCurrency+seperator) //Minimum fee currency
            writer.append(((loanTerms.margin+1) * 100).toString()+seperator) //Required margin (as a percent It seems)
            writer.append("0"+seperator) //Cash prepayment rate
            writer.append(dateString+seperator) //Trade date
            writer.append(dateString+seperator) //Security settlement due date
            writer.append("CHESS"+seperator) //Security settlement mode
            writer.append(dateString+seperator) //Cash settlement due date
            writer.append("WIRE"+seperator) //Cash settlement mode
            writer.append("E"+seperator) //Security main code type -> A-Sedol, B-ISIN, C-Cusip,D-Quick, E-Ticker, F-In-House cross reference //TODO: Okay to use ticket here?
            writer.append(loanTerms.code+seperator) //Security main code
            writer.append(loanTerms.code+seperator) //Security ticket (in this case same as main code)
            writer.append(loanTerms.code+seperator) //Security in house (in this case still the same)
            writer.append(codeToISIN(loanTerms.code)+seperator) //Security ISIN Code //TODO: Make a list of this where we store our codes and have a function to retrieve ISN from code. Currenyly hardcoding GBT
            writer.append(""+seperator) //Security quick code //TODO Whats this
            writer.append(""+seperator) //Security SEDOL code //TODO Whats this
            writer.append(""+seperator) //Security CUSPID code //TODO Whats this
            writer.append(""+seperator) //Security pricing identifier code //TODO Whats this
            writer.append("COM"+seperator) //Security class -> using common but not sure what the other classes are
            writer.append("N"+seperator) //Security bond indicator -> no as we are using regular securities at this point
            writer.append(codeToString(loanTerms.code)+seperator)  //Security company name
            writer.append(codeToString(loanTerms.code)+seperator) //Security Issue name
            writer.append(defaultCurrency+seperator)
            writer.append("0"+seperator) // Accured Interest
            writer.append(""+seperator) //Internal comment
            writer.append(""+seperator) //External comment
            writer.append("KNIGPET"+seperator) //Dealer identifier //TODO What is this -> have copied from the example one for now
            writer.append("N"+seperator) //Ammenedment
            writer.append(((loanTerms.margin+1) * 100).toString()+seperator) //Net dividend percentage //TODO How do i calculate this? For now im just doing total percentage
            writer.append("0"+seperator) // Overseas tax percentage
            writer.append("0"+seperator) // Domestic tax percentage
            writer.append(""+seperator) // Fund or cost centre identifier
            writer.append(""+seperator) // Fund or cost cetnre cross reference code
            writer.append(dateString+seperator) // Activity input date -> entry date of activity. TODO Make sure activity input date can be the same as effective date (aka this instance of time)
            writer.append(""+seperator) // Fund or cost centre major
            writer.append(timeString+seperator) // Acitivty time relative to activity input date
            writer.append(""+seperator) //Finder code
            writer.append("0"+seperator) //Fomder fee rate
            writer.append(loanTerms.toString().hashCode().toString()+seperator) //System generated unique identifier //TODO: How do i generate this? Maybe just hash something for now
            writer.append("T"+seperator) // Trade / Collateral indicator
            writer.append(""+seperator) // Link reference //TODO Whats this
            if (loanTerms.collateralType == "Cash") {
                writer.append("C"+seperator) //Collateral type C = cash, N = non cash
            } else {
                writer.append("N"+seperator) //Collateral type C = cash, N = non cash
            }
            if (loanTerms.borrower == myIdentity) {
                writer.append("B"+seperator) // Borrow/Loan indicator -> B is us borrowing
            } else {
                writer.append("L"+seperator) // Borrow/Loan indicator -> L is us lending
            }
            writer.append("D"+seperator) //Debit/Credit indicator -> new loan is a debit, and a return is a credit
            writer.append(""+seperator) // User defined table entered at Trade input //TODO What should i do here
            writer.append("Y"+seperator) //Callable indicator -> can this be called over recodrd date
            writer.append("Y"+seperator) //DvP indicator
            writer.append("F"+seperator) //First/Last day indicator -> Interest payment calculation type F-First Day, L-Last Day, B-Both, FS-First SEN, LS-Last SEN, BS-Both SEN.  ‘S’ indicates SEN rate todo
            writer.append(""+seperator) //Fail code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.toString()+seperator) //Counter party name
            writer.append(""+seperator) //Counter party swift code
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party cross reference
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).toString()+seperator) //Counter party major code
            writer.append(""+seperator) //Own Switf BIC code
            writer.append("AUDWIRE"+seperator) //Cash clearer code TODO this is defaulted from the example issue, same as below
            writer.append(""+seperator) //Cash clearer switft BIC
            writer.append(""+seperator) //Cash clearer account num
            writer.append(""+seperator) //cash clearer sub account
            writer.append(""+seperator) //cash clearer account ref
            writer.append("Stephanie Wright"+seperator) //cash clearer contact
            writer.append("DEUTSCHE BANK AG"+seperator) //Cash clearer name
            writer.append("AUD"+seperator) //Security clearer code
            writer.append(""+seperator) //Security clearer swift BIC
            writer.append(""+seperator) //Security clearer account number
            writer.append(""+seperator) //Security clearer sub-account
            writer.append(""+seperator) //Security clearer account reference
            writer.append("Stephanie Wright"+seperator) //Security clearer contact
            writer.append("DEUTSCHE BANK AG"+seperator) //Security clearer name
            writer.append("AUD"+seperator) //CL cash clearer code
            writer.append(""+seperator) //CL cash clearer swift BIC
            writer.append("6102473"+seperator) //CL Cash clearer account number
            writer.append(""+seperator) // CL Cash clearer sub-account
            writer.append(""+seperator) //CL cash clearer account reference
            writer.append("KATE DALE"+seperator) //CL cash clearer contact
            writer.append("JP MORGAN CHASE BANK (SYDNEY BRANCH)"+seperator) //CL cash clearer name
            writer.append("AUD"+seperator) //CL security clearer code
            writer.append(""+seperator) //CL security clearer swift BIC
            writer.append("6102473"+seperator) //CL security clearer account number
            writer.append(""+seperator) //CL security clearer sub-account
            writer.append(""+seperator) //CL security clearer account reference
            writer.append("KATE DALE"+seperator) //CL security clearer contact
            writer.append("JP MORGAN CHASE BANK (SYDNEY BRANCH)"+seperator) //CL security clearer name
            writer.append(loanTerms.rebate.toString()+seperator) //Current trade rate, fee or a rebate
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Initial loan value
            writer.append(loanTerms.quantity.toString()+seperator) //Initial loan quantity
            writer.append("N"+seperator) //Recalled indicator
            writer.append("N"+seperator) //Cash pool settlment
            writer.append("KNIGPET"+seperator) //User ID originator of the activity from the SB+ userid todo what is this actually, in example they use KNIGPET
            writer.append(""+seperator) //Authorization user ID
            writer.append(dateString+seperator) //Authorization date
            writer.append(timeString+seperator) //Authorization time
            writer.append(""+seperator) //Final return flag todo whats this
            writer.append("N"+seperator) //Own counterparty security indicator -> Y if instruction details are overrideen on trade or return
            writer.append("N"+seperator) //Own counterparty cash indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append("N"+seperator) //counterparty security indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append("N"+seperator) //counterparty cash indicator ‘Y’ if instruction details are overridden on Trade or Return
            writer.append(""+seperator) //Intercompany ref -> only present for trades set up through intercompany processing
            writer.append(""+seperator) //Location code -> Lenders Module only.  Populated for fund level activities and for principal borrow and principal collateral items
            writer.append(""+seperator) //Location cross ref -> Lenders Module only.  Populated for fund level activities and for principal borrow and principal collateral items
            writer.append("N"+seperator) //Repo type trade ->Y/N.  Set to ‘Y’ if trade was entered through Repo Trade Input or is a US Dollar Repo Trade.  Set to ’N’ if not Rep type trade
            writer.append("N"+seperator) //Monthly billing -> Y/N.  Only used if Repo Type Trade = ‘Y’.  Set to ‘Y’ if the Repo trade is to be billed monthly.  Leave blank for non-repo trades.
            writer.append("N"+seperator) //System settled -> Y/N.  Only set to ’Y’ if the Settlement Activity has been created automatically as a result of Maturity options processing.  Only applicable to SL, CL, SR, CR, RI
            writer.append("0"+seperator) //Total accural -> Zero if Repo Type Trade = ‘N’ or if no Term Date.  If Return Activity then this is the accrued interest associated with the Return.  Zero if no associated interest.  Can be –ve.
            writer.append("N"+seperator) //Associated transaction -> Y/N.  Set to ‘Y’ for a ‘PR’ or ‘XPR’ which has an associated ‘PI’ or ‘XPI’.  Also set to ‘Y’ for a ‘PI’ or ‘XPI’ which has an associated ‘PR’ or ‘XPR’/
            writer.append("0"+seperator) //Coupon accural days TODO what is this
            writer.append("N"+seperator) //Security settlement suppressed -> Y/N.  Only set to ‘Y’ if ‘N’ entered to Settlement instructions flag on Trade Authorisation screen, only applicable to PL, XPL, PR, and XPR.
            writer.append(""+seperator) //Crest own data participant identifier
            writer.append(""+seperator) //Crest counterparty data participant identifier
            writer.append(""+seperator) //Crest account identifier
            writer.append(""+seperator) //Creat agent indicator
            writer.append(""+seperator) //Crest trade system or origin
            writer.append(""+seperator) //Crest NC condition
            writer.append(""+seperator) //Crest bargin conditions
            writer.append("0"+seperator) //Crest priority
            writer.append(""+seperator) //Crest participant country of residence
            writer.append(""+seperator) //crest cash movement type
            writer.append(""+seperator) //crest payment type
            writer.append(""+seperator) //crest security category identifier
            writer.append(""+seperator) //crest concentration limit
            writer.append("0"+seperator) //crest dbv consideration
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty security clearer address line 1 //TODO:  Check what to do here for these 4
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty security clearer address line 2
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty cash clearer address line 1
            writer.append(LoanChecks.getCounterParty(loanTerms, myIdentity).name.location+seperator) //counterparty cash clearer address line 2
            writer.append(""+seperator) //From repo trade ref
            writer.append(""+seperator) //to repo trade ref
            writer.append("0"+seperator) //new price after mark -> copied from example,
            writer.append("1"+seperator) //num of fund/locations or cost centres
            writer.append("0"+seperator) //num of this fund/location or cost cetnre
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //cash activity quantity
            writer.append(""+seperator) //dividend ex date
            writer.append(""+seperator) //dividend record date
            writer.append(""+seperator) //dividend payment date
            writer.append(""+seperator) //dividend currency
            writer.append("0"+seperator) //gross rate per share
            writer.append("0"+seperator) //net rate per share
            writer.append("0"+seperator) //coupon rate for bonds
            writer.append("0"+seperator) //dividend claim value
            writer.append("0"+seperator) //overseas tax max
            writer.append("0"+seperator) //domestic tax max
            writer.append(""+seperator) //pay/receieve indicator
            writer.append(""+seperator) //dividend paid date
            writer.append("0"+seperator) //dividend paid amount
            writer.append(""+seperator) //dividend payment type
            writer.append("0"+seperator) //start clean price
            writer.append("0"+seperator) //start clean principle
            writer.append("0"+seperator) //start coupon accrual
            writer.append("0"+seperator) //end clean price
            writer.append("0"+seperator) //end clean principle
            writer.append("0"+seperator) //end coupon accrual
            writer.append("0"+seperator) //inclusive coupon payment amount
            writer.append("0"+seperator) //inclusive coupon re-investment interest
            writer.append("0"+seperator) //rolled accrual value
            writer.append(dateString+seperator) //original trade settlement due date -> due date of the initial trade //TODO: Date stuff again
            writer.append("0"+seperator) //cash pool value
            writer.append("Y"+seperator) //Instructions required Y/N
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //market value of loan in loan currency
            writer.append(""+seperator) //activity rate sign
            writer.append(""+seperator) //pre payment rate sign
            writer.append(""+seperator) //current rate sign
            writer.append(""+seperator) //finder fee rate sign
            writer.append(""+seperator) //Security class cross ref
            writer.append("N"+seperator) //Auto settled indicator
            writer.append("Y"+seperator) //mark trade indicator
            writer.append(""+seperator) //agency reference //TODO this
            writer.append("N"+seperator) //matched investment indicator
            writer.append("0"+seperator) //own security agency type -> 0-No Link, 1-Euroclear, 2-Cedel, 3-Kassenverein, 4-Citibank, 5-DTC, 6-Telex, 7-Polaris, 8-JP Morgan, 9-SWIFT, 10-Talisman, 11-Canadian Depository (CDS), 12-Debt Clearing System (DCS), 13-CGO (Central Gifts Office), 14-CREST, 15-Bank of New York (BONY), 16-Generic BULK instructions
            writer.append("0"+seperator) //own cash agency type -> same as above
            writer.append("105"+seperator) //non-cash collateral haircut percentage //TODO: This either needs to be added as a field to the loan or calculated from loan
            writer.append("DBAUS"+seperator) //own security clearer special instructions //TODO: Again this is just default copied from the example
            writer.append("DBAUS"+seperator) //own cash clearer special instructions
            writer.append(""+seperator) //counterparty security clearer special instructions
            writer.append(""+seperator) //counterparty cash clearer special instructions
            writer.append(loanTerms.stockPrice.quantity.toString()+seperator) //activity price to 7dp
            writer.append("0"+seperator) //new price after mark to 7dp -> price hasnt changed
            writer.append("T"+seperator) //activity file level -> Detail record level.  Can be T-Trade, F-Fund, C-Cost Centre
            writer.append("0"+seperator) //accrual paid
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //activity quantity to 2dp
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //initial quantity to 2dp
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //cash activity quantity to 2dp
            writer.append("AUS"+seperator) //security country of issue

            //Seems these two are on the last line
            writer.append("\n");
            writer.append("9"+seperator) //redenomination flag 1-Currency Only, 2-Currency and Quantity, 3-Quantity only TODO dont understand what this is
            writer.append("1") //internal comments line 2 -> this seems to be the total num of txns to process in this one .dat file


            //Close writer when done
            writer.close()
        }


        fun readFromSyn() {
            //Open the file as a string
            val fileContents = IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("example_prices.txt"), Charsets.UTF_8.name())

            //Read from file

            //Close file and return
        }

        fun writeToSyn(msg : String) {
            //Open the file

            //Write the msg

            //Close file and return
        }
    }

    /** Further development/Other possibly needed functions
     * Thinking all of these will be done in a class called SynMessageProcessing? Could be best way to make the cordapp
     * more modular.
     * getLoanTerms(synMessage : String)
     *     Takes in either a string/xml/message format from syn, and generates the appropriate loanTerms so that this
     *     loan can be processed
     *
     * getSynMessage(loanTerms)
     *     Takes in loan/Loan terms and generates the appropriate msg to be sent to syn to notify it of the txn that
     *     is goingto take place
     *
     * readFromSyn()
     *     Reads from the appropriate file (yet to be decided). Will usually just be either a yes/no msg on whether the
     *     txn is going ahead. If yes the flow then commits the txn to the ledger. Otherwise exits with a flow exception
     *
     * writeToSyn(msg)
     *     Writes a message to the appropriate file (Yet to be decided). msg will be a synMessage (i.e already formatted)
     *     to allow syn to figure out what loan it is processing.
     *
     * Doesnt seem like anything in the loanContract needs to be altered for this flow to work. Note we will only be
     * working with cash collateral and doing basic txns (issue and terminate) for the time being.
     *
     * Possible Problems/Issues/Thoughts
     *    - If syn doesnt respond promptly, we may end up busy waiting for a response before we commit to the ledger.
     *      To resolve this could do a few things. Easiest would be to have a timeout period and if we never receive a
     *      response we simply quit. Could also do multithreading, create a new thread to process and do the stuff, other
     *      thread simply exits (probably a more elegent solution, especially when coupled with the timeout).
     *
     *    - How do we communicate if there is a flow issue (i.e turns out we didnt have enough states to do this loan)
     *      Either we will need to pre check and try limit the chance a flow exception happens, or add another piece of
     *      communication (i.e Corda proposes txn, syn receives says yes, Corda attempts to process on ledger, says yes
     *      back on sucess, no on failure. Syn process txn and does same msg form - a bit more convaluted but assists in
     *      minimising errors and confirming that states are atomic between both Corda and Syn.)
     *
     *    - Some fields such as the currently used securities (GBT, CBA, RIO, NAB) may need to be altered to work with
     *      syn. Either that or will also need to have a converter tha converts between variables within the Cordapp and
     *      there txt representation for use within syn.
     *
     *    - Will be using IOUtils for file IO, similar to how it is used within the oracle. See documenatation for
     *      read and write methods.
     *
     * How this will actuall integrate with the cordapp.
     *    Create new button within GUI for issue. Once can be regular issue, one can be issue/syn.
     *
     *    When user issues or termiantes the share, instead of loanIssuanceFlow being called, SynItegrationFlow gets
     *    called. This sends off the required msg to syn, as well as processing the actual transaction on this node.
     *
     *
     * Notes for formatting Syn Documentation
     * - Corda acts as Global1 and is using the Global 1 File interface.
     * - The extracts from Global One are loaded into the following Syn~ directories - %SynHome%\feeds\G1S1\incoming or %SynHome%\feeds\G1S2\incoming
     */

}
