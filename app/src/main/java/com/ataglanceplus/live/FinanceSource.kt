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
        // All the stocks in one request. Only a stock the batch left out is asked for on its own;
        // when Yahoo refused the batch, none are, so as not to ask again at once.
        val batch = fetchAll(tickers, context)
        val fresh = tickers.mapNotNull { symbol ->
            (batch?.get(symbol.uppercase()) ?: if (batch != null) fetch(symbol, context) else null)
                ?: previous[symbol].also { failed++ }
        }
        refreshedAt = System.currentTimeMillis()
        _quotes.value = fresh
        _status.value = if (failed == 0) "Updated" else "Could not update $failed of ${tickers.size}; showing the last prices"
    }

    /**
     * A fresher quote fetched elsewhere (the stock screen, as it opens, refreshes and updates
     * live): it replaces the card's copy of that stock, so the card shows the same price as the
     * screen. A stock the card does not follow is left out. Nothing changes if it is the same.
     */
    fun publish(quote: Quote) {
        val current = _quotes.value
        val i = current.indexOfFirst { it.symbol.equals(quote.symbol, ignoreCase = true) }
        if (i < 0 || current[i] == quote) return
        _quotes.value = current.toMutableList().also { it[i] = quote }
    }

    /** Yahoo's two hosts: the second is tried when the first refuses or fails. */
    private val HOSTS = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")

    /** Why the last request to Yahoo failed (its reply, or the network error), or null while it answers. */
    @Volatile var problem: String? = null
        private set
    /** When Yahoo last answered (epoch ms). */
    @Volatile var answeredAt = 0L
        private set
    /** Requests in a row that Yahoo refused or that failed. */
    @Volatile var failures = 0
        private set
    @Volatile private var logged: String? = null

    /**
     * How long the stock screen waits before asking again: [normalMs] while Yahoo answers; after
     * refusals 30 seconds, then 1, 2 and at most 5 minutes, so a rate limit can lift instead of
     * being hit again every 15 seconds.
     */
    fun waitMs(normalMs: Long): Long = if (failures == 0) normalMs else minOf(30_000L shl (failures - 1).coerceAtMost(4), 5 * 60_000L)

    /**
     * A GET of [path] from Yahoo's chart feed: the body, or null when neither host answered. Each
     * change between answering and failing (with Yahoo's status, such as "HTTP 429, too many
     * requests") goes in the debug history when [context] is given.
     */
    private fun yahoo(path: String, context: Context?): String? {
        var why = "no answer"
        for (host in HOSTS) {
            val connection = (URL(host + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                // The feed refuses requests without a browser-like agent.
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
            }
            try {
                val code = connection.responseCode
                if (code == 200) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    failures = 0
                    problem = null
                    answeredAt = System.currentTimeMillis()
                    if (logged != null && context != null) {
                        logged = null
                        com.ataglanceplus.DebugLog.add(context, com.ataglanceplus.DebugLog.Kind.FETCH, "Prices: Yahoo is answering again")
                    }
                    return body
                }
                // No such stock: Yahoo answered, so it is not a problem with the feed.
                if (code == 404) return null
                why = "HTTP $code" + when (code) { 429 -> ", too many requests"; 401, 403 -> ", refused"; in 500..599 -> ", Yahoo's error"; else -> "" }
            } catch (e: Exception) {
                why = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            } finally {
                connection.disconnect()
            }
        }
        failures++
        problem = why
        if (logged != why && context != null) {
            logged = why
            com.ataglanceplus.DebugLog.add(context, com.ataglanceplus.DebugLog.Kind.FETCH, "Prices: Yahoo did not answer ($why); showing the last prices")
        }
        return null
    }

    /** One ticker's quote, or null if it does not exist or the feed did not answer. */
    fun fetch(symbol: String, context: Context? = null): Quote? {
        val body = yahoo("/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") + "?range=1d&interval=5m", context) ?: return null
        return try {
            quoteFrom(JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0), symbol)
        } catch (e: Exception) {
            Log.w(TAG, "quote failed for $symbol", e)
            null
        }
    }

    /**
     * Several tickers' quotes in one request (Yahoo's spark feed, the same day's data as [fetch]),
     * by upper-case symbol; null when Yahoo did not answer. One request instead of one each keeps
     * the card's refresh to a single call, as Yahoo limits how often an address may ask.
     */
    fun fetchAll(symbols: List<String>, context: Context? = null): Map<String, Quote>? {
        if (symbols.isEmpty()) return emptyMap()
        val list = symbols.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        val body = yahoo("/v7/finance/spark?symbols=$list&range=1d&interval=5m", context) ?: return null
        return try {
            val results = JSONObject(body).getJSONObject("spark").getJSONArray("result")
            (0 until results.length()).mapNotNull { i ->
                val entry = results.getJSONObject(i)
                val symbol = entry.optString("symbol")
                val response = entry.optJSONArray("response")?.optJSONObject(0) ?: return@mapNotNull null
                runCatching { symbol.uppercase() to quoteFrom(response, symbol) }.getOrNull()
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "batch quotes failed", e)
            emptyMap()
        }
    }

    /** A quote from one chart result (its meta and the day's closes). */
    private fun quoteFrom(result: JSONObject, symbol: String): Quote {
        val meta = result.getJSONObject("meta")
        val closes = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val points = (0 until (closes?.length() ?: 0)).mapNotNull { i ->
            if (closes!!.isNull(i)) null else closes.getDouble(i)
        }
        return Quote(
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
     * Volume-weighted average price at each of [candles], and the indices where it starts again.
     * [offsetMs] is the exchange's offset from UTC. A candle the feed reports no volume for (Yahoo
     * gives none outside regular hours) has no average, so the line is not drawn flat through it.
     * Candles with eToro's volume (overnight, and pre-market and after hours) cannot be added to
     * Yahoo's exchange volume, so the average starts again where the volume's source changes.
     */
    fun vwap(candles: List<Candle>, offsetMs: Long, source: VwapSource, anchor: VwapAnchor): Pair<List<Double?>, Set<Int>> {
        var period = Long.MIN_VALUE
        var priceVolume = 0.0
        var volume = 0L
        val starts = mutableSetOf(0)
        val values = candles.mapIndexed { i, c ->
            // The overnight session from 20:00 belongs to the next trading day, so its average
            // does not start again at midnight.
            val local = c.time + offsetMs
            val lateEvening = c.session == Session.OVERNIGHT && Math.floorMod(local, 86_400_000L) >= 20 * 3_600_000L
            val date = java.time.LocalDate.ofEpochDay(Math.floorDiv(local, 86_400_000L) + if (lateEvening) 1 else 0)
            val current = anchor.period(date)
            val feedChanged = i > 0 && c.etoroVolume != candles[i - 1].etoroVolume
            if (current != period || feedChanged) {
                if (period != Long.MIN_VALUE) starts += i
                period = current
                priceVolume = 0.0
                volume = 0L
            }
            if (c.volume <= 0) return@mapIndexed null
            priceVolume += source.of(c) * c.volume
            volume += c.volume
            priceVolume / volume
        }
        return values to starts
    }

    /** A chart range for the dashboard, and the step between its prices ([minutes] for the intraday ones, with eToro's name for it). */
    enum class Range(val label: String, val range: String, val interval: String, val minutes: Int?, val etoroInterval: String?) {
        DAY("1D", "1d", "5m", 5, "FiveMinutes"), WEEK("5D", "5d", "30m", 30, "ThirtyMinutes"),
        MONTH("1M", "1mo", "1d", null, null), HALF_YEAR("6M", "6mo", "1d", null, null), YEAR("1Y", "1y", "1wk", null, null),
    }

    /** Which part of the trading day a candle is from (US listings; elsewhere every candle is [REGULAR]). */
    enum class Session(val label: String) { PRE("Pre-market"), REGULAR("Regular"), POST("After hours"), OVERNIGHT("Overnight") }

    /**
     * One candle: its start (epoch ms), open, high, low, close and volume (0 where the feed
     * reports none), and its session. [etoroVolume] when the volume is eToro's own trading (the
     * overnight candles, and pre-market and after-hours ones Yahoo gives none for), which is on a
     * different scale from the exchange's.
     */
    data class Candle(
        val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long,
        val session: Session = Session.REGULAR,
        val etoroVolume: Boolean = false,
    )

    /** [candles] in steps of [minutes] (each step's first open, highest high, lowest low, last close, summed volume), by session. */
    fun aggregate(candles: List<Candle>, minutes: Int): List<Candle> {
        val step = minutes * 60_000L
        return candles.groupBy { Math.floorDiv(it.time, step) to it.session }.toSortedMap(compareBy({ it.first }, { it.second.ordinal })).map { (key, group) ->
            Candle(key.first * step, group.first().open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close,
                group.sumOf { it.volume }, key.second, group.any { it.etoroVolume })
        }
    }

    internal val NEW_YORK: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

    /** The US session at [time]: pre-market 04:00, regular 09:30, after hours 16:00, overnight 20:00 to 04:00, New York time. */
    fun usSession(time: Long): Session {
        val at = java.time.Instant.ofEpochMilli(time).atZone(NEW_YORK)
        val minutes = at.hour * 60 + at.minute
        return when {
            minutes < 4 * 60 || minutes >= 20 * 60 -> Session.OVERNIGHT
            minutes < 9 * 60 + 30 -> Session.PRE
            minutes < 16 * 60 -> Session.REGULAR
            else -> Session.POST
        }
    }

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
        /** The price the chart's tag shows: the latest (overnight or after hours) with extended hours on, else the official close or live price. */
        val latest: Double,
        /** Whether [latest] is from outside regular hours. */
        val latestExtended: Boolean,
        /** Whether the chart has overnight candles (from eToro). */
        val hasOvernight: Boolean,
        /** The candles' step in minutes as fetched (intraday ranges), else null. */
        val minutes: Int? = null,
    ) {
        /** The change over the range: from the day's previous close for one day, else from the first price. */
        val rangeChange: Double get() = quote.price - (points.firstOrNull()?.second ?: quote.previousClose)
    }

    /**
     * [symbol] over [range], or null if the feed did not answer. With extended hours in [settings],
     * the intraday ranges of a US listing include pre-market and after-hours candles (Yahoo's) and
     * the overnight session's (eToro's, see [EtoroCandles]), from the range's start until now.
     */
    fun history(context: android.content.Context, symbol: String, range: Range, settings: VwapSettings, candleMinutes: Int? = null): History? {
        val quote = fetch(symbol, context) ?: return null
        // The card shows the same price as the stock screen (see publish).
        publish(quote)
        // A short gap between the two requests rather than a burst, which Yahoo is quicker to refuse.
        Thread.sleep(400)
        val prePost = settings.extendedHours && range.interval.endsWith("m")
        // The step fetched: the range's own, or a finer one when the user picks smaller candles
        // than it (5-minute candles on 5D). Coarser candles are grouped on the chart instead.
        val minutes = range.minutes?.let { own -> if (candleMinutes != null && candleMinutes < own) candleMinutes else own }
        val interval = minutes?.let { "${it}m" } ?: range.interval
        // eToro's candles at the step fetched: five-minute ones grouped into 15 minutes for 15m.
        val etoroInterval = when (minutes) { null -> null; 5, 15 -> "FiveMinutes"; 30 -> "ThirtyMinutes"; else -> range.etoroInterval }
        val body = yahoo(
            "/v8/finance/chart/" +
                // One day with extended hours asks for two, so that before the market opens the
                // chart can show the last full day as well as this morning (see below).
                URLEncoder.encode(symbol, "UTF-8") + "?range=${if (range == Range.DAY && prePost) "2d" else range.range}&interval=$interval" +
                if (prePost) "&includePrePost=true" else "",
            context,
        ) ?: return null
        return try {
            val result = JSONObject(body)
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
            val us = meta.optString("exchangeTimezoneName") == "America/New_York"
            val fetched = (0 until minOf(times?.length() ?: 0, closes?.length() ?: 0)).mapNotNull { i ->
                if (listOf(opens, highs, lows, closes).any { it == null || it.isNull(i) }) return@mapNotNull null
                val time = times!!.getLong(i) * 1000
                Candle(time, opens!!.getDouble(i), highs!!.getDouble(i), lows!!.getDouble(i), closes!!.getDouble(i),
                    volumes?.takeUnless { it.isNull(i) }?.getLong(i) ?: 0L,
                    // Without extended hours every candle is the regular session's, the last one
                    // being the 16:00 closing print (the official close), not after hours.
                    if (us && prePost) usSession(time) else Session.REGULAR)
            }
            // One day with extended hours: today, once its regular session has begun; before
            // that (pre-market), the last full trading day as well, so the chart shows the close,
            // the night after it and this morning.
            val offset = meta.optLong("gmtoffset") * 1000
            val days = fetched.groupBy { Math.floorDiv(it.time + offset, 86_400_000L) }.toSortedMap().values.toList()
            val todayOnly = days.lastOrNull()?.any { it.session == Session.REGULAR } == true
            val yahoo = if (range == Range.DAY && prePost && days.size > 1) (if (todayOnly) days.last() else days.takeLast(2).flatten()) else fetched
            // The overnight session, which Yahoo does not carry, from eToro: its candles in the
            // overnight hours from the range's first candle on, and on one day of today alone,
            // the night that led into it (from 20:00 the evening before).
            // eToro's candles for the range (eToro trades 24/5): the overnight session, which Yahoo
            // does not carry, and the volume of pre-market and after-hours candles, which Yahoo
            // reports as none.
            val etoro = if (prePost && us && etoroInterval != null && yahoo.isNotEmpty()) {
                val count = if (range == Range.DAY) 600 else 1000
                val from = yahoo.first().time - if (range == Range.DAY && todayOnly) 8 * 3_600_000L else 0L
                EtoroCandles.instrumentId(context, symbol)?.let { id -> EtoroCandles.candles(id, etoroInterval, count) }.orEmpty()
                    .filter { it.time >= from }
                    .map { it.copy(session = usSession(it.time), etoroVolume = true) }
                    .let { if (minutes != null && etoroInterval == "FiveMinutes" && minutes > 5) aggregate(it, minutes) else it }
            } else emptyList()
            val overnight = etoro.filter { it.session == Session.OVERNIGHT }
            val etoroAt = etoro.associateBy { it.time }
            // The candle still forming carries the time of its latest trade (09:59, say), not its
            // start: matched to eToro's and Nasdaq's by the start of its step.
            val stepMs = (minutes ?: 0) * 60_000L
            fun start(time: Long) = if (stepMs > 0) time - Math.floorMod(time, stepMs) else time
            // Pre-market and after-hours candles Yahoo gives no volume for: eToro's (for the stocks
            // it trades around the clock), else Nasdaq's for the latest day (the exchanges' own).
            // Asked for only when the latest day (all Nasdaq has) has such a candle.
            val latestDay = yahoo.lastOrNull()?.let { java.time.Instant.ofEpochMilli(it.time).atZone(NEW_YORK).toLocalDate() }
            val nasdaq = if (prePost && us && stepMs > 0 && yahoo.any {
                    it.volume <= 0 && it.session != Session.REGULAR &&
                        java.time.Instant.ofEpochMilli(it.time).atZone(NEW_YORK).toLocalDate() == latestDay &&
                        etoroAt[start(it.time)].let { e -> e == null || e.volume <= 0 }
                })
                NasdaqVolume.minutes(symbol) else emptyMap()
            val filled = yahoo.map { c ->
                if (c.volume > 0 || c.session == Session.REGULAR) return@map c
                val e = etoroAt[start(c.time)]
                val n = if (nasdaq.isEmpty()) 0L else NasdaqVolume.sum(nasdaq, start(c.time), stepMs)
                when {
                    e != null && e.volume > 0 -> c.copy(volume = e.volume, etoroVolume = true)
                    n > 0 -> c.copy(volume = n)
                    else -> c
                }
            }
            val taken = filled.map { it.time }.toHashSet()
            val candles = (filled + overnight.filter { it.time !in taken }).sortedBy { it.time }
            val (average, starts) = vwap(candles, meta.optLong("gmtoffset") * 1000, settings.source, settings.anchor)
            fun number(key: String) = meta.optDouble(key).takeUnless { it.isNaN() }
            History(
                quote = quote,
                // One day starts from the previous close, so the line and the change agree.
                points = (if (yahoo.isNotEmpty()) points.filter { it.first >= yahoo.first().time } else points).let { shown ->
                    if (range == Range.DAY && shown.isNotEmpty()) listOf(shown.first().first to quote.previousClose) + shown else shown
                },
                candles = candles,
                vwap = average,
                vwapStarts = starts,
                dayLow = number("regularMarketDayLow"),
                dayHigh = number("regularMarketDayHigh"),
                yearLow = number("fiftyTwoWeekLow"),
                yearHigh = number("fiftyTwoWeekHigh"),
                volume = meta.optLong("regularMarketVolume").takeIf { it > 0 },
                exchangeName = meta.optString("fullExchangeName").ifEmpty { quote.exchange },
                // The tag matches the price at the top of the screen; with extended hours off, the
                // official close (not the last 5-minute candle's, which misses the closing auction).
                latest = if (prePost) quote.extended?.price ?: quote.price else quote.price,
                latestExtended = prePost && quote.extended != null,
                hasOvernight = overnight.isNotEmpty(),
                minutes = minutes,
            )
        } catch (e: Exception) {
            Log.w(TAG, "history failed for $symbol", e)
            null
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
