package com.secLendModel.api

import net.corda.core.messaging.CordaRPCOps
import javax.ws.rs.GET
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// This API is accessible from /api/secLendModel. The endpoint paths specified below are relative to it.
@Path("secLendModel")
class HttpApi(val services: CordaRPCOps) {
    /**
     * Accessible at /api/secLendModel/cordappGetEndpoint.
     */
    @GET
    @Path("cordappGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.accepted().entity("Cordapp GET endpoint.").build()
    }

    /**
     * Accessible at /api/secLendModel/cordappPutEndpoint.
     */
    @PUT
    @Path("cordappPutEndpoint")
    fun templatePutEndpoint(payload: Any): Response {
        return Response.accepted().entity("Cordapp PUT endpoint.").build()
    }
}