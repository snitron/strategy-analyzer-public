package com.strat.analyzer.db.candle

import com.strat.analyzer.domain.api.ByBitWSCandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.logging.Logger

typealias ByBitCandleUpdate = Triple<Symbol, ByBitWSCandle, CandleInterval>

class CandlesFlow(process: suspend (ByBitCandleUpdate?) -> (Unit), val scope: CoroutineScope) {
    private val flow = MutableStateFlow<ByBitCandleUpdate?>(null)

    companion object {
        private val logger = Logger.getLogger("CANDLES_FLOW")
    }

    init {
        scope.launch { flow.collectLatest(process) }
    }

    fun push(update: ByBitCandleUpdate) {
        var success = flow.compareAndSet(flow.value, update)

        if (!success) {
            logger.warning("An error occurred while updating $update. Forcing the process...")
            success = flow.tryEmit(update)

            if (!success) {
                logger.warning("Shit again. Double forcing the process...")

                scope.launch { flow.emit(update) }
            }
        }
    }
}
