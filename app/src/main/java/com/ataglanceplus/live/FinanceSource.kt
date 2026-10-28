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
