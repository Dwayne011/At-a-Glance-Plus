package com.ataglanceplus.live

import android.content.Context
import android.util.JsonReader
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The overnight session's prices (20:00 to 04:00 New York time) for US stocks, which Yahoo's feed
 * does not carry: from eToro's public candle feed, the one its own web charts use (no keys; it is
 * unofficial, so it may change, and the chart then simply has no overnight part). eToro's prices
 * are its own quotes, close to the exchange's but not always the same.
 *
 * eToro names stocks by its own instrument number, looked up from its public list of instruments
 * (about 14 MB), read once a month and kept on the phone as a small symbol-to-number index.
 */
object EtoroCandles {

    private const val TAG = "EtoroCandles"
    private const val LIST_URL = "https://api.etorostatic.com/sapi/instrumentsmetadata/V1.1/instruments"
    private const val CANDLES_URL = "https://candle.etoro.com/candles/desc.json/"
    private const val INDEX_FILE = "etoro_instruments.json"
    private const val KEEP_MS = 30L * 24 * 60 * 60_000
    /** A symbol not in the index is looked for again after this long (a new listing). */
    private const val RETRY_MS = 24L * 60 * 60_000

    @Volatile private var index: Map<String, Int>? = null
    @Volatile private var lastTry = 0L

    /** eToro's number for [symbol] (Yahoo's spelling: "BRK-B" is eToro's "BRK.B"), or null. Blocking. */
    fun instrumentId(context: Context, symbol: String): Int? {
        val wanted = listOf(symbol.uppercase(), symbol.uppercase().replace('-', '.'))
        val file = File(context.filesDir, INDEX_FILE)
        val map = index ?: load(file)?.also { index = it }
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > KEEP_MS
        val missing = map == null || wanted.none { it in map }
        if ((stale || missing) && System.currentTimeMillis() - lastTry > RETRY_MS) {
            lastTry = System.currentTimeMillis()
            download()?.let { fresh ->
                runCatching { file.writeText(JSONObject(fresh as Map<*, *>).toString()) }
                index = fresh
            }
        }
        val current = index ?: return null
        return wanted.firstNotNullOfOrNull { current[it] }
    }

    private fun load(file: File): Map<String, Int>? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrNull()

    /** The whole list, read as it streams in so its 14 MB never sit in memory at once. */
    private fun download(): Map<String, Int>? {
        val connection = (URL(LIST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        return try {
            if (connection.responseCode != 200) return null
            val out = HashMap<String, Int>()
            JsonReader(connection.inputStream.bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "InstrumentDisplayDatas") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id: Int? = null
                        var symbol: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "InstrumentID" -> id = reader.nextInt()
                                "SymbolFull" -> symbol = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (id != null && !symbol.isNullOrEmpty()) out.putIfAbsent(symbol.uppercase(), id)
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "instrument list failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The latest [count] candles of instrument [id] at [interval] ("FiveMinutes", "ThirtyMinutes"),
     * oldest first, as the chart's candles in [FinanceSource.Session.OVERNIGHT]; empty when the feed
     * does not answer. Blocking.
     */
    fun candles(id: Int, interval: String, count: Int): List<FinanceSource.Candle> {
        val connection = (URL("$CANDLES_URL$interval/$count/$id").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val groups = root.optJSONArray("Candles") ?: return emptyList()
            val list = groups.optJSONObject(0)?.optJSONArray("Candles") ?: return emptyList()
            (0 until list.length()).mapNotNull { i ->
                val c = list.optJSONObject(i) ?: return@mapNotNull null
                val time = runCatching { java.time.Instant.parse(c.getString("FromDate")).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                FinanceSource.Candle(
                    time, c.getDouble("Open"), c.getDouble("High"), c.getDouble("Low"), c.getDouble("Close"),
                    c.optDouble("Volume", 0.0).toLong(), FinanceSource.Session.OVERNIGHT,
                )
            }.sortedBy { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "candles failed for $id", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}
