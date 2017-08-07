package com.secLendModel.flow.oracle

import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.CordaSerializable


//A Service Definition to pick out the stock prices oracle from the network map

object PriceType {
        val type : ServiceType = ServiceType.getServiceType("com.secLendModel.flow.oracle", "Oracle")
    }
