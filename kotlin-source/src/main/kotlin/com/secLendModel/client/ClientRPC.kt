package com.secLendModel.client

import com.google.common.net.HostAndPort
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import net.corda.client.rpc.CordaRPCClient
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
    }

    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: ClientRPC <node address>" }
        val nodeAddress = HostAndPort.fromString(args[0])
        val client = CordaRPCClient(nodeAddress)

        // Can be amended in the com.secLendModel.MainKt file.
        val proxy = client.start("user1", "test").proxy

        // Grab all signed transactions and all future signed transactions.
        val (transactions: List<SignedTransaction>, futureTransactions: Observable<SignedTransaction>) =
                proxy.verifiedTransactions()

    }
}
