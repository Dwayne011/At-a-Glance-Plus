package com.ataglanceplus.live

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Formula 1 championship standings and full session results from Jolpica (api.jolpi.ca), the
 * free, open successor to the Ergast API that FastF1 also uses. No key; answers are kept for a
 * few minutes so moving between tabs does not fetch again.
 */
object JolpicaSource {

    private const val TAG = "JolpicaSource"
    private const val BASE = "https://api.jolpi.ca/ergast/f1"
    private const val KEEP_MS = 5 * 60_000L

    data class DriverStanding(val position: Int, val code: String, val name: String, val team: String, val teamId: String, val points: String, val wins: Int)
    data class TeamStanding(val position: Int, val name: String, val teamId: String, val points: String, val wins: Int)

    /** One finisher (or not) of a session: [time] is the race time or gap, or the best lap in qualifying. */
    data class ResultRow(val position: String, val code: String, val name: String, val teamId: String, val time: String, val points: String?, val fastestLap: Boolean)
    data class SessionResult(val title: String, val rows: List<ResultRow>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    private fun get(path: String): JSONObject? {
        cache[path]?.let { (at, json) -> if (System.currentTimeMillis() - at < KEEP_MS) return json }
        val connection = (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("MRData")
                .also { cache[path] = System.currentTimeMillis() to it }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $path", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** The drivers' championship, leader first, or null if Jolpica did not answer. */
    fun driverStandings(): List<DriverStanding>? {
        val list = get("current/driverStandings.json")?.getJSONObject("StandingsTable")?.getJSONArray("StandingsLists")
            ?.optJSONObject(0)?.getJSONArray("DriverStandings") ?: return null
        return (0 until list.length()).map { i ->
            val s = list.getJSONObject(i)
            val driver = s.getJSONObject("Driver")
            val team = s.getJSONArray("Constructors").optJSONObject(0)
            DriverStanding(
                position = s.optInt("position", i + 1),
                code = driver.optString("code").ifEmpty { driver.optString("familyName").take(3).uppercase() },
                name = "${driver.optString("givenName")} ${driver.optString("familyName")}".trim(),
                team = team?.optString("name").orEmpty(),
                teamId = team?.optString("constructorId").orEmpty(),
                points = s.optString("points"),
                wins = s.optInt("wins"),
            )
        }
    }

    /** The constructors' championship, leader first, or null if Jolpica did not answer. */
    fun teamStandings(): List<TeamStanding>? {
        val list = get("current/constructorStandings.json")?.getJSONObject("StandingsTable")?.getJSONArray("StandingsLists")
            ?.optJSONObject(0)?.getJSONArray("ConstructorStandings") ?: return null
        return (0 until list.length()).map { i ->
            val s = list.getJSONObject(i)
            val team = s.getJSONObject("Constructor")
            TeamStanding(s.optInt("position", i + 1), team.optString("name"), team.optString("constructorId"), s.optString("points"), s.optInt("wins"))
        }
    }

    /**
     * The weekend's full results (race, sprint and qualifying, those that have happened), for the
     * round whose race falls within three days of [raceDay]. Empty before any of them, or if
     * Jolpica did not answer.
     */
    fun weekendResults(raceDay: LocalDate): List<SessionResult> {
        val races = get("current/races.json?limit=40")?.getJSONObject("RaceTable")?.getJSONArray("Races") ?: return emptyList()
        val round = (0 until races.length()).map { races.getJSONObject(it) }.firstOrNull {
            runCatching { kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(it.getString("date")), raceDay)) <= 3 }.getOrDefault(false)
        }?.optString("round") ?: return emptyList()
        return listOfNotNull(
            session("current/$round/results.json", "Results", "Race") { r -> r.optJSONObject("Time")?.optString("time") ?: r.optString("status") },
            session("current/$round/sprint.json", "SprintResults", "Sprint") { r -> r.optJSONObject("Time")?.optString("time") ?: r.optString("status") },
            session("current/$round/qualifying.json", "QualifyingResults", "Qualifying") { r ->
                r.optString("Q3").ifEmpty { r.optString("Q2") }.ifEmpty { r.optString("Q1") }.ifEmpty { "No time" }
            },
        )
    }

    private fun session(path: String, key: String, title: String, time: (JSONObject) -> String): SessionResult? {
        val race = get(path)?.getJSONObject("RaceTable")?.getJSONArray("Races")?.optJSONObject(0) ?: return null
        val list = race.optJSONArray(key) ?: return null
        if (list.length() == 0) return null
        val rows = (0 until list.length()).map { i ->
            val r = list.getJSONObject(i)
            val driver = r.getJSONObject("Driver")
            ResultRow(
                position = r.optString("positionText", r.optString("position")),
                code = driver.optString("code").ifEmpty { driver.optString("familyName").take(3).uppercase() },
                name = "${driver.optString("givenName")} ${driver.optString("familyName")}".trim(),
                teamId = r.optJSONObject("Constructor")?.optString("constructorId").orEmpty(),
                time = time(r),
                points = r.optString("points").takeIf { it.isNotEmpty() && key != "QualifyingResults" },
                fastestLap = r.optJSONObject("FastestLap")?.optString("rank") == "1",
            )
        }
        return SessionResult(title, rows)
    }

    /** Each team's colour (Jolpica's constructor ids), for the bars beside names; grey when unknown. */
    fun teamColour(teamId: String): Int = when (teamId) {
        "mercedes" -> 0xFF27F4D2
        "ferrari" -> 0xFFE8002D
        "red_bull" -> 0xFF3671C6
        "mclaren" -> 0xFFFF8000
        "rb" -> 0xFF6692FF
        "alpine" -> 0xFF0093CC
        "haas" -> 0xFFB6BABD
        "audi", "sauber" -> 0xFFF50537
        "williams" -> 0xFF64C4FF
        "aston_martin" -> 0xFF229971
        "cadillac" -> 0xFFC9B37E
        else -> 0xFF9AA0A6
    }.toInt()
}
