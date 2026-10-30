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
    /** The price outside regular hours (pre-market, after hours or overnight), while it differs from the close. */
    val extended: ExtendedPrice? = null,
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() = if (previousClose != 0.0) 100 * change / previousClose else 0.0
    /** The bigger of the day's move and the move outside regular hours, in percent. */
    val moved: Double get() = maxOf(abs(changePercent), abs(extended?.changePercent ?: 0.0))
}

/** When a price outside regular hours was made. */
enum class ExtendedSession(val label: String) { PRE("Pre-market"), POST("After hours"), OVERNIGHT("Overnight") }

/**
 * A price outside regular hours. Its change is from the last regular close ([close]), as eToro
 * shows it: an overnight fall is measured from the day's close, not the day before's.
 */
data class ExtendedPrice(val session: ExtendedSession, val price: Double, val close: Double) {
    val change: Double get() = price - close
    val changePercent: Double get() = if (close != 0.0) 100 * change / close else 0.0
}

/**
 * Prices for the user's tickers from Yahoo Finance's public chart feed: free, no key, worldwide
 * (some exchanges about 15 minutes delayed). It is unofficial and could change without warning;
 * failures leave the last prices in place and say so on the settings screen.
 */
object FinanceSource {

    /** When prices were last fetched (epoch ms): a dismissed finance card comes back after the next fetch. */
    @Volatile var refreshedAt = 0L
        private set

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
        refreshedAt = System.currentTimeMillis()
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
                extended = extended(meta, System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "quote failed for $symbol", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The price outside regular hours, from the feed's full-day price, while it differs from the
     * regular close. Only for listings with pre- and after-hours trading (US ones). Overnight is
     * the US overnight session (20:00 to 04:00 New York time, Sunday night to Friday morning);
     * outside it and the pre- and after-hours sessions, the last such price counts as after hours.
     */
    private fun extended(meta: JSONObject, now: Long): ExtendedPrice? {
        if (!meta.optBoolean("hasPrePostMarketData")) return null
        val full = meta.optDouble("fulldayPrice").takeUnless { it.isNaN() } ?: return null
        val close = meta.optDouble("regularMarketPrice").takeUnless { it.isNaN() } ?: return null
        if (abs(full - close) < 0.00001) return null
        val periods = meta.optJSONObject("currentTradingPeriod")
        fun inside(name: String): Boolean {
            val period = periods?.optJSONObject(name) ?: return false
            return now >= period.optLong("start") * 1000 && now < period.optLong("end") * 1000
        }
        val session = when {
            inside("regular") -> return null
            inside("pre") -> ExtendedSession.PRE
            inside("post") -> ExtendedSession.POST
            meta.optString("exchangeTimezoneName") == "America/New_York" && overnight(now) -> ExtendedSession.OVERNIGHT
            else -> ExtendedSession.POST
        }
        return ExtendedPrice(session, full, close)
    }

    /** Whether the US overnight session is open: 20:00 to 04:00 New York time, Sunday night to Friday morning. */
    private fun overnight(now: Long): Boolean {
        val at = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("America/New_York"))
        val day = at.dayOfWeek
        return (at.hour >= 20 && day != java.time.DayOfWeek.FRIDAY && day != java.time.DayOfWeek.SATURDAY) ||
            (at.hour < 4 && day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY)
    }

    /** What each candle's VWAP is worked out from (eToro's "Source"). */
    enum class VwapSource(val label: String) {
        OPEN("Open"), HIGH("High"), LOW("Low"), CLOSE("Close"), HL2("(H + L)/2"), HLC3("(H + L + C)/3"), OHLC4("(O + H + L + C)/4");

        fun of(c: Candle): Double = when (this) {
            OPEN -> c.open
            HIGH -> c.high
            LOW -> c.low
            CLOSE -> c.close
            HL2 -> (c.high + c.low) / 2
            HLC3 -> (c.high + c.low + c.close) / 3
            OHLC4 -> (c.open + c.high + c.low + c.close) / 4
        }
    }

    /** When the VWAP starts again (eToro's "Anchor period"), in the exchange's own time. */
    enum class VwapAnchor(val label: String) {
        SESSION("Session"), WEEK("Week"), MONTH("Month"), QUARTER("Quarter"), YEAR("Year"), RANGE("Start of the range");

        /** Which period [date] falls in; the VWAP starts again where this changes. */
        fun period(date: java.time.LocalDate): Long = when (this) {
            SESSION -> date.toEpochDay()
            WEEK -> date.with(java.time.DayOfWeek.MONDAY).toEpochDay()
            MONTH -> date.year * 12L + date.monthValue
            QUARTER -> date.year * 4L + (date.monthValue - 1) / 3
            YEAR -> date.year.toLong()
            RANGE -> 0L
        }
    }

    /** The VWAP's settings for the dashboard chart. */
    data class VwapSettings(val source: VwapSource, val anchor: VwapAnchor, val extendedHours: Boolean)

    /**
     * Volume-weighted average price at each of [candles] (null before any volume), and the
     * indices where it starts again. [offsetMs] is the exchange's offset from UTC.
     */
    fun vwap(candles: List<Candle>, offsetMs: Long, source: VwapSource, anchor: VwapAnchor): Pair<List<Double?>, Set<Int>> {
        var period = Long.MIN_VALUE
        var priceVolume = 0.0
        var volume = 0L
        val starts = mutableSetOf(0)
        val values = candles.mapIndexed { i, c ->
            val date = java.time.LocalDate.ofEpochDay(Math.floorDiv(c.time + offsetMs, 86_400_000L))
            val current = anchor.period(date)
            if (current != period) {
                if (period != Long.MIN_VALUE) starts += i
                period = current
                priceVolume = 0.0
                volume = 0L
            }
            priceVolume += source.of(c) * c.volume
            volume += c.volume
            if (volume > 0) priceVolume / volume else null
        }
        return values to starts
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
        /** Volume-weighted average price at each candle (null before any volume), per [VwapSettings]. */
        val vwap: List<Double?>,
        /** Indices of the candles where the VWAP starts again. */
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

    /**
     * [symbol] over [range], or null if the feed did not answer. With extended hours in [settings],
     * the intraday ranges include pre-market and after-hours candles (the feed has none for the
     * overnight session).
     */
    fun history(symbol: String, range: Range, settings: VwapSettings): History? {
        val quote = fetch(symbol) ?: return null
        val prePost = settings.extendedHours && range.interval.endsWith("m")
        val url = URL(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(symbol, "UTF-8") + "?range=${range.range}&interval=${range.interval}" +
                if (prePost) "&includePrePost=true" else ""
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
            val (average, starts) = vwap(candles, meta.optLong("gmtoffset") * 1000, settings.source, settings.anchor)
            fun number(key: String) = meta.optDouble(key).takeUnless { it.isNaN() }
            History(
                quote = quote,
                // One day starts from the previous close, so the line and the change agree.
                points = if (range == Range.DAY && points.isNotEmpty()) listOf(points.first().first to quote.previousClose) + points else points,
                candles = candles,
                vwap = average,
                vwapStarts = starts,
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
