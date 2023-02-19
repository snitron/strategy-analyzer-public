package com.strat.analyzer.domain.strategies

import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.StockExchange
import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.db.deal.Deal
import com.strat.analyzer.db.deal.Side
import com.strat.analyzer.domain.Analyzer
import com.strat.analyzer.domain.TradeSignal
import com.strat.analyzer.domain.TradeStrategy
import com.strat.analyzer.utils.Constants
import com.strat.analyzer.utils.round
import com.strat.analyzer.utils.startSuspended
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import org.ta4j.core.Bar
import org.ta4j.core.BarSeries
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.Num
import org.ta4j.core.rules.InPipeRule
import org.ta4j.core.rules.OverIndicatorRule
import org.ta4j.core.rules.UnderIndicatorRule
import java.math.BigDecimal
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger


@Component
class PNLStrategy(
    private val analyzer: Analyzer,
    private val constants: Constants
) {
    enum class RSIShortStrategyState {
        NONE, UPPER_70, TAKE1, TAKE2,/* TAKE3, */ SHORT, BETWEEN_30_70, NEAR_70
    }

    enum class RSILongStrategyState {
        NONE, LOWER_30, TAKE1, TAKE2,/* TAKE3, */ LONG, BETWEEN_30_70, NEAR_30
    }

    private val assetsData = analyzer.byBitAPI.assetsData

    private fun PLUS_STOP_PERCENT() = BigDecimal.ONE + constants.stopBUPercent
    private fun MINUS_STOP_PRECENT() = BigDecimal.ONE - constants.stopBUPercent

    private val name = TradeStrategy.PNL_STRATEGY
    private val shortStates = Collections.synchronizedMap(mutableMapOf<String, RSIShortStrategyState>())
    private val longStates = Collections.synchronizedMap(mutableMapOf<String, RSILongStrategyState>())

    private val longDiverBarIndex = Collections.synchronizedMap(mutableMapOf<String,  MutableList<Int>>())
    private val shortDiverBarIndex = Collections.synchronizedMap(mutableMapOf<String,  MutableList<Int>>())

    private val tradeLocks = Collections.synchronizedMap(mutableMapOf<Symbol, AtomicBoolean>())

    private val ONE_QUARTER = 0.25.toBigDecimal()
    private val ONE_HALF = 0.5.toBigDecimal()

    var enteredTakes = Collections.synchronizedMap(mutableMapOf<String, List<BigDecimal>>())

    companion object {
        private val logger = Logger.getLogger("PNL-STRATEGY")

        fun findExpremums(rsi: RSIIndicator, from: Int, to: Int) : Pair<Int, Int> /*min, max*/ {
            var minV = Double.MAX_VALUE
            var minInd = -1

            var maxV = Double.MIN_VALUE
            var maxInd = -1


            for (i in from until to) {
                if (rsi.getValue(i).doubleValue() > maxV) {
                    maxInd = i
                    maxV = rsi.getValue(i).doubleValue()
                }

                if (rsi.getValue(i).doubleValue() < minV) {
                    minInd = i
                    minV = rsi.getValue(i).doubleValue()
                }
            }

            return Pair(minInd, maxInd)
        }

        fun findExpremums(barSeries: BarSeries, from: Int, to: Int) : Pair<Int, Int> /*min, max*/ {
            var minV = Double.MAX_VALUE
            var minInd = -1

            var maxV = Double.MIN_VALUE
            var maxInd = -1


            for (i in from until to) {
                if (barSeries.getBar(i).highPrice.doubleValue() > maxV) {
                    maxInd = i
                    maxV = barSeries.getBar(i).highPrice.doubleValue()
                }

                if (barSeries.getBar(i).lowPrice.doubleValue() < minV) {
                    minInd = i
                    minV = barSeries.getBar(i).lowPrice.doubleValue()
                }
            }

            return Pair(minInd, maxInd)
        }

        fun allSatisfy(rsi: RSIIndicator, from: Int, to: Int, pred: (Num) -> Boolean): Boolean {
            for (i in from until to) {
                if (!pred.invoke(rsi.getValue(i))) {
                    return false
                }
            }

            return true
        }
    }

    private fun sideMultiplier(side: Boolean): BigDecimal = if (side) BigDecimal.ONE else -BigDecimal.ONE

    fun calculateStops(
        symbol: Symbol,
        entry: BigDecimal,
        qty: BigDecimal,
        leverage: BigDecimal,
        side: Boolean
    ): List<BigDecimal> {
        val bankruptcyPrice = entry * (BigDecimal.ONE - sideMultiplier(side) / leverage)
        val feeToClose = bankruptcyPrice * qty * 0.0006.toBigDecimal()
        val initialMargin = qty * entry / leverage
        val positionMargin = initialMargin + feeToClose

        return constants.takes.map { unrealizedPnl ->
            (unrealizedPnl * positionMargin / qty / sideMultiplier(side) + entry).round(assetsData[symbol.name]!!.third)!!
        }
    }

    suspend fun runShortStrategy(
        rsi: RSIIndicator,
        deal: Deal?,
        seriesName: String,
        symbol: Symbol,
        interval: CandleInterval,
        updatedCandle: Boolean,
        enabled: Boolean
    ) {
        if (seriesName !in shortStates) {
            shortStates[seriesName] = RSIShortStrategyState.NONE
        }

        if(shortDiverBarIndex[seriesName] == null){
            shortDiverBarIndex[seriesName] = mutableListOf(0, 0)
        } else {
            if(updatedCandle){
                shortDiverBarIndex[seriesName] = shortDiverBarIndex[seriesName]!!.map {
                    maxOf(0, it - 1)
                }.toMutableList()
            }
        }


        com.strat.analyzer.utils.Logger.addCandleEvent("Рrocessing Short $symbol")


        if (!updatedCandle && (shortStates[seriesName] == RSIShortStrategyState.NONE ||
                    shortStates[seriesName] == RSIShortStrategyState.UPPER_70 ||
                    shortStates[seriesName] == RSIShortStrategyState.BETWEEN_30_70)
        ) {
            return
        }


        val fullBars = rsi.barSeries
        val lastUpdatedBar = fullBars.lastBar

        val series = fullBars.getSubSeries(fullBars.beginIndex, fullBars.endIndex) // обрезание (все кроме последнего)
        val lastRsiValue = rsi.getValue(fullBars.endIndex - 1).doubleValue() // rsi предпоследнего бара

        val lastBar = series.lastBar // предпоследний бар

        val enteredClose = deal?.price?.toDouble() ?: 1.0
        val EPS = constants.rsiEpsilon

        val UPPER = constants.rsiLevels.maxOf { it }
        val LOWER = constants.rsiLevels.minOf { it }

        when (shortStates[seriesName]!!) {
            RSIShortStrategyState.NONE -> {
                if (OverIndicatorRule(rsi, UPPER).isSatisfied(series.endIndex)) {
                    shortStates[seriesName] = RSIShortStrategyState.UPPER_70

                    shortDiverBarIndex[seriesName]!![0] = series.endIndex
                    //notifyMasters(shortStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Вышли за 70 вверх, ждём откат")
                }
            }

            RSIShortStrategyState.UPPER_70 -> {
                if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                    shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70

                    shortDiverBarIndex[seriesName]!![1] = series.endIndex
                    //notifyMasters(shortStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Произошёл откат, ждём ретеста")
                } else if (UnderIndicatorRule(rsi, LOWER + EPS).isSatisfied(series.endIndex)) {
                    shortStates[seriesName] = RSIShortStrategyState.NONE

                    //analyzer.closeDeal(symbol)
                    //notifyMasters(shortStates[seriesName]!!, lastRsiValue, lastBar, interval,  seriesName, "Вышли за зону отката, сброс стратегии")
                }
            }

            RSIShortStrategyState.BETWEEN_30_70 -> {
                //analyzer.closeDeal(symbol)
                if (InPipeRule(rsi, UPPER, UPPER - EPS).isSatisfied(series.endIndex) && UnderIndicatorRule(
                        rsi,
                        UPPER
                    ).isSatisfied(series.endIndex - 1)
                ) {
                    tradeLocks[symbol]?.set(true)

                    delay(1000 * constants.timeSleeps[interval.index].toLong())
                    shortStates[seriesName] = RSIShortStrategyState.NEAR_70

                    tradeLocks[symbol]?.set(false)

                } else if (OverIndicatorRule(rsi, UPPER).isSatisfied(series.endIndex)) {
                    shortStates[seriesName] = RSIShortStrategyState.NONE
                    //notifyMasters(shortStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Опять вышли за 70")
                    //analyzer.closeDeal(symbol)


                } else if (!InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                    shortStates[seriesName] = RSIShortStrategyState.NONE
                    //notifyMasters(shortStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Вышли за зону отката, сброс стратегии")
                    //analyzer.closeDeal(symbol)

                }
            }

            RSIShortStrategyState.NEAR_70 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()
                val upperDiv = constants.diverLevels.maxOf { it }
                val lowerDiv = constants.diverLevels.minOf { it }


                if(UnderIndicatorRule(rsi, UPPER).isSatisfied(fullBars.endIndex)){
                    val rsiExtremumIndex = findExpremums(rsi,
                        shortDiverBarIndex[seriesName]!![0],
                        series.endIndex + 1
                    ).second

                    val beginExtremumIndex = findExpremums(series,
                        shortDiverBarIndex[seriesName]!![0],
                        shortDiverBarIndex[seriesName]!![1]
                    ).second

                    val nextExtremumIndex = findExpremums(series,
                        shortDiverBarIndex[seriesName]!![1],
                        series.endIndex + 1
                    ).second

                    if(rsiExtremumIndex == -1
                        || beginExtremumIndex == -1
                        || nextExtremumIndex == -1
                    ){
                        shortStates[seriesName] = RSIShortStrategyState.NONE
                        return
                    }

                    val beginDiverTestBarHigh = series.getBar(beginExtremumIndex).highPrice
                    val nextDiverReTestBarHigh = series.getBar(nextExtremumIndex).highPrice
                    val beginDiverTestBarRsi = rsi.getValue(rsiExtremumIndex)
                    val nextDiverReTestBarRsi = rsi.getValue(series.endIndex)

                    var isDiver = false

                    if (beginDiverTestBarRsi != null && beginDiverTestBarHigh != null) {

                        isDiver = allSatisfy(rsi, rsiExtremumIndex, series.endIndex) { it.doubleValue() > 50.0 } &&
                                beginDiverTestBarHigh < nextDiverReTestBarHigh &&
                                beginDiverTestBarRsi > nextDiverReTestBarRsi &&

                                nextExtremumIndex - beginExtremumIndex >= lowerDiv &&
                                nextExtremumIndex - beginExtremumIndex <= upperDiv &&

                                series.endIndex - rsiExtremumIndex >= lowerDiv &&
                                series.endIndex - rsiExtremumIndex <= upperDiv

                    }

                    if (enabled && isDiver) {
                        shortStates[seriesName] = RSIShortStrategyState.SHORT
                        startSuspended {
                            tradeLocks[symbol]?.set(true)
                            val newDeal = analyzer.openDeal(
                                symbol,
                                side = false,
                                interval = interval,
                                close = lastClose
                            )

                            if (newDeal == null) {
                                shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70

                                tradeLocks[symbol]?.set(false)
                                return@startSuspended null
                            } else {

                                notifyMasters(
                                    shortStates[seriesName]!!,
                                    lastRsiValue,
                                    lastBar,
                                    interval,
                                    seriesName,
                                    "Произошёл откат, зашли в ШОРТ $beginDiverTestBarRsi $nextDiverReTestBarRsi"
                                )
                            }

                            enteredTakes[seriesName] = calculateStops(
                                symbol = symbol,
                                entry = newDeal.price,
                                qty = newDeal.amount,
                                side = false,
                                leverage = constants.leverage
                            )

                            enteredTakes[seriesName]!!.forEachIndexed { index, takePrice ->
                                analyzer.makeLimit(
                                    symbol = symbol,
                                    side = true,
                                    part = when (index) {
                                        0 -> ONE_HALF
                                        2 -> BigDecimal.ONE
                                        else -> ONE_QUARTER
                                    },
                                    price = takePrice,
                                    interval = interval
                                )
                            }

                            tradeLocks[symbol]?.set(false)
                        }
                    } else {
                        if(!enabled && isDiver){
                            notifyMasters(
                                shortStates[seriesName]!!,
                                lastRsiValue,
                                lastBar,
                                interval,
                                seriesName,
                                "ШОРТ disabled, тестовый режим, заходим в сделку $beginDiverTestBarRsi $nextDiverReTestBarRsi"
                            )
                        }
                        shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70

                    }
                } else {
                    shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70
                }

            }


            RSIShortStrategyState.SHORT -> {
                val stopPercent = constants.stopPercent.toDouble()
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()


                if (lastClose >= (enteredClose * (1.0 + stopPercent)).toBigDecimal()) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70
                    } else if (OverIndicatorRule(rsi, UPPER - EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.UPPER_70
                    } else {
                        shortStates[seriesName] = RSIShortStrategyState.NONE
                    }

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли $enteredClose + ${stopPercent * 100}%, сработал стоп лосс"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                        //analyzer.closeAllStops(symbol)
                    }

                    analyzer.closeDeal(symbol)
                } else if (lastClose < enteredTakes[seriesName]!![0]) {
                    shortStates[seriesName] = RSIShortStrategyState.TAKE1

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли первый тейк, закрываем половину. Выставляем стоп в БУ."
                    )

                    startSuspended {
                        analyzer.closeAllStops(symbol)
                        analyzer.makeStop(
                            symbol,
                            side = true,
                            part = BigDecimal.ONE,
                            price = enteredClose.toBigDecimal() * MINUS_STOP_PRECENT(),
                            basePriceMultiplier = true
                        )
                    }
                }


            }

            RSIShortStrategyState.TAKE1 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()

                if (lastClose >= (enteredClose.toBigDecimal() * MINUS_STOP_PRECENT()).round(assetsData[symbol.name]!!.third)!!) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70
                    } else if (OverIndicatorRule(rsi, UPPER - EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.UPPER_70
                    } else {
                        shortStates[seriesName] = RSIShortStrategyState.NONE
                    }

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли $enteredClose, сработал стоп закрылись в БУ"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                    }

                    analyzer.closeDeal(symbol)
                } else if (lastClose < enteredTakes[seriesName]!![1]) {
                    shortStates[seriesName] = RSIShortStrategyState.TAKE2

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли второй тейк, закрываем четверть. Выставляем новый стоп БУ."
                    )

                    startSuspended {
                        analyzer.closeAllStops(symbol)
                        analyzer.makeStop(
                            symbol,
                            side = true,
                            part = BigDecimal.ONE,
                            price = enteredTakes[seriesName]!![0],
                            basePriceMultiplier = true
                        )
                    }
                }

            }

            RSIShortStrategyState.TAKE2 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()

                if (lastClose >= enteredTakes[seriesName]!![0]) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.BETWEEN_30_70
                    } else if (OverIndicatorRule(rsi, UPPER - EPS).isSatisfied(series.endIndex)) {
                        shortStates[seriesName] = RSIShortStrategyState.UPPER_70
                    } else {
                        shortStates[seriesName] = RSIShortStrategyState.NONE
                    }

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли ${enteredTakes[seriesName]!![0]}, сработал стоп закрылись в БУ"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                    }

                    analyzer.closeDeal(symbol)
                } else if (lastClose < enteredTakes[seriesName]!![2]) {
                    shortStates[seriesName] = RSIShortStrategyState.NONE

                    notifyMasters(
                        shortStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли третий тейк, закрылись поностью, сброс стратегии."
                    )

                    startSuspended {
                        analyzer.closeAllStops(symbol)
                    }

                    analyzer.closeDeal(symbol)

                }
            }
        }
    }

    suspend fun runLongStrategy(
        rsi: RSIIndicator,
        deal: Deal?,
        seriesName: String,
        symbol: Symbol,
        interval: CandleInterval,
        updatedCandle: Boolean,
        enabled: Boolean
    ) {
        if (seriesName !in longStates) {
            longStates[seriesName] = RSILongStrategyState.NONE
        }

        if(longDiverBarIndex[seriesName] == null){
            longDiverBarIndex[seriesName] = mutableListOf(0, 0)
        } else {
            if(updatedCandle){
                longDiverBarIndex[seriesName] = longDiverBarIndex[seriesName]!!.map {
                    maxOf(0, it - 1)
                }.toMutableList()
            }
        }

        com.strat.analyzer.utils.Logger.addCandleEvent("Рrocessing Long $symbol")


        if (!updatedCandle && (longStates[seriesName] == RSILongStrategyState.NONE ||
                    longStates[seriesName] == RSILongStrategyState.LOWER_30 ||
                    longStates[seriesName] == RSILongStrategyState.BETWEEN_30_70)
        ) {
            return
        }

        val fullBars = rsi.barSeries
        val lastUpdatedBar = fullBars.lastBar

        val series = fullBars.getSubSeries(fullBars.beginIndex, fullBars.endIndex)
        val lastRsiValue = rsi.getValue(fullBars.endIndex - 1).doubleValue()
        val lastBar = series.lastBar

        val enteredClose = deal?.price?.toDouble() ?: 1.0
        val EPS = constants.rsiEpsilon

        val UPPER = constants.rsiLevels.maxOf { it }
        val LOWER = constants.rsiLevels.minOf { it }

        when (longStates[seriesName]) {
            RSILongStrategyState.NONE -> {
                if (UnderIndicatorRule(rsi, LOWER).isSatisfied(series.endIndex)) {
                    longStates[seriesName] = RSILongStrategyState.LOWER_30
                    longDiverBarIndex[seriesName]!![0] = series.endIndex
                    //notifyMasters(longStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Вышли за 30 вверх, ждём откат")
                }
            }

            RSILongStrategyState.LOWER_30 -> {
                if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                    longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70
                    longDiverBarIndex[seriesName]!![1] = series.endIndex
                    //notifyMasters(longStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Произошёл откат, ждём ретеста")

                } else if (OverIndicatorRule(rsi, UPPER - EPS).isSatisfied(series.endIndex)) {
                    longStates[seriesName] = RSILongStrategyState.NONE
                    //notifyMasters(longStates[seriesName]!!, lastRsiValue, lastBar, interval,  seriesName, "Вышли за зону отката, сброс стратегии")
                }
            }

            RSILongStrategyState.BETWEEN_30_70 -> {
                if (InPipeRule(rsi, LOWER + EPS, LOWER).isSatisfied(series.endIndex) && OverIndicatorRule(
                        rsi,
                        LOWER
                    ).isSatisfied(series.endIndex - 1)
                ) {

                    tradeLocks[symbol]?.set(true)

                    delay(1000 * constants.timeSleeps[interval.index].toLong())
                    longStates[seriesName] = RSILongStrategyState.NEAR_30

                    tradeLocks[symbol]?.set(false)

                } else if (UnderIndicatorRule(rsi, LOWER).isSatisfied(series.endIndex)) {
                    longStates[seriesName] = RSILongStrategyState.NONE
                    //notifyMasters(longStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Опять вышли за 30")
                } else if (!InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                    longStates[seriesName] = RSILongStrategyState.NONE
                    //notifyMasters(longStates[seriesName]!!, lastRsiValue, lastBar, interval, seriesName, "Вышли за зону отката, сброс стратегии")
                }
            }

            RSILongStrategyState.NEAR_30 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()
                val upperDiv = constants.diverLevels.maxOf { it }
                val lowerDiv = constants.diverLevels.minOf { it }

                if(OverIndicatorRule(rsi, LOWER).isSatisfied(fullBars.endIndex))
                {

                    val rsiExtremumIndex = findExpremums(rsi,
                        longDiverBarIndex[seriesName]!![0],
                        series.endIndex + 1
                    ).first

                    val beginExtremumIndex = findExpremums(series,
                        longDiverBarIndex[seriesName]!![0],
                        longDiverBarIndex[seriesName]!![1]
                    ).first

                    val nextExtremumIndex = findExpremums(series,
                        longDiverBarIndex[seriesName]!![1],
                        series.endIndex + 1
                    ).first

                    if(rsiExtremumIndex == -1
                        || beginExtremumIndex == -1
                        || nextExtremumIndex == -1
                    ){
                        longStates[seriesName] = RSILongStrategyState.NONE
                        return
                    }

                    val beginDiverTestBarLow = series.getBar(beginExtremumIndex).lowPrice
                    val nextDiverReTestBarLow = series.getBar(nextExtremumIndex).lowPrice
                    val beginDiverTestBarRsi = rsi.getValue(rsiExtremumIndex)
                    val nextDiverReTestBarRsi = rsi.getValue(series.endIndex)


                    var isDiver = false

                    if (beginDiverTestBarRsi != null && beginDiverTestBarLow != null) {

                        isDiver = allSatisfy(rsi, rsiExtremumIndex, series.endIndex) { it.doubleValue() < 50.0 } &&
                                beginDiverTestBarLow > nextDiverReTestBarLow &&
                                beginDiverTestBarRsi < nextDiverReTestBarRsi &&

                                nextExtremumIndex - beginExtremumIndex >= lowerDiv &&
                                nextExtremumIndex - beginExtremumIndex <= upperDiv &&

                                series.endIndex - rsiExtremumIndex >= lowerDiv &&
                                series.endIndex - rsiExtremumIndex <= upperDiv

                    }
                    if (enabled && isDiver) {
                        longStates[seriesName] = RSILongStrategyState.LONG

                        startSuspended {
                            tradeLocks[symbol]?.set(true)

                            val newDeal = analyzer.openDeal(
                                symbol,
                                side = true,
                                interval = interval,
                                close = lastClose
                            )

                            if (newDeal == null) {
                                longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70

                                tradeLocks[symbol]?.set(false)
                                return@startSuspended null
                            }
                            else {

                                notifyMasters(
                                    longStates[seriesName]!!,
                                    lastRsiValue,
                                    lastBar,
                                    interval,
                                    seriesName,
                                    "Произошёл откат, зашли в ЛОНГ $beginDiverTestBarRsi $nextDiverReTestBarRsi"
                                )

                            }


                            enteredTakes[seriesName] = calculateStops(
                                symbol = symbol,
                                entry = newDeal.price,
                                qty = newDeal.amount,
                                side = true,
                                leverage = constants.leverage
                            )

                            enteredTakes[seriesName]!!.forEachIndexed { index, takePrice ->
                                analyzer.makeLimit(
                                    symbol = symbol,
                                    side = false,
                                    part = when (index) {
                                        0 -> ONE_HALF
                                        2 -> BigDecimal.ONE
                                        else -> ONE_QUARTER
                                    },
                                    price = takePrice,
                                    interval = interval
                                )
                            }

                            tradeLocks[symbol]?.set(false)
                        }
                    } else {
                        if(!enabled && isDiver){
                            notifyMasters(
                                longStates[seriesName]!!,
                                lastRsiValue,
                                lastBar,
                                interval,
                                seriesName,
                                "ЛОНГ disabled, тестовый режим, заходим в сделку $beginDiverTestBarRsi $nextDiverReTestBarRsi"
                            )
                        }
                        longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70

                    }
                } else {
                    longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70
                }

            }


            RSILongStrategyState.LONG -> {
                val stopPercent = constants.stopPercent.toDouble()
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()

                if (lastClose <= (enteredClose * (1.0 - stopPercent)).toBigDecimal()) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70
                    } else if (UnderIndicatorRule(rsi, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.LOWER_30
                    } else {
                        longStates[seriesName] = RSILongStrategyState.NONE
                    }

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли $enteredClose - ${stopPercent * 100}%, сработал стоп лосс"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                        //analyzer.closeAllStops(symbol)
                    }

                    analyzer.closeDeal(symbol)

                } else if (lastClose > enteredTakes[seriesName]!![0]) {
                    longStates[seriesName] = RSILongStrategyState.TAKE1

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли первый тейк, закрываем половину. Выставляем стоп в БУ."
                    )

                    startSuspended {
                        analyzer.closeAllStops(symbol)
                        analyzer.makeStop(
                            symbol,
                            side = false,
                            part = BigDecimal.ONE,
                            price = enteredClose.toBigDecimal() * PLUS_STOP_PERCENT(),
                            basePriceMultiplier = false
                        )
                    }
                }
            }

            RSILongStrategyState.TAKE1 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()

                if (lastClose <= (enteredClose.toBigDecimal() * PLUS_STOP_PERCENT()).round(assetsData[symbol.name]!!.third)!!) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70
                    } else if (UnderIndicatorRule(rsi, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.LOWER_30
                    } else {
                        longStates[seriesName] = RSILongStrategyState.NONE
                    }

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли $enteredClose, сработал стоп закрылись в БУ"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                        //analyzer.closeAllStops(symbol)
                    }

                    analyzer.closeDeal(symbol)
                } else if (lastClose > enteredTakes[seriesName]!![1]) {
                    longStates[seriesName] = RSILongStrategyState.TAKE2

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли второй тейк, закрываем четверть. Выставляем новый стоп БУ."
                    )

                    startSuspended {
                        analyzer.closeAllStops(symbol)
                        analyzer.makeStop(
                            symbol,
                            side = false,
                            part = BigDecimal.ONE,
                            price = enteredTakes[seriesName]!![0],
                            basePriceMultiplier = false
                        )
                    }
                }
            }

            RSILongStrategyState.TAKE2 -> {
                val lastClose = lastUpdatedBar.closePrice.doubleValue().toBigDecimal()

                if (lastClose <= enteredTakes[seriesName]!![0]) {
                    if (InPipeRule(rsi, UPPER - EPS, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.BETWEEN_30_70
                    } else if (UnderIndicatorRule(rsi, LOWER + EPS).isSatisfied(series.endIndex)) {
                        longStates[seriesName] = RSILongStrategyState.LOWER_30
                    } else {
                        longStates[seriesName] = RSILongStrategyState.NONE
                    }

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли ${enteredTakes[seriesName]!![0]}, сработал стоп закрылись в БУ"
                    )

                    startSuspended {
                        analyzer.closeAllLimits(symbol)
                    }

                    analyzer.closeDeal(symbol)
                } else if (lastClose > enteredTakes[seriesName]!![2]) {
                    longStates[seriesName] = RSILongStrategyState.NONE

                    notifyMasters(
                        longStates[seriesName]!!,
                        lastRsiValue,
                        lastBar,
                        interval,
                        seriesName,
                        "Пересекли третий тейк, закрылись поностью, сброс стратегии."
                    )

                    startSuspended {
                        //analyzer.closeAllLimits(symbol)
                        analyzer.closeAllStops(symbol)
                    }

                    analyzer.closeDeal(symbol)
                }
            }
        }
    }

    suspend fun runStrategy(
        barSeries: BarSeries,
        info: Triple<Symbol, CandleInterval, StockExchange>,
        updatedCandle: Boolean
    ) {
        val possibleIntervals =
            CandleInterval.values().toList().filter { it.duration == Duration.ofMinutes(constants.timeFrame) }

        if (info.second !in possibleIntervals) {
            return
        }

        if (info.first !in tradeLocks) {
            tradeLocks[info.first] = AtomicBoolean(false)
        }

        if (tradeLocks[info.first]?.get() == true) {
            return
        }

        val series = barSeries.getSubSeries(barSeries.beginIndex, barSeries.endIndex + 1)

        val seriesName = series.name
        val rsi = RSIIndicator(ClosePriceIndicator(series), constants.rsiLength)


        if (analyzer.byBitAPI.assetsData[info.first.toString()]!!.first >= constants.leverage) {
            com.strat.analyzer.utils.Logger.addCandleEvent("Start processing $info")
            val deal = analyzer.deals[info.first]

            val dealLong = if (deal?.side == Side.LONG) analyzer.deals[info.first] else null
            val dealShort = if (deal?.side == Side.SHORT) analyzer.deals[info.first] else null

            if (dealShort == null && (dealLong == null || dealLong.interval == info.second)) {
                runLongStrategy(
                    rsi,
                    dealLong,
                    seriesName,
                    info.first,
                    info.second,
                    updatedCandle,
                    constants.longEnabled,
                )
            }

            if (dealLong == null && (dealShort == null || dealShort.interval == info.second)) {
                runShortStrategy(
                    rsi,
                    dealShort,
                    seriesName,
                    info.first,
                    info.second,
                    updatedCandle,
                    constants.shortEnabled,
                )
            }
        }
    }

    private fun notifyMasterImpl(
        side: Boolean,
        state: String,
        lastRsiValue: Double,
        lastBar: Bar,
        interval: CandleInterval,
        seriesName: String,
        comment: String
    ) {
        println("$side $state $seriesName $comment")


        analyzer.checkTradeSignal(
            TradeSignal(
                side = side,
                strategyName = name,
                state = state,
                orderType = -1,
                interval = interval
            ),
            seriesName = seriesName,
            comment = "$comment\nПоследнее значение RSI: $lastRsiValue\nПоследний close: ${lastBar.closePrice.doubleValue()}\n"
        )
    }

    fun notifyMasters(
        state: RSIShortStrategyState,
        lastRsiValue: Double,
        lastBar: Bar,
        interval: CandleInterval,
        seriesName: String,
        comment: String
    ) = notifyMasterImpl(false, state.name, lastRsiValue, lastBar, interval, seriesName, comment)

    fun notifyMasters(
        state: RSILongStrategyState,
        lastRsiValue: Double,
        lastBar: Bar,
        interval: CandleInterval,
        seriesName: String,
        comment: String
    ) = notifyMasterImpl(true, state.name, lastRsiValue, lastBar, interval, seriesName, comment)
}

