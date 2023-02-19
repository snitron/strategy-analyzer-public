package com.strat.analyzer.domain.api.entity

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class CreateOrderResponse(
    val qty: BigDecimal,
    @SerializedName("order_id")
    val orderId: String
    )
