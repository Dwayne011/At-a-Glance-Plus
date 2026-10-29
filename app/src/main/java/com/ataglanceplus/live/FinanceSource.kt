package com.ataglanceplus.live

import android.content.Context
import android.util.Log
import com.ataglanceplus.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** One stock's price today. */
data class Quote(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchange: String,
    val price: Double,
    val previousClose: Double,
    /** Today's prices, oldest first, for the small chart. */
    val points: List<Double>,
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() = if (previousClose != 0.0) 100 * change / previousClose else 0.0
    val moved: Double get() = abs(changePercent)
}

/**
 * Prices for the user's tickers from Yahoo Finance's public chart feed: free, no key, worldwide
 * (some exchanges about 15 minutes delayed). It is unofficial and could change without warning;
 * failures leave the last prices in place and say so on the settings screen.
 */
object FinanceSource {

    private const val TAG = "FinanceSource"

    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Refreshes every ticker in [Prefs.financeTickers], in the user's order. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val tickers = Prefs.financeTickers(context)
        if (tickers.isEmpty()) {
            _quotes.value = emptyList()
            _status.value = ""
            return@withContext
        }
        val previous = _quotes.value.associateBy { it.symbol }
        var failed = 0
        val fresh = tickers.mapNotNull { symbol ->
            fetch(symbol) ?: previous[symbol].also { failed++ }
        }
        _quotes.value = fresh
        _status.value = if (failed == 0) "Updated" else "Could not update $failed of ${tickers.size}; showing the last prices"
    }

    /** One ticker's quote, or null if it does not exist or the feed did not answer. */
    fun fetch(symbol: String): Quote? {
        val url = URL(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(symbol, "UTF-8") + "?range=1d&interval=5m"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            // The feed refuses requests without a browser-like agent.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
        }
        return try {
            if (connection.responseCode != 200) return null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val closes = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
            val points = (0 until (closes?.length() ?: 0)).mapNotNull { i ->
                if (closes!!.isNull(i)) null else closes.getDouble(i)
            }
            Quote(
                symbol = meta.optString("symbol", symbol),
                name = meta.optString("longName").ifEmpty { meta.optString("shortName") }.trim().ifEmpty { symbol },
                currency = meta.optString("currency"),
                exchange = meta.optString("exchangeName"),
                price = meta.getDouble("regularMarketPrice"),
                previousClose = meta.optDouble("chartPreviousClose").takeUnless { it.isNaN() }
                    ?: meta.optDouble("previousClose").takeUnless { it.isNaN() } ?: meta.getDouble("regularMarketPrice"),
                points = points,
            )
        } catch (e: Exception) {
            Log.w(TAG, "quote failed for $symbol", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** A chart range for the dashboard, and the step between its prices. */
    enum class Range(val label: String, val range: String, val interval: String) {
        DAY("1D", "1d", "5m"), WEEK("5D", "5d", "30m"), MONTH("1M", "1mo", "1d"), HALF_YEAR("6M", "6mo", "1d"), YEAR("1Y", "1y", "1wk"),
    }

    /** One candle: its start (epoch ms), open, high, low, close and volume. */
    data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    /** A stock over a [Range], with the day's figures, for the dashboard. */
    data class History(
        val quote: Quote,
        /** Prices over the range, oldest first: epoch milliseconds and price. */
        val points: List<Pair<Long, Double>>,
        val candles: List<Candle>,
        /**
         * Volume-weighted average price at each candle (null before any volume): resetting each
         * trading day for intraday ranges, else anchored to the start of the range.
         */
        val vwap: List<Double?>,
        /** Indices of the candles where the VWAP starts again (each trading day, intraday). */
        val vwapStarts: Set<Int>,
        val dayLow: Double?,
        val dayHigh: Double?,
        val yearLow: Double?,
        val yearHigh: Double?,
        val volume: Long?,
        val exchangeName: String,
    ) {
        /** The change over the range: from the day's previous close for one day, else from the first price. */
        val rangeChange: Double get() = quote.price - (points.firstOrNull()?.second ?: quote.previousClose)
    }

    /** [symbol] over [range], or null if the feed did not answer. */
    fun history(symbol: String, range: Range): History? {
        val quote = fetch(symbol) ?: return null
        val url = URL(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(symbol, "UTF-8") + "?range=${range.range}&interval=${range.interval}"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
        }
        return try {
            if (connection.responseCode != 200) return null
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val times = result.optJSONArray("timestamp")
            val ohlc = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            val closes = ohlc?.optJSONArray("close")
            val points = (0 until minOf(times?.length() ?: 0, closes?.length() ?: 0)).mapNotNull { i ->
                if (closes!!.isNull(i)) null else times!!.getLong(i) * 1000 to closes.getDouble(i)
            }
            fun column(name: String) = ohlc?.optJSONArray(name)
            val opens = column("open")
            val highs = column("high")
            val lows = column("low")
            val volumes = column("volume")
            val candles = (0 until minOf(times?.length() ?: 0, closes?.length() ?: 0)).mapNotNull { i ->
                if (listOf(opens, highs, lows, closes).any { it == null || it.isNull(i) }) return@mapNotNull null
                Candle(times!!.getLong(i) * 1000, opens!!.getDouble(i), highs!!.getDouble(i), lows!!.getDouble(i), closes!!.getDouble(i),
                    volumes?.takeUnless { it.isNull(i) }?.getLong(i) ?: 0L)
            }
            // VWAP from the typical price ((high + low + close) / 3), resetting at each trading
            // day in the exchange's own time for intraday candles.
            val offset = meta.optLong("gmtoffset") * 1000
            val intraday = range == Range.DAY || range == Range.WEEK
            var day = Long.MIN_VALUE
            var priceVolume = 0.0
            var volume = 0L
            val vwapStarts = mutableSetOf(0)
            val vwap = candles.mapIndexed { i, c ->
                val today = (c.time + offset) / 86_400_000L
                if (intraday && today != day) {
                    if (day != Long.MIN_VALUE) vwapStarts += i
                    day = today
                    priceVolume = 0.0
                    volume = 0L
                }
                priceVolume += (c.high + c.low + c.close) / 3 * c.volume
                volume += c.volume
                if (volume > 0) priceVolume / volume else null
            }
            fun number(key: String) = meta.optDouble(key).takeUnless { it.isNaN() }
            History(
                quote = quote,
                // One day starts from the previous close, so the line and the change agree.
                points = if (range == Range.DAY && points.isNotEmpty()) listOf(points.first().first to quote.previousClose) + points else points,
                candles = candles,
                vwap = vwap,
                vwapStarts = vwapStarts,
                dayLow = number("regularMarketDayLow"),
                dayHigh = number("regularMarketDayHigh"),
                yearLow = number("fiftyTwoWeekLow"),
                yearHigh = number("fiftyTwoWeekHigh"),
                volume = meta.optLong("regularMarketVolume").takeIf { it > 0 },
                exchangeName = meta.optString("fullExchangeName").ifEmpty { quote.exchange },
            )
        } catch (e: Exception) {
            Log.w(TAG, "history failed for $symbol", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** A match for what the user typed: enough to tell, say, VOD (NASDAQ) from VOD.L (London). */
    data class Match(val symbol: String, val name: String, val exchange: String, val type: String)

    /**
     * Symbols matching a name or ticker, from Yahoo Finance's search feed (stocks, ETFs,
     * funds and indices). Empty if nothing matches or the feed does not answer.
     */
    fun search(query: String): List<Match> {
        if (query.isBlank()) return emptyList()
        val url = URL(
            "https://query1.finance.yahoo.com/v1/finance/search?q=" + URLEncoder.encode(query.trim(), "UTF-8") +
                "&quotesCount=8&newsCount=0&listsCount=0"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
        }
        return try {
            if (connection.responseCode != 200) return emptyList()
            val quotes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("quotes")
                ?: return emptyList()
            (0 until quotes.length()).mapNotNull { i ->
                val q = quotes.optJSONObject(i) ?: return@mapNotNull null
                val symbol = q.optString("symbol").ifEmpty { return@mapNotNull null }
                Match(
                    symbol = symbol,
                    name = q.optString("longname").ifEmpty { q.optString("shortname") }.trim().ifEmpty { symbol },
                    exchange = q.optString("exchDisp").ifEmpty { q.optString("exchange") },
                    type = q.optString("typeDisp"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** The stock's page on Google Finance, where the exchange is known; otherwise a search. */
    fun googleFinanceUrl(quote: Quote): String {
        val base = quote.symbol.substringBefore('.')
        val exchange = when (quote.exchange) {
            "NMS", "NGM", "NCM", "NAS" -> "NASDAQ"
            "NYQ", "NYS" -> "NYSE"
            "PCX" -> "NYSEARCA"
            "ASE" -> "NYSEAMERICAN"
            "LSE" -> "LON"
            "GER" -> "ETR"
            "FRA" -> "FRA"
            "PAR" -> "EPA"
            "AMS" -> "AMS"
            "TOR" -> "TSE"
            "ASX" -> "ASX"
            "HKG" -> "HKG"
            else -> null
        }
        return if (exchange != null) "https://www.google.com/finance/quote/$base:$exchange"
        else "https://www.google.com/finance?q=" + URLEncoder.encode(quote.symbol, "UTF-8")
    }
}
