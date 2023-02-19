package com.strat.analyzer.db.candle

import com.strat.analyzer.domain.TA4JService
import com.strat.analyzer.domain.api.ByBitWSCandle
import com.strat.analyzer.utils.Constants
import com.strat.analyzer.utils.Logger
import com.strat.analyzer.utils.wrapTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct

interface CandleHandler {
    fun gotUpdate(symbol: Symbol, interval: CandleInterval, byBitWSCandle: ByBitWSCandle)
}

@Component
final class CandleService(
    private val tA4JService: TA4JService,
    private val constants: Constants
) : CandleHandler {
    private val candlesProcessorScope = CoroutineScope(Dispatchers.Default)

    private val bybitFlows = mutableMapOf<Pair<Symbol, CandleInterval>, CandlesFlow>()

    init {
        Symbol.values()
            .map { symbol -> CandleInterval.values()
                .map { symbol to it }
            }
            .flatten()
            .forEach {
                bybitFlows[it] = CandlesFlow(
                    process = wrapTry(this::transformCandle, Unit),
                    scope = candlesProcessorScope
                )
            }
    }

    @PostConstruct
    fun iinit() {
        constants.candleHandler = this
    }

    //override fun deleteAll() = candleRepository.deleteAll()

    suspend fun transformCandle(info: Triple<Symbol, ByBitWSCandle, CandleInterval>?) {
        if (info == null) { return }

        constants.coroutinesCount.incrementAndGet()

        val symbol = info.first
        val candlestickEvent = info.second
        val interval = info.third

        if (constants.candlesLastTime[Pair(symbol, interval)] == null) {
            synchronized(constants.candlesLastTime) {
                constants.candlesLastTime[Pair(symbol, interval)] = AtomicLong()
            }
        }

        if (candlestickEvent.timestamp < constants.candlesLastTime[Pair(symbol, interval)]!!.get()) {
            return
        }

        constants.candlesLastTime[Pair(symbol, interval)]?.set(candlestickEvent.timestamp)

        Logger.addCandleEvent(candlestickEvent.toString())

        /*val candles = withContext(Dispatchers.IO) {
            candleRepository.findAllByOpenTimeAndSymbolAndIntervalAndStockExchange(
                candlestickEvent.start,
                symbol,
                interval,
                StockExchange.BYBIT
            )
        }

        if (candles.size > 1) {
            println(candles)
        }

        var candle = candles.firstOrNull()

        if (candle == null) {
            candle = Candle(symbol, candlestickEvent, interval)
        } else {
            candle.updateCandleByCandlestickEvent(candlestickEvent)
        }*/

        tA4JService.updateCandle(Candle(symbol, candlestickEvent, interval))

        /*withContext(Dispatchers.IO) {
            candleRepository.save(candle)
        }*/

        constants.coroutinesCount.decrementAndGet()
    }

    override fun gotUpdate(symbol: Symbol, interval: CandleInterval, byBitWSCandle: ByBitWSCandle) {
        candlesProcessorScope.launch {
            try {
                transformCandle(Triple(symbol, byBitWSCandle, interval))
            } catch (e: Exception) {
                e.printStackTrace()
                constants.coroutinesCount.decrementAndGet()
            }
        }
    }
}