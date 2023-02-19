package com.strat.analyzer.utils

import com.google.gson.Gson
import com.strat.analyzer.bot.MessageSender
import com.strat.analyzer.db.candle.CandleHandler
import com.strat.analyzer.db.candle.CandleInterval
import com.strat.analyzer.db.candle.Symbol
import com.strat.analyzer.domain.BSInitializer
import com.strat.analyzer.domain.api.BalanceGetter
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences

@Component
class Constants {
    var candleHandler: CandleHandler? = null
    var adminMessageSender: MessageSender? = null
    var bsInitializer: BSInitializer? = null
    var balanceGetter: BalanceGetter? = null

    private val gson = Gson()

    var longEnabled = Preferences.userRoot().getBoolean("longEnabled", true)
        set(value) {
            Preferences.userRoot().putBoolean("longEnabled", value)
            field = value
        }

    var shortEnabled = Preferences.userRoot().getBoolean("shortEnabled", true)
        set(value) {
            Preferences.userRoot().putBoolean("shortEnabled", value)
            field = value
        }

    var dealAmount = Preferences.userRoot().get("dealAmount", "3.0").toBigDecimal()
        set(value) {
            Preferences.userRoot().put("dealAmount", value.toString())
            field = value
        }

    var rsiLength = Preferences.userRoot().getInt("rsiLength", 14)
        set(value) {
            Preferences.userRoot().putInt("rsiLength", value)
            field = value
        }

    var stopPercent = Preferences.userRoot().get("stopPercent", "0.0065").toBigDecimal()
        set(value) {
            Preferences.userRoot().put("stopPercent", value.toString())
            field = value
        }

    var rsiEpsilon = Preferences.userRoot().get("rsiEpsilon", "0.5").toDouble()
        set(value) {
            Preferences.userRoot().put("rsiEpsilon", value.toString())
            field = value
        }

    var stopBUPercent = Preferences.userRoot().get("stopBUPercent", "0.0005").toBigDecimal()
        set(value) {
            Preferences.userRoot().put("stopBUPercent", value.toString())
            field = value
        }

    var takes = gson.fromJson<List<BigDecimal>>(Preferences.userRoot().get("takes", "[0.28, 0.5, 0.75]"))
        set(value) {
            Preferences.userRoot().put("takes", gson.toJson(value))
            field = value
        }

    var timeSleeps = gson.fromJson<List<BigDecimal>>(Preferences.userRoot().get("timeSleeps", "[5, 10, 15]"))
        set(value) {
            Preferences.userRoot().put("timeSleeps", gson.toJson(value))
            field = value
        }

    var rsiLevels = gson.fromJson<List<Double>>(Preferences.userRoot().get("rsiLevels", "[30.0, 70.0]"))
        set(value) {
            Preferences.userRoot().put("rsiLevels", gson.toJson(value))
            field = value
        }

    var diverLevels = gson.fromJson<List<Int>>(Preferences.userRoot().get("diverLevels", "[5, 60]"))
        set(value) {
            Preferences.userRoot().put("diverLevels", gson.toJson(value))
            field = value
        }

    var deals = gson.fromJson<List<Symbol>>(Preferences.userRoot().get("deals", ""))
        set(value) {
            Preferences.userRoot().put("deals", gson.toJson(value))
            field = value
        }

    var lastUpdate = Preferences.userRoot().get("lastUpdate", "0").toLong()
        set(value) {
            Preferences.userRoot().put("lastUpdate", value.toString())
            field = value
        }

    var timeFrame = Preferences.userRoot().get("timeFrame", "3").toLong()
        set(value) {
            Preferences.userRoot().put("timeFrame", value.toString())
            field = value
        }

    var leverage = Preferences.userRoot().get("leverage", "25").toBigDecimal()
        set(value) {
            Preferences.userRoot().put("leverage", value.toString())
            field = value
        }

    val coroutinesCount = AtomicLong()
    val candlesLastTime: MutableMap<Pair<Symbol, CandleInterval>, AtomicLong> = Collections.synchronizedMap(mutableMapOf<Pair<Symbol, CandleInterval>, AtomicLong>())
    val candlesStartTime: MutableMap<Pair<Symbol, CandleInterval>, AtomicLong> = Collections.synchronizedMap(mutableMapOf<Pair<Symbol, CandleInterval>, AtomicLong>())
}