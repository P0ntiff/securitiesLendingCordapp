package com.secLendModel.flow.oracle
import net.corda.nodeapi.internal.ServiceType


//A Service Definition to pick out the stock prices oracle from the network map

object PriceType {
        //val type : ServiceType = ServiceType.getServiceType("com.secLendModel.flow.oracle", "Oracle")
        val type: ServiceType = ServiceType.networkMap.getSubType("Oracle") //todo there will be an error caused by this
    }
