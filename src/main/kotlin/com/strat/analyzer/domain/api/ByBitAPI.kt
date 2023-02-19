package com.strat.analyzer.domain.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.strat.analyzer.db.candle.*
import com.strat.analyzer.domain.api.entity.CreateOrderResponse
import com.strat.analyzer.utils.*
import com.strat.analyzer.utils.fromJson
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZonedDateTime
import java.util.*
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface BalanceGetter {
    suspend fun getBalance(coin: String = "USDT"): BigDecimal
}

data class ByBitWSCandle(
    val id: Long,
    val open: BigDecimal,
    val close: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val volume: BigDecimal,
    var start: Long,
    val timestamp: Long
)

data class ByBitAPICandle(
    val id: Long,
    val symbol: Symbol,
    val period: String,
    val open: BigDecimal,
    val close: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val volume: BigDecimal,
    @SerializedName("start_at")
    val startAt: Long,
    @SerializedName("open_time")
    val openTime: Long
)

@EnableScheduling
@Component
class ByBitAPI(
    @Value("\${bybit.credentials.token}")
    private val token: String,
    @Value("\${bybit.credentials.secret}")
    private val secret: String,
    @Value("\${test}")
    private val test: String,
    private val constants: Constants
): BalanceGetter {
    private val okHttpClient = OkHttpClient.Builder()
        .build()

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            logger.info(response.message)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)

            try {
                //println(text)
                val json = JSONObject(text)

                if (json.has("topic")) {
                    val topic = json.getString("topic").split(".")

                    constants.candleHandler?.gotUpdate(
                        Symbol.valueOf(topic[2]),
                        CandleInterval.valueOfByBitId(topic[1])!!,
                        gson.fromJson(json.getJSONArray("data").getJSONObject(0).toString(), ByBitWSCandle::class.java)
                            .apply { start *= 1000L }
                    )
                } else {
                    //logger.info(text)
                }
            } catch (e: Exception) {
                logger.warning(e.message)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            reconnectSockets()
            logger.warning(t.message)

            super.onFailure(webSocket, t, response)
        }
    }

    private var webSocketClient: WebSocket? = null

    private val logger = Logger.getLogger("BYBIT-API")

    private val baseQueryMap = TreeMap<String, String> { s1, s2 -> s1.compareTo(s2) }
        .apply { this["api_key"] = token }

    private val BASE_URL = "https://api.bybit.com"
    //private val BASE_URL = "https://api-testnet.bybit.com"
    private val EPSILON = BigDecimal(0.1)
    private val PLUS_EPSILON = BigDecimal.ONE + EPSILON
    private val MINUS_EPSILON = BigDecimal.ONE - EPSILON

    private val mac = Mac.getInstance("HmacSHA256")
    private val emptyRequestBody = ByteArray(0).toRequestBody(null)

    private fun genQueryString(params: TreeMap<String, String>, secret: String): String {
        val keySet: Set<String> = params.keys
        val iter = keySet.iterator()
        val sb = StringBuilder()
        while (iter.hasNext()) {
            val key = iter.next()
            sb.append(key + "=" + params[key])
            sb.append("&")
        }
        sb.deleteCharAt(sb.length - 1)
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return sb.toString() + "&sign=" + bytesToHex(mac.doFinal(sb.toString().toByteArray()))
    }

    private fun bytesToHex(hash: ByteArray): String {
        val hexString = StringBuffer()
        for (i in hash.indices) {
            val hex = Integer.toHexString(0xff and hash[i].toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()
    }

    private fun calculateQty(asset: String, leverage: BigDecimal, amount: BigDecimal, price: BigDecimal): BigDecimal {
        return (amount * leverage).setScale(assetsData[asset]!!.second, RoundingMode.HALF_EVEN) / price
    }

    final val assetsData = mutableMapOf<String, Triple<BigDecimal, Int, BigDecimal>>() // leverage, min_qty_exp, tick_price
    private val gson = Gson()

    init {
        val data = OkHttpUtils.makeRequest(
            OkHttpClient.Builder().build(),
            Request.Builder().url("$BASE_URL/v2/public/symbols").get().build()
        )

        val jsonData = data!!.getJSONArray("result")

        for (i in 0 until jsonData.length()) {
            val jsonObject = jsonData.getJSONObject(i)

            val minQty = jsonObject.getJSONObject("lot_size_filter").getBigDecimal("min_trading_qty").toString()

            assetsData[jsonObject.getString("name")] = Triple(
                jsonObject.getJSONObject("leverage_filter").getBigDecimal("max_leverage"),
                if (minQty.indexOf(".") == -1) 0 else minQty.length - minQty.indexOf(".") - 1,
                jsonObject.getJSONObject("price_filter").getBigDecimal("tick_size")
            )
        }

        println(assetsData.keys)

        constants.balanceGetter = this
    }

    @EventListener(ApplicationReadyEvent::class)
    fun iinit() {
        if (test != "true") {
            //constants.candleHandler?.deleteAll()

            webSocketClient = okHttpClient.newWebSocket(
                Request.Builder().url("ws://stream.bybit.com/realtime_public").build(),
                wsListener
            )

            CandleInterval.values().forEach { interval ->
                Symbol.values().forEach { symbol ->
                    try {
                        logger.info("Downloading $symbol $interval")

                        val candles = runBlocking { getCandles(symbol, interval) }
                            .map { Candle(symbol, it, interval) }

                        constants.bsInitializer?.initBaseSeries(candles)

                        logger.info("Finished downloading $symbol $interval")

                        webSocketClient?.send("""{"op": "subscribe", "args": [${
                            "\"candle.${interval.byBitId}.$symbol\""
                        }]}""".apply { println(this) })
                    } catch (e: Exception) {
                        println(symbol)
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    @Async
    @Scheduled(fixedRate = 30_000)
    fun ping() {
        try {
            webSocketClient?.send("""{"op":"ping"}""")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //@Async
    //@Scheduled(cron = "0 30 * * * *")
    fun reconnectSockets() {
        GlobalScope.launch {
            webSocketClient?.close(1000, null)

            delay(500)
            webSocketClient = okHttpClient.newWebSocket(
                Request.Builder().url("wss://stream.bybit.com/realtime_public").build(),
                wsListener
            )

            webSocketClient?.send("""{"op": "subscribe", "args": [${
                CandleInterval.values()
                    .map { interval -> Symbol.values().map { symbol -> "\"candle.${interval.byBitId}.$symbol\"" } }
                    .flatten().joinToString(",")
            }]}""".apply { println(this) })
        }
    }

    suspend fun makeOrder(
        symbol: Symbol,
        side: Boolean,
        amount: BigDecimal? = null,
        qty: BigDecimal? = null,
        close: BigDecimal? = null,
        leverage: BigDecimal = assetsData[symbol.name]!!.first,
        reduceOnly: Boolean = false,
        stopLossPrice: BigDecimal? = null,
        positionIdx: String? = null
    ): CreateOrderResponse? {

        DebugUtils.print("Trying to open the deal",  symbol, side, amount, qty, close, leverage, reduceOnly)

        //TODO: slow?
        setMarginType(symbol, leverage.toDouble())
        setLeverage(symbol, leverage.toDouble())

        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name
        queryMap["qty"] =
            qty?.round(assetsData[symbol.name]!!.second)?.toString() ?: calculateQty(symbol.name, leverage, amount!!, close!!).toString()
        queryMap["side"] = if (side) "Buy" else "Sell"
        queryMap["time_in_force"] = "GoodTillCancel"
        queryMap["order_type"] = "Market"
        queryMap["reduce_only"] = reduceOnly.toString()
        queryMap["close_on_trigger"] = "false"
        if (stopLossPrice != null) { queryMap["stop_loss"] = stopLossPrice.round(assetsData[symbol.name]!!.third).toString() }

        if (positionIdx == null) {queryMap["position_idx"] = if (side) "1" else "2"}
        else{queryMap["position_idx"] = positionIdx}

        return try {
            gson.fromJson(
            JSONObject(makeRequest("/private/linear/order/create", queryMap)!!).getJSONObject("result").toString(),
            CreateOrderResponse::class.java
        )
        } catch (e: Exception) { null }
    }

    suspend fun makeStopOrder(
        symbol: Symbol,
        side: Boolean,
        qty: BigDecimal,
        price: BigDecimal,
        leverage: BigDecimal = assetsData[symbol.name]!!.first,
        basePriceMultiplier: Boolean = side
    ) {
        setMarginType(symbol, leverage.toDouble())
        setLeverage(symbol, leverage.toDouble())

        DebugUtils.print("Trying to place the stop",  symbol, side, qty, leverage, price, (price * (if (side) PLUS_EPSILON else MINUS_EPSILON)).toString())

        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name
        queryMap["qty"] = qty.round(assetsData[symbol.name]!!.second).toString()
        queryMap["side"] = if (side) "Buy" else "Sell"
        queryMap["time_in_force"] = "GoodTillCancel"
        queryMap["order_type"] = "Market"
        queryMap["reduce_only"] = "true"
        queryMap["trigger_by"] = "LastPrice"
        queryMap["close_on_trigger"] = "false"
        queryMap["stop_px"] = price.round(assetsData[symbol.name]!!.third).toString()
        queryMap["base_price"] = (price
                * (if (basePriceMultiplier) MINUS_EPSILON else PLUS_EPSILON)).round(assetsData[symbol.name]!!.third).toString()

        makeRequest("/private/linear/stop-order/create", queryMap)
    }

    suspend fun makeLimitOrder(
        symbol: Symbol,
        side: Boolean,
        price: BigDecimal,
        qty: BigDecimal,
        leverage: BigDecimal = assetsData[symbol.name]!!.first,
        reduceOnly: Boolean = false
    ): CreateOrderResponse? {

        DebugUtils.print("Trying to place limit order",  symbol, side, qty, leverage, reduceOnly)

        //TODO: slow?
        setMarginType(symbol, leverage.toDouble())
        setLeverage(symbol, leverage.toDouble())

        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name
        queryMap["qty"] = qty.round(assetsData[symbol.name]!!.second).toString()
        queryMap["side"] = if (side) "Buy" else "Sell"
        queryMap["time_in_force"] = "GoodTillCancel"
        queryMap["order_type"] = "Limit"
        queryMap["price"] = price.round(assetsData[symbol.name]!!.third).toString()
        queryMap["reduce_only"] = reduceOnly.toString()
        queryMap["close_on_trigger"] = "false"
        queryMap["position_idx"] = if (!side) "1" else "2"


        return gson.fromJson(
            JSONObject(makeRequest("/private/linear/order/create", queryMap)!!).getJSONObject("result").toString(),
            CreateOrderResponse::class.java
        )
    }

    suspend fun cancelAllActiveOrders(symbol: Symbol) {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name

        makeRequest("/private/linear/order/cancel-all", queryMap)
    }

    suspend fun cancelAllStops(symbol: Symbol) {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name

        makeRequest("/private/linear/stop-order/cancel-all", queryMap)
    }

    suspend fun setMarginType(symbol: Symbol, leverage: Double): String? {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name
        queryMap["is_isolated"] = "true"
        queryMap["buy_leverage"] = leverage.toString()
        queryMap["sell_leverage"] = leverage.toString()

        return try { makeRequest("/private/linear/position/switch-isolated", queryMap) } catch (e: Exception) { e.printStackTrace(); return null }
    }

    suspend fun setLeverage(symbol: Symbol, leverage: Double): String? {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name
        queryMap["buy_leverage"] = leverage.toString()
        queryMap["sell_leverage"] = leverage.toString()

        return try { makeRequest("/private/linear/position/set-leverage", queryMap) } catch (e: Exception) { e.printStackTrace(); return null }
    }


    suspend fun getLeverage(symbol: Symbol): Double {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["symbol"] = symbol.name

        val json = JSONObject(makeGetRequest("/public/linear/risk-limit", queryMap)!!).getJSONArray("result")
        return json.getJSONObject(0).getDouble("max_leverage")
    }

    override suspend fun getBalance(coin: String): BigDecimal {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>

        queryMap["coin"] = coin

        return JSONObject(makeGetRequest("/v2/private/wallet/balance", queryMap)!!)
            .getJSONObject("result")
            .getJSONObject(coin)
            .getBigDecimal("available_balance")
    }

    suspend fun getCandles(symbol: Symbol, interval: CandleInterval): List<ByBitAPICandle> {
        val queryMap = TreeMap<String, String>()
        queryMap["symbol"] = symbol.name
        queryMap["interval"] = interval.byBitId
        queryMap["from"] = (Instant.now().epochSecond - interval.duration.seconds * 190).toString()

        return JSONObject(makeGetRequest("/public/linear/kline", queryMap)!!)
            .getJSONArray("result").toString().let { gson.fromJson(it) }
    }

    suspend fun getOrder(orderId: String, symbol: Symbol, side: Boolean): Pair<BigDecimal, BigDecimal> /*liq_price, price*/ {
        //e7f881d5-7f25-49de-9035-b5a74d26fe8a

        println(orderId)
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>
        queryMap["symbol"] = symbol.name
        queryMap["order_id"] = orderId

        return JSONObject(makeGetRequest("/private/linear/position/list", queryMap)!!)
            .getJSONArray("result")
            /*.map { it as JSONObject }
            .first { it.getString("side") == (if (side) "Buy" else "Sell") }*/
            .let {
                for (i in 0 until it.length()) {
                    val json = it.getJSONObject(i)

                    if (json.getString("side") == (if (side) "Buy" else "Sell")) {
                        return json.getBigDecimal("liq_price") to json.getBigDecimal("entry_price")
                    }
                }

                return BigDecimal.ZERO to BigDecimal.ZERO
            }
    }

    suspend fun getMarkPrice(symbol: Symbol): BigDecimal {
        val queryMap = baseQueryMap.clone() as TreeMap<String, String>
        queryMap["symbol"] = symbol.name
        queryMap["interval"] = "1"
        queryMap["from"] = Instant.now().epochSecond.let { it / 60 * 60 }.toString()

        return JSONObject(makeGetRequest("/public/linear/mark-price-kline", queryMap)!!)
            .getJSONArray("result")
            .getJSONObject(0)
            .getBigDecimal("close")
    }

    private suspend fun makeRequest(endpoint: String, map: TreeMap<String, String>): String? {
        map["timestamp"] = ZonedDateTime.now().toInstant().toEpochMilli().toString()

        println(map)

        val request = Request.Builder()
            .post(emptyRequestBody)
            .url("$BASE_URL$endpoint?${genQueryString(map, secret)}")
            .build()

        return OkHttpUtils.makeAsyncRequest(
            okHttpClient,
            request
        )
    }

    private suspend fun makeGetRequest(endpoint: String, map: TreeMap<String, String>): String? {
        map["timestamp"] = ZonedDateTime.now().toInstant().toEpochMilli().toString()

        val request = Request.Builder()
            .get()
            .url("$BASE_URL$endpoint?${genQueryString(map, secret)}")
            .build()

        return OkHttpUtils.makeAsyncRequest(
            okHttpClient,
            request
        )
    }
}