package com.secLendModel.flow.securitiesLending

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import org.apache.commons.io.IOUtils

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
    open class IssueLoan(val loanTerms: LoanTerms) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            //STEP 1: Get appropriate msg and send it to syn
            SynIntegrationFlow.messageProcessor().getSynMessage(loanTerms)

            //STEP 2: Wait for Syn to respond, on yes continue the process, on no exit
            SynIntegrationFlow.messageProcessor().readFromSyn()

            //STEP 3: Conduct the actual loan issuance and commit to ledger
            subFlow(LoanIssuanceFlow.Initiator(loanTerms))

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

        fun getSynMessage(loanTerms : LoanTerms) {
            //generates a syn message from a specific set of loanTerms
            //Format of a syn message is ????
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
     */

}
