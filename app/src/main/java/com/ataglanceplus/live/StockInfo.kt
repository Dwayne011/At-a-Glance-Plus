package com.ataglanceplus.live

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What the stock screen shows beyond prices: the next earnings date and the dividend (Nasdaq's
 * public quote pages, US listings only), and recent news (Yahoo Finance's public headline feed).
 * No keys; cached so the 15-second refresh does not ask again.
 */
object StockInfo {

    private const val TAG = "StockInfo"
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus"

    /** The next earnings report: its date and when in the day ("before market open"…), where known. */
    data class Earnings(val date: LocalDate, val timing: String?)

    data class Dividend(val exDate: LocalDate?, val payDate: LocalDate?, val amount: String?, val yield: String?, val annual: String?)

    data class News(val title: String, val link: String, val source: String, val at: Long?)

    private val cache = ConcurrentHashMap<String, Pair<Long, Any?>>()

    private fun <T> cached(key: String, keepMs: Long, load: () -> T?): T? {
        cache[key]?.let { (at, value) -> if (System.currentTimeMillis() - at < keepMs) @Suppress("UNCHECKED_CAST") return value as T? }
        val value = runCatching(load).onFailure { Log.w(TAG, "$key failed", it) }.getOrNull()
        cache[key] = System.currentTimeMillis() to value
        return value
    }

    private fun get(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json, application/xml")
        }
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }

    private fun usDate(text: String?): LocalDate? = text?.trim()?.let { t ->
        runCatching { LocalDate.parse(t, DateTimeFormatter.ofPattern("MM/dd/yyyy")) }.getOrNull()
            ?: runCatching { LocalDate.parse(t, DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)) }.getOrNull()
    }

    private fun nasdaqSymbol(symbol: String) = URLEncoder.encode(symbol.uppercase().replace('-', '/'), "UTF-8")

    /** The next earnings date for a US stock, or null (not listed, or not announced yet). Blocking. */
    fun earnings(symbol: String): Earnings? = cached("earnings:$symbol", 6 * 3_600_000L) {
        if (symbol.contains('.')) return@cached null
        val data = JSONObject(get("https://api.nasdaq.com/api/analyst/${nasdaqSymbol(symbol)}/earnings-date") ?: return@cached null).optJSONObject("data") ?: return@cached null
        val date = usDate(data.optString("announcement").substringAfter(":", "")) ?: return@cached null
        val report = data.optString("reportText")
        val timing = when {
            report.contains("before market open", true) -> "before the market opens"
            report.contains("after market close", true) -> "after the market closes"
            else -> null
        }
        Earnings(date, timing).takeIf { !it.date.isBefore(LocalDate.now()) }
    }

    /** The latest dividend for a US stock, or null if it pays none. Blocking. */
    fun dividend(symbol: String): Dividend? = cached("dividend:$symbol", 6 * 3_600_000L) {
        if (symbol.contains('.')) return@cached null
        val data = JSONObject(get("https://api.nasdaq.com/api/quote/${nasdaqSymbol(symbol)}/dividends?assetclass=stocks") ?: return@cached null).optJSONObject("data") ?: return@cached null
        fun value(key: String) = data.optString(key).takeIf { it.isNotEmpty() && it != "N/A" && it != "null" }
        val row = data.optJSONObject("dividends")?.optJSONArray("rows")?.optJSONObject(0)
        val exDate = usDate(row?.optString("exOrEffDate")) ?: usDate(value("exDividendDate"))
        val amount = row?.optString("amount")?.takeIf { it.isNotEmpty() && it != "N/A" }
        if (exDate == null && amount == null) return@cached null
        Dividend(exDate, usDate(row?.optString("paymentDate")) ?: usDate(value("dividendPaymentDate")), amount, value("yield"), value("annualizedDividend"))
    }

    /** The latest headlines about [symbol] (up to [count]), newest first. Blocking. */
    fun news(symbol: String, count: Int = 5): List<News> = cached("news:$symbol", 15 * 60_000L) {
        val xml = get("https://feeds.finance.yahoo.com/rss/2.0/headline?s=${URLEncoder.encode(symbol, "UTF-8")}&region=US&lang=en-US") ?: return@cached null
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.trim()
                ?.removePrefix("<![CDATA[")?.removeSuffix("]]>")?.trim()
            val title = tag("title")?.let(::unescape) ?: return@mapNotNull null
            val link = tag("link")?.let(::unescape) ?: return@mapNotNull null
            val at = tag("pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            val source = runCatching { URL(link).host.removePrefix("www.").removePrefix("finance.") }.getOrDefault("")
            News(title, link, source, at)
        }.take(count).toList()
    }.orEmpty()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
