package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import com.secLendModel.CODES
import com.secLendModel.CURRENCY
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import org.apache.commons.io.IOUtils
import java.io.PrintWriter
import java.time.LocalDateTime

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
            //STEP 1: Get appropriate msg and send it to syn
            val myIdentity = serviceHub.myInfo.legalIdentity
            SynIntegrationFlow.messageProcessor().getSynMessage(loanTerms, myIdentity)

            //STEP 2: Wait for Syn to respond, on yes continue the process, on no exit
            //SynIntegrationFlow.messageProcessor().readFromSyn()

            //STEP 3: Conduct the actual loan issuance and commit to ledger
            //subFlow(LoanIssuanceFlow.Initiator(loanTerms))

            return
        }

    }


    open class ExitLoan(val loanTerms: LoanTerms) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 1: Send msg to Syn to indicate this loan exit
            SynIntegrationFlow.messageProcessor().writeToSyn("Msg")

            //STEP 3: Wait for Syn to respond, on yes continue the process
            SynIntegrationFlow.messageProcessor().readFromSyn()

            //STEP 3: Conduct the actual loan Exit.
            subFlow(LoanIssuanceFlow.Initiator(loanTerms))
            return
        }

    }

    class messageProcessor() {
        fun getLoanTerms(synMessage: String) {

        }

        fun getSynMessage(loanTerms : LoanTerms, myIdentity: Party) {
            //FOR TESTING WRITE TO THIS FILE AND CAN COMPARE
            val seperator = "|"
            val defaultCurrency = "AUD"
            val writer = PrintWriter("examplesyn.txt")
            //Write the header
            writer.append("0|Activity|DBAUS|20160307||ACG|NEW||DB_Global1.csv|DBAUS\n")

            //generates a syn message from a specific set of loanTerms
            //Format of a syn message is txt file.
            writer.append("1"+seperator) //Record type default is one
            writer.append("20172010"+seperator) //Effective date//TODO: Loan terms should store the start date
            writer.append(""+seperator) //Maturity date or term date if the loan is fixed length //TODO Loan Terms should have a fixed length bool field
            writer.append(""+seperator) //Date of final repayment starts as blank, only present when fully repaid
            writer.append(""+seperator) //Security settlement date -> Only present if settled
            writer.append(""+seperator) //Cash settlement date -> blank if not yet settled or if non cash or cash DVP trade
            writer.append(loanTerms.quantity.toString()+seperator) //Active quantity
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Activity value
            writer.append(defaultCurrency+seperator) //Loan value currency code
            writer.append(loanTerms.stockPrice.quantity.toString()+seperator) //Activity price
            writer.append("12345"+seperator) //TODO: This should probably be the loans unique ID but we can only get that once the loan is created -> should we create loan before this stage
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Market value of loan in market currency //TODO: What is unit of quotation (guessing this is local currency)
            writer.append("PL"+seperator) //Activity type -> currently is pending trade, after DVP on Corda this is updated
            writer.append("0"+seperator) //Activity loan rate -> //TODO Should add this loanRate/Fee term to the loanTerms
            writer.append("B"+seperator) //TODO: What is posting transaction type
            writer.append("0"+seperator) //Minimum fee
            writer.append(defaultCurrency+seperator) //Minimum fee currency
            writer.append(((loanTerms.margin+1) * 100).toString()+seperator) //Required margin (as a percent It seems)
            writer.append("0"+seperator) //Cash prepayment rate
            writer.append("20172010"+seperator) //Trade date //TODO: Same as date before, need to store this in loanTerms
            writer.append("20172010"+seperator) //Security settlement due date
            writer.append("CHESS"+seperator) //Security settlement mode
            writer.append("20172010"+seperator) //Cash settlement due date
            writer.append("WIRE"+seperator) //Cash settlement mode
            writer.append("E"+seperator) //Security main code type -> A-Sedol, B-ISIN, C-Cusip,D-Quick, E-Ticker, F-In-House cross reference //TODO: Okay to use ticket here?
            writer.append(loanTerms.code+seperator) //Security main code
            writer.append(loanTerms.code+seperator) //Security ticket (in this case same as main code)
            writer.append(loanTerms.code+seperator) //Security in house (in this case still the same)
            writer.append("AU000000GBT8"+seperator) //Security ISIN Code //TODO: Make a list of this where we store our codes and have a function to retrieve ISN from code. Currenyly hardcoding GBT
            writer.append(""+seperator) //Security quick code //TODO Whats this
            writer.append(""+seperator) //Security SEDOL code //TODO Whats this
            writer.append(""+seperator) //Security CUSPID code //TODO Whats this
            writer.append(""+seperator) //Security pricing identifier code //TODO Whats this
            writer.append("COM"+seperator) //Security class -> using common but not sure what the other classes are
            writer.append("N"+seperator) //Security bond indicator -> no as we are using regular securities at this point
            writer.append("GBST Holdings Ltd"+seperator)  //TODO: May need a code to name function as well
            writer.append("GBST Holdings Ltd"+seperator) //Security Issue name
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
            writer.append("20172010"+seperator) // Activity input date -> entry date of activity. Same todo as other date stuff
            writer.append(""+seperator) // Fund or cost centre major
            writer.append("23:45:42"+seperator) // Acitivty time relative to activity input date
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
            writer.append("AUDWIRE"+seperator) //Cash clearer code TODO this is defaulted from the example issue
            writer.append(""+seperator) //Cash clearer switft BIC
            writer.append(""+seperator) //Cash clearer account num
            writer.append(""+seperator) //cash clearer sub account
            writer.append(""+seperator) //cash clearer account ref
            writer.append(""+seperator) //cash clearer contract
            writer.append(""+seperator) //Cash clearer name
            writer.append(""+seperator) //Security clearer code
            writer.append(""+seperator) //Security clearer swift BIC
            writer.append(""+seperator) //Security clearer account number
            writer.append(""+seperator) //Security clearer sub-account
            writer.append(""+seperator) //Security clearer account reference
            writer.append(""+seperator) //Security clearer contact
            writer.append(""+seperator) //Security clearer name
            writer.append(""+seperator) //CL cash clearer code
            writer.append(""+seperator) //CL cash clearer swift BIC
            writer.append(""+seperator) //CL Cash clearer account number
            writer.append(""+seperator) // CL Cash clearer sub-account
            writer.append(""+seperator) //CL cash clearer account reference
            writer.append(""+seperator) //CL cash clearer contact
            writer.append(""+seperator) //CL cash clearer name
            writer.append(""+seperator) //CL security clearer code
            writer.append(""+seperator) //CL security clearer swift BIC
            writer.append(""+seperator) //CL security clearer swift BIC
            writer.append(""+seperator) //CL security clearer account number
            writer.append(""+seperator) //CL security clearer sub-account
            writer.append(""+seperator) //CL security clearer account reference
            writer.append(""+seperator) //CL security clearer contact
            writer.append(""+seperator) //CL security clearer name
            writer.append(loanTerms.rebate.toString()+seperator) //Current trade rate, fee or a rebate
            writer.append((loanTerms.quantity * loanTerms.stockPrice.quantity).toString()+seperator) //Initial loan value
            writer.append(loanTerms.quantity.toString()+seperator) //Initial loan quantity
            writer.append("N"+seperator) //Recalled indicator
            writer.append("N"+seperator) //Cash pool settlment
            writer.append("KNIGPET"+seperator) //User ID originator of the activity from the SB+ userid todo what is this actually, in example they use KNIGPET
            writer.append(""+seperator) //Authorization user ID
            writer.append("20172010"+seperator) //Authorization date todo same as other dates
            writer.append("23:45:42"+seperator) //Authorization time todo same as other time
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
            writer.append(""+seperator) //crest dbv consideration
            writer.append(""+seperator) //counterparty security clearer address line 1
            writer.append(""+seperator) //counterparty security clearer address line 2
            writer.append(""+seperator) //counterparty cash clearer address line 1
            writer.append(""+seperator) //counterparty cash clearer address line 2
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)
            writer.append(""+seperator)


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
