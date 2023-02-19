package com.strat.analyzer.db.deal

import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.Symbol
import java.math.BigDecimal
import java.math.RoundingMode

data class Deal(
    var amount: BigDecimal,
    var price: BigDecimal,
    var time: Long = System.currentTimeMillis(),
    var active: Boolean = true,
    var symbol: Symbol,
    var interval: CandleInterval,
    val side: Side
) {
    init {
        amount.setScale(8, RoundingMode.CEILING)
        price.setScale(8, RoundingMode.CEILING)
    }
}

enum class Side(val bool: Boolean) {
    LONG(true), SHORT(false);

    companion object {
        fun valueOfBoolSide(side: Boolean): Side = if (side) LONG else SHORT
    }
}