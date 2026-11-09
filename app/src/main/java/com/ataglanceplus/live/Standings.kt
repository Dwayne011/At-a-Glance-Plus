package com.ataglanceplus.live

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * League tables from ESPN's public standings feed (the same unofficial source as the scores).
 * Football leagues come as one table; US leagues as their conferences. Cached for 10 minutes.
 */
object Standings {

    private const val TAG = "Standings"

    /** A team's line: [values] in the order of [Table.columns]. [zone] is the feed's colour for its place (Champions League…). */
    data class Row(val rank: Int, val teamId: String, val name: String, val abbr: String, val values: List<String>, val zone: Int?, val zoneName: String?)

    data class Table(val name: String, val columns: List<String>, val rows: List<Row>)

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Table>>>()

    /** The tables of [league] in [sport] (one, or one per conference), or null if it has none. Blocking. */
    fun tables(sport: String, league: String): List<Table>? {
        val key = "$sport/$league"
        cache[key]?.let { (at, t) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return t }
        val root = runCatching { get("https://site.api.espn.com/apis/v2/sports/$sport/$league/standings") }
            .onFailure { Log.w(TAG, "$key failed", it) }.getOrNull() ?: return cache[key]?.second
        val tables = mutableListOf<Table>()
        fun walk(node: JSONObject) {
            val entries = node.optJSONObject("standings")?.optJSONArray("entries")
            if (entries != null && entries.length() > 0) parse(sport, node.optString("name"), entries)?.let { tables += it }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let(::walk)
        }
        walk(root)
        cache[key] = System.currentTimeMillis() to tables
        return tables.ifEmpty { null }
    }

    /** Which figures each sport shows, by the feed's stat names, and their headings. */
    private fun columns(sport: String): List<Pair<String, String>> = when (sport) {
        "soccer" -> listOf("gamesPlayed" to "P", "wins" to "W", "ties" to "D", "losses" to "L", "pointDifferential" to "GD", "points" to "Pts")
        "hockey" -> listOf("gamesPlayed" to "GP", "wins" to "W", "losses" to "L", "otLosses" to "OTL", "points" to "Pts")
        else -> listOf("wins" to "W", "losses" to "L", "winPercent" to "PCT", "gamesBehind" to "GB")
    }

    private fun parse(sport: String, name: String, entries: org.json.JSONArray): Table? {
        val cols = columns(sport)
        data class Raw(val row: Row, val sortKey: Double)
        val raws = (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val team = e.optJSONObject("team") ?: return@mapNotNull null
            val statsArr = e.optJSONArray("stats") ?: return@mapNotNull null
            val stats = HashMap<String, Pair<String, Double>>()
            for (j in 0 until statsArr.length()) statsArr.optJSONObject(j)?.let { s ->
                stats[s.optString("name")] = s.optString("displayValue") to s.optDouble("value", Double.NaN)
            }
            val note = e.optJSONObject("note")
            val zone = note?.optString("color")?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            val rank = stats["rank"]?.second?.takeIf { !it.isNaN() && it > 0 }
            // Football tables come ranked; the US ones are ordered here: by points in hockey,
            // else by winning percentage, then wins.
            val sortKey = when {
                rank != null -> rank
                sport == "hockey" -> -(stats["points"]?.second ?: 0.0) * 1000 - (stats["wins"]?.second ?: 0.0)
                else -> -(stats["winPercent"]?.second ?: 0.0) * 1000 - (stats["wins"]?.second ?: 0.0)
            }
            Raw(
                Row(
                    rank = 0, teamId = team.optString("id"),
                    name = team.optString("shortDisplayName").ifEmpty { team.optString("displayName") },
                    abbr = team.optString("abbreviation"),
                    values = cols.map { (key, _) -> stats[key]?.first?.ifEmpty { null } ?: "–" },
                    zone = zone, zoneName = note?.optString("description")?.ifEmpty { null },
                ),
                sortKey,
            )
        }
        if (raws.isEmpty()) return null
        val rows = raws.sortedBy { it.sortKey }.mapIndexed { i, r -> r.row.copy(rank = i + 1) }
        return Table(name, cols.map { it.second }, rows)
    }

    private fun get(url: String): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
        }
        return try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    }
}
