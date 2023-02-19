package com.strat.analyzer

import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.domain.Analyzer
import com.strat.analyzer.domain.strategies.PNLStrategy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class PNLStrategyTests @Autowired constructor(
    val pnlStrategy: PNLStrategy,
    val analyzer: Analyzer
) {
    @Test
    fun testPNLStopsCalculation() {
        println(pnlStrategy.calculateStops(
            entry = 0.53100000.toBigDecimal(),
            leverage = 50.0.toBigDecimal(),
            qty = 471.00000000.toBigDecimal(),
            side = false,
            symbol = Symbol.ATOMUSDT
        ))
    }
}