package com.strat.analyzer.domain

import com.strat.analyzer.db.candle.Candle
import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.StockExchange
import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.domain.strategies.PNLStrategy
import com.strat.analyzer.utils.Constants
import org.springframework.stereotype.Component
import org.ta4j.core.Bar
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.num.DecimalNum
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.annotation.PostConstruct

interface BSInitializer {
    fun initBaseSeries(candles: List<Candle>)
}

@Component
class TA4JService(
    private val pnlStrategy: PNLStrategy,
    private val constants: Constants
): BSInitializer {

    private val baseSeries = mutableMapOf<Triple<Symbol, CandleInterval, StockExchange>, BarSeries>()

    companion object {
        private val logger = Logger.getLogger("TA4J-SERVICE")

        private fun constructBar(candle: Candle): Bar = BaseBar.builder()
            .openPrice(DecimalNum.valueOf(candle.open))
            .closePrice(DecimalNum.valueOf(candle.close))
            .highPrice(DecimalNum.valueOf(candle.high))
            .lowPrice(DecimalNum.valueOf(candle.low))
            .volume(DecimalNum.valueOf(candle.volume))
            .endTime(
                ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(candle.openTime + candle.interval.duration.toMillis()), ZoneOffset.UTC
                )
            )
            .timePeriod(candle.interval.duration)
            .build()

        private fun constructBarSeriesFromCandles(candles: List<Candle>): BarSeries = BaseBarSeries(
            candles.first().let { "${it.symbol} ${it.interval}" },
            candles.map(this::constructBar)
        ).apply { maximumBarCount = 190 }
    }

    @PostConstruct
    fun iinit() {
        constants.bsInitializer = this
    }

    override fun initBaseSeries(candles: List<Candle>) {
        println(candles.size)
        baseSeries[Triple(candles.first().symbol, candles.first().interval, candles.first().stockExchange)] =
            constructBarSeriesFromCandles(candles)
    }

    suspend fun updateCandle(candle: Candle) {
        //logger.info("Update candle $candle")
        val bs = baseSeries[Triple(candle.symbol, candle.interval, candle.stockExchange)] ?: return

        if (constants.candlesStartTime[Pair(candle.symbol, candle.interval)] == null) {
            synchronized(constants.candlesStartTime) {
                constants.candlesStartTime[Pair(candle.symbol, candle.interval)] = AtomicLong()
            }
        }

        val oldCandle = constants.candlesStartTime[Pair(candle.symbol, candle.interval)]!!.get() == candle.openTime

        constants.candlesStartTime[Pair(candle.symbol, candle.interval)]?.set(candle.openTime)

        try {
            bs.addBar(constructBar(candle), oldCandle)
        } catch (e: Exception) {
            e.printStackTrace()

            return
        }

        //logger.info("BS size ${bs.barCount}")

        //constants.lastUpdate = candle.openTime

        refreshStrategies(candle.symbol, candle.interval, candle.stockExchange, !oldCandle)
    }

    suspend fun refreshStrategies(
        symbol: Symbol,
        interval: CandleInterval,
        stockExchange: StockExchange,
        updatedCandle: Boolean
    ) {
        pnlStrategy.runStrategy(
            baseSeries[Triple(symbol, interval, stockExchange)]!!,
            Triple(symbol, interval, stockExchange),
            updatedCandle
        )

    }
}