package com.ataglanceplus.live

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pre-market and after-hours volume for US stocks, from Nasdaq's public intraday chart (the one on
 * nasdaq.com's quote pages). Yahoo reports no volume outside regular hours, and eToro only has
 * it for the stocks it trades around the clock; Nasdaq's is the exchanges' own, on the same scale
 * as the regular session's. It covers the latest trading day only, 04:00 to 20:00 New York time,
 * minute by minute.
 */
object NasdaqVolume {

    private const val TAG = "NasdaqVolume"
    /** Asked again for a stock at most this often. */
    private const val KEEP_MS = 60_000L

    private class Day(val at: Long, val minutes: Map<Long, Long>)

    private val cache = HashMap<String, Day>()

    private val asking = HashSet<String>()

    /**
     * Volume per minute (start, epoch ms → shares) for [symbol]'s latest day, as last fetched;
     * empty until the first answer. Never waits: Nasdaq takes 2 to 3 seconds to answer, so a
     * stale or missing day is fetched in the background, and the stock screen's next refresh
     * (every 15 seconds) uses it.
     */
    fun minutes(symbol: String): Map<Long, Long> {
        val key = symbol.uppercase()
        val day = synchronized(cache) { cache[key] }
        val stale = day == null || System.currentTimeMillis() - day.at >= KEEP_MS
        if (stale && synchronized(asking) { asking.add(key) }) {
            Thread {
                try {
                    val fresh = fetch(key)
                    synchronized(cache) { cache[key] = Day(System.currentTimeMillis(), fresh) }
                } finally {
                    synchronized(asking) { asking.remove(key) }
                }
            }.apply { isDaemon = true; name = "nasdaq-$key" }.start()
        }
        return day?.minutes.orEmpty()
    }

    private fun fetch(symbol: String): Map<Long, Long> {
        // Nasdaq spells share classes with a slash ("BRK/B"); Yahoo with a dash.
        val url = "https://api.nasdaq.com/api/quote/${URLEncoder.encode(symbol.replace('-', '/'), "UTF-8")}/chart?assetclass=stocks&charttype=rs"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) return emptyMap()
            val chart = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("data")?.optJSONArray("chart") ?: return emptyMap()
            val out = HashMap<Long, Long>()
            for (i in 0 until chart.length()) {
                val point = chart.optJSONObject(i) ?: continue
                val shares = point.optDouble("w").takeUnless { it.isNaN() || it <= 0 } ?: continue
                // The time is New York's clock written as if it were UTC.
                val wall = Instant.ofEpochMilli(point.optLong("x")).atOffset(ZoneOffset.UTC).toLocalDateTime()
                val start = wall.atZone(FinanceSource.NEW_YORK).toInstant().toEpochMilli()
                out[start] = (out[start] ?: 0L) + Math.round(shares)
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "chart failed for $symbol", e)
            emptyMap()
        } finally {
            connection.disconnect()
        }
    }

    /** The shares traded in [start] to [start] + [stepMs] in [minutes], or 0 when none are listed. */
    fun sum(minutes: Map<Long, Long>, start: Long, stepMs: Long): Long {
        var total = 0L
        var t = start
        while (t < start + stepMs) {
            total += minutes[t] ?: 0L
            t += 60_000L
        }
        return total
    }
}
