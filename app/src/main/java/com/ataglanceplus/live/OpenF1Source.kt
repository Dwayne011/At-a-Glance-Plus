package com.ataglanceplus.live

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Full classifications of finished Formula 1 sessions, practice included, from OpenF1
 * (api.openf1.org, open source): position, best lap or race time, gap, laps and team colour. Its
 * API is free between sessions; while any session is live it answers only to paid keys, and then
 * this returns nothing and the dashboard falls back to Jolpica or ESPN.
 */
object OpenF1Source {

    private const val TAG = "OpenF1Source"
    private const val BASE = "https://api.openf1.org/v1"

    /** Finished sessions' results never change, so they are kept for good once read. */
    private val results = java.util.concurrent.ConcurrentHashMap<Int, JolpicaSource.SessionResult>()
    private var sessions: Pair<Long, JSONArray>? = null

    private fun get(path: String): JSONArray? {
        val connection = (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) return null
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $path", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** The year's sessions, read at most every 10 minutes. */
    private fun sessions(year: Int): JSONArray? {
        sessions?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return list }
        return get("sessions?year=$year")?.also { sessions = System.currentTimeMillis() to it }
    }

    /**
     * The session starting within two hours of [startMs] (ESPN's time for it), classified, or null
     * if OpenF1 does not have it or does not answer.
     */
    fun result(startMs: Long, title: String): JolpicaSource.SessionResult? {
        val year = java.time.Instant.ofEpochMilli(startMs).atZone(java.time.ZoneOffset.UTC).year
        val list = sessions(year) ?: return null
        val session = (0 until list.length()).map { list.getJSONObject(it) }.minByOrNull {
            runCatching { kotlin.math.abs(java.time.OffsetDateTime.parse(it.getString("date_start")).toInstant().toEpochMilli() - startMs) }
                .getOrDefault(Long.MAX_VALUE)
        }?.takeIf {
            runCatching { kotlin.math.abs(java.time.OffsetDateTime.parse(it.getString("date_start")).toInstant().toEpochMilli() - startMs) < 2 * 3_600_000L }
                .getOrDefault(false)
        } ?: return null
        val key = session.getInt("session_key")
        results[key]?.let { return it.copy(title = title) }
        val type = session.optString("session_type")
        val rows = get("session_result?session_key=$key") ?: return null
        if (rows.length() == 0) return null
        val drivers = get("drivers?session_key=$key")?.let { d -> (0 until d.length()).associate { d.getJSONObject(it).let { o -> o.optInt("driver_number") to o } } }.orEmpty()
        val race = type.equals("Race", ignoreCase = true)
        val qualifying = type.equals("Qualifying", ignoreCase = true)
        val out = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { if (it.isNull("position")) 99 else it.optInt("position") }.map { r ->
            val driver = drivers[r.optInt("driver_number")]
            val status = when {
                r.optBoolean("dsq") -> "DSQ"
                r.optBoolean("dns") -> "DNS"
                r.optBoolean("dnf") -> "DNF"
                else -> null
            }
            val leader = r.optInt("position") == 1
            val figure = status ?: when {
                // Qualifying: the best time in the last part reached (Q3, Q2 or Q1).
                qualifying -> lastOf(r.opt("duration"))?.let { lap(it) } ?: "No time"
                race && leader -> r.optDouble("duration").takeIf { !it.isNaN() }?.let { raceTime(it) } ?: "Leader"
                leader -> r.optDouble("duration").takeIf { !it.isNaN() }?.let { lap(it) } ?: "No time"
                else -> gap(lastOf(r.opt("gap_to_leader")) ?: r.opt("gap_to_leader"))
            }
            val partReached = if (qualifying) (r.opt("duration") as? JSONArray)?.let { a -> (0 until a.length()).lastOrNull { !a.isNull(it) }?.let { "Q${it + 1}" } } else null
            JolpicaSource.ResultRow(
                position = if (r.isNull("position")) "–" else r.optInt("position").toString(),
                code = driver?.optString("name_acronym").orEmpty().ifEmpty { r.optString("driver_number") },
                name = driver?.optString("full_name")?.let { name(it) }.orEmpty(),
                teamId = "",
                time = figure,
                points = partReached ?: r.optInt("number_of_laps").takeIf { it > 0 && !race }?.let { "$it laps" },
                fastestLap = false,
                colour = driver?.optString("team_colour")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() },
            )
        }
        return JolpicaSource.SessionResult(title, out).also { results[key] = it }
    }

    /** The last non-null entry of a qualifying array, or the value itself. */
    private fun lastOf(value: Any?): Any? = if (value is JSONArray) (value.length() - 1 downTo 0).firstOrNull { !value.isNull(it) }?.let { value.get(it) } else value

    /** "Isack HADJAR" as "Isack Hadjar". */
    private fun name(full: String): String = full.split(" ").joinToString(" ") { w ->
        if (w.length > 1 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) } else w
    }

    /** 78.128 seconds as "1:18.128". */
    private fun lap(value: Any): String {
        val seconds = (value as? Number)?.toDouble() ?: return value.toString()
        return String.format(Locale.ROOT, "%d:%06.3f", (seconds / 60).toInt(), seconds % 60)
    }

    /** A race time in seconds as "1:35:16.650". */
    private fun raceTime(seconds: Double): String =
        String.format(Locale.ROOT, "%d:%02d:%06.3f", (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt(), seconds % 60)

    /** A gap: "+0.659", or as OpenF1 words it ("+1 LAP"). */
    private fun gap(value: Any?): String = when (value) {
        is Number -> String.format(Locale.ROOT, "+%.3f", value.toDouble())
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }
}
