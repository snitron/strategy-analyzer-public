package com.strat.analyzer

import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.domain.api.ByBitAPI
import com.strat.analyzer.utils.round
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.math.RoundingMode


@SpringBootTest
class ByBitAPITests @Autowired constructor(
    val byBitAPI: ByBitAPI
) {

    @Test
    fun getLeverageTest() {
        runBlocking {
            println(byBitAPI.getLeverage(Symbol.BTCUSDT))
        }
    }

    @Test
    fun setLeverageTest() {
        runBlocking {
            println(byBitAPI.setLeverage(Symbol.BTCUSDT, 150.0))
        }
    }

    @Test
    fun getBalanceTest() {
        runBlocking {
            println(byBitAPI.getBalance())
        }
    }

    @Test
    fun placeOrderTest() {
        runBlocking {
            byBitAPI.makeOrder(
                symbol = Symbol.BTCUSDT,
                side = false,
                amount = 10.toBigDecimal(),
                close = 33000.0.toBigDecimal(),
                leverage = 75.0.toBigDecimal(),
                stopLossPrice = 34000.0.toBigDecimal()
            )
        }
    }

    @Test
    fun placeStopTest() {
        runBlocking {
            byBitAPI.makeStopOrder(
                Symbol.BTCUSDT,
                false,
                0.022.toBigDecimal(),
                38828.5.toBigDecimal() * 0.95.toBigDecimal()
            )
        }
    }

    @Test
    fun roundingTest() {
        for (rm in RoundingMode.values()) {
            println(rm.toString() + " " + 0.00324.toBigDecimal().round(BigDecimal("0.0001"), rm))
        }
    }

    @Test
    fun candlesRequestTest() {
        println("""{"op": "subscribe", "args": [${
            CandleInterval.values().map { interval -> Symbol.values().map { symbol -> "\"candle.${interval.byBitId}.$symbol\"" } }.flatten().joinToString(",")
        }]}""")
    }

    @Test
    fun getOrderTest() {
        runBlocking {
            println(byBitAPI.getOrder("9b0e89fc", Symbol.BTCUSDT, true))
        }
    }

    @Test
    fun getMarkPrice() {
        runBlocking {
            println(byBitAPI.getMarkPrice(Symbol.BTCUSDT))
        }
    }
}