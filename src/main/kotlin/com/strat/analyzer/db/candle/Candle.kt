package com.strat.analyzer.db.candle

import com.strat.analyzer.domain.api.ByBitAPICandle
import com.strat.analyzer.domain.api.ByBitWSCandle
import java.time.Duration

data class Candle(
    val symbol: Symbol,
    val openTime: Long,
    var open: Double,
    var high: Double,
    var low: Double,
    var close: Double,
    var volume: Double,
    val interval: CandleInterval,
    val stockExchange: StockExchange
) {
    constructor(symbol: Symbol, candlestickEvent: ByBitWSCandle, interval: CandleInterval) : this(
        symbol,
        candlestickEvent.start,
        candlestickEvent.open.toDouble(),
        candlestickEvent.high.toDouble(),
        candlestickEvent.low.toDouble(),
        candlestickEvent.close.toDouble(),
        candlestickEvent.volume.toDouble(),
        interval,
        StockExchange.BYBIT
    )

    constructor(symbol: Symbol, candlestick: ByBitAPICandle, interval: CandleInterval) : this(
        symbol,
        candlestick.openTime * 1000,
        candlestick.open.toDouble(),
        candlestick.high.toDouble(),
        candlestick.low.toDouble(),
        candlestick.close.toDouble(),
        candlestick.volume.toDouble(),
        interval,
        StockExchange.BYBIT
    )

    fun updateCandleByCandlestickEvent(candlestickEvent: ByBitWSCandle) {
        open = candlestickEvent.open.toDouble()
        high = candlestickEvent.high.toDouble()
        low = candlestickEvent.low.toDouble()
        close = candlestickEvent.close.toDouble()
        volume = candlestickEvent.volume.toDouble()
    }
}

enum class Symbol {
    BTCUSDT, ETHUSDT, EOSUSDT, XRPUSDT, BCHUSDT, LTCUSDT, XTZUSDT, LINKUSDT, ADAUSDT, DOTUSDT,
    UNIUSDT, XEMUSDT, SUSHIUSDT, AAVEUSDT, DOGEUSDT, MATICUSDT, ETCUSDT, BNBUSDT, FILUSDT, SOLUSDT,
    XLMUSDT, TRXUSDT, VETUSDT, THETAUSDT, COMPUSDT, AXSUSDT, SANDUSDT, MANAUSDT, KSMUSDT, ATOMUSDT,
    AVAXUSDT, CHZUSDT, CRVUSDT, ENJUSDT, GRTUSDT, SHIB1000USDT, YFIUSDT, BSVUSDT, ICPUSDT, FTMUSDT,
    ALGOUSDT, DYDXUSDT, NEARUSDT, SRMUSDT, OMGUSDT, IOSTUSDT, DASHUSDT, FTTUSDT, BITUSDT, GALAUSDT,
    CELRUSDT, HBARUSDT, ONEUSDT, C98USDT, AGLDUSDT, MKRUSDT, COTIUSDT, ALICEUSDT, EGLDUSDT, RENUSDT,
    TLMUSDT, RUNEUSDT, ILVUSDT, FLOWUSDT, WOOUSDT, LRCUSDT, ENSUSDT, IOTXUSDT, CHRUSDT, BATUSDT,
    STORJUSDT, SNXUSDT, SLPUSDT, ANKRUSDT, LPTUSDT, QTUMUSDT, CROUSDT, SXPUSDT, YGGUSDT, ZECUSDT,
    IMXUSDT, SFPUSDT, AUDIOUSDT, ZENUSDT, SKLUSDT, GTCUSDT, LITUSDT, CVCUSDT, RNDRUSDT, SCUSDT, RSRUSDT,
    STXUSDT, MASKUSDT, CTKUSDT, BICOUSDT, REQUSDT, KLAYUSDT, SPELLUSDT, ANTUSDT, DUSKUSDT, ARUSDT,
    REEFUSDT, XMRUSDT, PEOPLEUSDT, IOTAUSDT, ICXUSDT, CELOUSDT, WAVESUSDT, RVNUSDT, KNCUSDT, KAVAUSDT,
    ROSEUSDT, DENTUSDT, CREAMUSDT, LOOKSUSDT, JASMYUSDT, HNTUSDT, ZILUSDT, NEOUSDT, RAYUSDT, CKBUSDT,
    SUNUSDT, JSTUSDT, BANDUSDT, RSS3USDT, OCEANUSDT, API3USDT, PAXGUSDT, KDAUSDT, APEUSDT, GMTUSDT,
    OGNUSDT, BSWUSDT, CTSIUSDT, HOTUSDT, ARPAUSDT, ALPHAUSDT, STMXUSDT, DGBUSDT, ZRXUSDT, GLMRUSDT,
    SCRTUSDT, BAKEUSDT, LINAUSDT, ASTRUSDT, FXSUSDT, MINAUSDT, BNXUSDT, BOBAUSDT, ACHUSDT, BALUSDT,
    MTLUSDT, CVXUSDT, DODOUSDT, TOMOUSDT, XCNUSDT, GSTUSDT, DARUSDT, FLMUSDT, GALUSDT, FITFIUSDT, CTCUSDT,
    AKROUSDT, UNFIUSDT, LUNA2USDT, OPUSDT, ONTUSDT, BLZUSDT, TRBUSDT, BELUSDT
}

enum class StockExchange {
    BINANCE, BYBIT
}

enum class CandleInterval(val intervalId: String, val duration: Duration, val byBitId: String, val index: Int) {
    ONE_MINUTE("1m", Duration.ofMinutes(1), "1", 0),
    THREE_MINUTES("3m", Duration.ofMinutes(3), "3", 1),
    FIVE_MINUTES("5m", Duration.ofMinutes(5), "5", 2),
    //FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), "15"),
    //HALF_HOURLY("30m", Duration.ofMinutes(30), "30"),
    //HOURLY("1h", Duration.ofHours(1), "60"),
    /*TWO_HOURLY(
        "2h", Duration.ofHours(2)
    ),
    FOUR_HOURLY("4h", Duration.ofHours(4)),
    SIX_HOURLY("6h", Duration.ofHours(6)),
    EIGHT_HOURLY("8h", Duration.ofHours(8)),
    TWELVE_HOURLY("12h", Duration.ofHours(12)),
    DAILY("1d", Duration.ofDays(1)),
    THREE_DAILY("3d", Duration.ofDays(3)),
    WEEKLY(
        "1w", Duration.ofDays(7)
    ),
    MONTHLY("1M", Duration.ofDays(30))*/;

    companion object {
        fun valueOfIntervalId(intervalId: String): CandleInterval? =
            values().firstOrNull { it.intervalId == intervalId }

        fun valueOfByBitId(byBitId: String): CandleInterval? =
            values().firstOrNull { it.byBitId == byBitId }
    }
}