package com.secLendModel.client

import com.google.common.net.HostAndPort
import com.secLendModel.contract.SecurityLoan
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import net.corda.core.messaging.CordaRPCOps
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.StateAndRef
import net.corda.core.messaging.RPCOps
import net.corda.core.utilities.NetworkHostAndPort
import org.slf4j.Logger
import rx.Observable

/**
 * Demonstration of how to use the CordaRPCClient to connect to a Corda Node and
 * stream some State data from the node.
 */
fun main(args: Array<String>) {
    ClientRPC().main(args)
}

private class ClientRPC {
    companion object {
        val logger: Logger = loggerFor<ClientRPC>()
        private fun logState(state: StateAndRef<SecurityLoan.State>) = logger.info("{}", state.state.data)
    }

    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: ClientRPC <node address>" }
        //val nodeAddress = HostAndPort.fromString(args[0])
        val nodeAddress = NetworkHostAndPort.parse(args[0])
        val client = CordaRPCClient(nodeAddress)
//        // Can be amended in the com.secLendModel.MainKt file.
//        val proxy = client.start("user1", "test").proxy

        //TODO: This is copied from the exampleCordapp which only seems to deal with IOUStates - this may need to be ported to deal with more than just secLoan states
        // Can be amended in the com.example.MainKt file.
        val proxy = client.start("user1", "test").proxy

        val (snapshot, updates) = proxy.vaultTrack(SecurityLoan.State::class.java)

        // Log the 'placed' IOU states and listen for new ones.
        snapshot.states.forEach { logState(it) }
        updates.toBlocking().subscribe { update ->
            update.produced.forEach { logState(it) }
        }
    }
}
