package com.secLendModel.flow.oracle

import net.corda.core.node.services.ServiceType


//A Service Definition to pick out the stock prices oracle from the network map
object PriceType {
    val type = ServiceType.getServiceType("com.secLendModel", "prices_oracle")

}