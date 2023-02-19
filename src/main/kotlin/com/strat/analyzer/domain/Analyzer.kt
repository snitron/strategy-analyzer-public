package com.strat.analyzer.domain

import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.db.deal.Deal
import com.strat.analyzer.db.deal.Side
import com.strat.analyzer.domain.api.ByBitAPI
import com.strat.analyzer.utils.Constants
import com.strat.analyzer.utils.startSuspended
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class Analyzer(
    val byBitAPI: ByBitAPI,
    private val constants: Constants
) {

    val deals = mutableMapOf<Symbol, Deal>()

    fun checkTradeSignal(tradeSignal: TradeSignal, seriesName: String, comment: String) {
        startSuspended {
            constants.adminMessageSender?.sendMessage(tradeSignal.let { tradeSignal ->
                "💸" + "Strategy Notification ${tradeSignal.strategyName} on the ${
                    seriesName.split(
                        " "
                    ).let { it[0] + " #" + it[1] }
                } ${if (tradeSignal.side) "LONG" else "SHORT"} side.\nState: ${tradeSignal.state}\n\nComment: $comment"
            }, -1001766990583)
        }
    }

    suspend fun openDeal(symbol: Symbol, side: Boolean, interval: CandleInterval, close: BigDecimal): Deal? {
        val orderInfo = byBitAPI.makeOrder(
            symbol = symbol,
            side = side,
            amount = constants.dealAmount,
            close = close,
            leverage = constants.leverage,
            //stopLossPrice = if (side) close * (BigDecimal.ONE - constants.stopPercent)
            //else close * (BigDecimal.ONE + constants.stopPercent)
        ) ?: return null

        delay(1000)
        val (liqPrice, orderPrice) = byBitAPI.getOrder(orderInfo.orderId, symbol, side)

        if (side && close * (BigDecimal.ONE - constants.stopPercent) <= liqPrice ||
                !side && close * (BigDecimal.ONE + constants.stopPercent) >= liqPrice) {
            byBitAPI.makeOrder(
                symbol = symbol,
                side = !side,
                amount = constants.dealAmount,
                close = close,
                leverage = constants.leverage,
                positionIdx = if (side) "1" else "2"
            )

            return null
        }

        deals[symbol] = Deal(
            side = Side.valueOfBoolSide(side),
            symbol = symbol,
            amount = orderInfo.qty,
            price = orderPrice,
            interval = interval
        )

        constants.deals = deals.keys.toList()

        makeStop(
            symbol,
            side = !side,
            part = BigDecimal.ONE,
            price = if (side) orderPrice * (BigDecimal.ONE - constants.stopPercent)
                    else orderPrice * (BigDecimal.ONE + constants.stopPercent),
            basePriceMultiplier = !side
        )

        return deals[symbol]
    }

    // pre: side against opened deal
    suspend fun makeStop(symbol: Symbol, side: Boolean, part: BigDecimal, price: BigDecimal, basePriceMultiplier: Boolean = side) {
        val deal = deals[symbol] ?: return

        byBitAPI.makeStopOrder(
            symbol = symbol,
            side = side,
            qty = deal.amount.setScale(byBitAPI.assetsData[symbol.name]!!.second, RoundingMode.CEILING) * part,
            price = price,
            leverage = constants.leverage,
            basePriceMultiplier = basePriceMultiplier
        )
    }

    suspend fun makeLimit(symbol: Symbol, side: Boolean, interval: CandleInterval, price: BigDecimal, part: BigDecimal ) {
        val deal = deals[symbol] ?: return

        byBitAPI.makeLimitOrder(
            symbol = symbol,
            side = side,
            qty = deal.amount * part,
            leverage = constants.leverage,
            price = price
        )
    }

    fun closeDeal(symbol: Symbol) {
        deals.remove(symbol)
        constants.deals = deals.keys.toList()
    }

    suspend fun closeAllStops(symbol: Symbol) {
        byBitAPI.cancelAllStops(symbol)
    }

    suspend fun closeAllLimits(symbol: Symbol) {
        byBitAPI.cancelAllActiveOrders(symbol)
    }
}

data class TradeSignal(
    val side: Boolean,
    val strategyName: TradeStrategy,
    val interval: CandleInterval,
    val state: String,
    val orderType: Int
)

enum class TradeStrategy {
    PNL_STRATEGY
}