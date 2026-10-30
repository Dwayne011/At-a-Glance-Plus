package com.ataglanceplus.live

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * A finished Formula 1 session's classification, as rich as the sources allow: best laps, gaps,
 * laps, best sectors, speed trap and tyres. Kept on the phone once read, because the sources
 * come and go: F1's live timing feed holds a session's final state only until the next one
 * starts, and OpenF1 is closed to free use while any session is live.
 *
 * In order: what is kept, F1's feed (when it still has the session), OpenF1, Jolpica's
 * qualifying, ESPN's top three. Races and sprints take Jolpica's official order and points,
 * with the rest filled in from the others.
 */
object F1Results {

    private const val TAG = "F1Results"
    private const val KEEP_DAYS = 120L

    private fun dir(context: Context) = File(context.filesDir, "f1_results").apply { mkdirs() }
    private fun file(context: Context, start: Long) = File(dir(context), "$start.json")

    /** The session's classification, or null if no source has it yet. Blocking: call off the main thread. */
    fun load(context: Context, session: RaceSession, title: String, raceDay: LocalDate): JolpicaSource.SessionResult? {
        val name = session.session.lowercase(Locale.ROOT)
        val raceOrSprint = name == "race" || name == "r" || ((name.contains("sprint") || name == "sr") && !name.contains("qual") && !name.contains("shoot") && name != "sq" && name != "ss")
        var best = stored(context, session.start)
        if (best?.final != true) best = capture(context, session) ?: best
        if (best?.final != true) {
            OpenF1Source.result(session.start, title)?.let { open -> best = open; store(context, session.start, open) }
        }
        val jolpica = if (raceOrSprint || name.startsWith("qual") || name == "q") JolpicaSource.weekendResults(raceDay) else emptyList()
        if (raceOrSprint) {
            jolpica.firstOrNull { (it.title == "Race") == (name == "race" || name == "r") && it.title != "Qualifying" }?.let { official ->
                return official.copy(title = title, rows = official.rows.map { row -> fillIn(row, best?.rows?.firstOrNull { it.code == row.code }) })
            }
        }
        best?.let { return it.copy(title = title) }
        if (name.startsWith("qual") || name == "q") jolpica.firstOrNull { it.title == "Qualifying" }?.let { return it.copy(title = title) }
        return session.top.takeIf { it.isNotEmpty() }?.let { top ->
            JolpicaSource.SessionResult(title, top.mapIndexed { i, n -> JolpicaSource.ResultRow("${i + 1}", "", n, "", "", null, false) }, final = false)
        }
    }

    /** Jolpica's official row, with what it lacks from [other] (the same driver from another source). */
    private fun fillIn(row: JolpicaSource.ResultRow, other: JolpicaSource.ResultRow?): JolpicaSource.ResultRow = if (other == null) row else row.copy(
        colour = row.colour ?: other.colour,
        laps = row.laps ?: other.laps,
        bestLap = row.bestLap ?: other.bestLap,
        sectors = row.sectors.ifEmpty { other.sectors },
        sectorFastest = row.sectorFastest.ifEmpty { other.sectorFastest },
        topSpeed = row.topSpeed ?: other.topSpeed,
        stints = row.stints.ifEmpty { other.stints },
        pitStops = row.pitStops ?: other.pitStops,
    )

    /**
     * Reads F1's feed once and, if it still holds this session, keeps and returns its
     * classification. Called as a session ends, and when a finished session is opened.
     */
    fun capture(context: Context, session: RaceSession): JolpicaSource.SessionResult? {
        val stored = stored(context, session.start)
        if (stored?.final == true) return stored
        val data = LiveTiming.snapshot() ?: return null
        val result = classify(data, session.start) ?: return null
        if (result.rows.none { it.time.isNotEmpty() && it.time != "No time" }) return null
        store(context, session.start, result)
        return result
    }

    /** The feed's state as a classification, if it is the session starting near [start]. */
    private fun classify(data: JSONObject, start: Long): JolpicaSource.SessionResult? {
        val info = data.optJSONObject("SessionInfo") ?: return null
        val began = runCatching {
            val offset = info.optString("GmtOffset").split(":").let { p -> (p[0].toInt() * 60 + (if (p[0].startsWith("-")) -1 else 1) * p[1].toInt()) * 60 }
            LocalDateTime.parse(info.optString("StartDate")).toInstant(ZoneOffset.ofTotalSeconds(offset)).toEpochMilli()
        }.getOrNull() ?: return null
        if (kotlin.math.abs(began - start) > 2 * 3_600_000L) return null
        val type = info.optString("Type")
        val race = type.equals("Race", ignoreCase = true)
        val qualifying = type.equals("Qualifying", ignoreCase = true)
        val status = data.optJSONObject("SessionStatus")?.optString("Status").orEmpty()
        val lines = data.optJSONObject("TimingData")?.optJSONObject("Lines") ?: return null
        val stats = data.optJSONObject("TimingStats")?.optJSONObject("Lines")
        val app = data.optJSONObject("TimingAppData")?.optJSONObject("Lines")
        val drivers = data.optJSONObject("DriverList")
        val rows = lines.keys().asSequence().mapNotNull { number ->
            val line = lines.optJSONObject(number) ?: return@mapNotNull null
            val position = line.optString("Position").toIntOrNull() ?: return@mapNotNull null
            val driver = drivers?.optJSONObject(number)
            val stat = stats?.optJSONObject(number)
            val best = line.optJSONObject("BestLapTime")?.optString("Value").orEmpty()
            val parts = list(line.opt("BestLapTimes")).map { (it as? JSONObject)?.optString("Value").orEmpty() }
            val retired = line.optBoolean("Retired")
            val sectors = list(stat?.opt("BestSectors")).map { it as? JSONObject }
            position to JolpicaSource.ResultRow(
                position = "$position",
                code = driver?.optString("Tla")?.ifEmpty { null } ?: number,
                name = listOfNotNull(driver?.optString("FirstName"), driver?.optString("LastName")).joinToString(" ").trim()
                    .ifEmpty { driver?.optString("FullName").orEmpty() },
                teamId = "",
                time = when {
                    race && retired -> "DNF"
                    race && position == 1 -> "Leader"
                    race -> line.optString("GapToLeader")
                    qualifying -> parts.lastOrNull { it.isNotEmpty() } ?: best.ifEmpty { "No time" }
                    else -> best.ifEmpty { "No time" }
                },
                points = null,
                fastestLap = false,
                colour = driver?.optString("TeamColour")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() },
                gap = if (race || position == 1) "" else line.optString("TimeDiffToFastest"),
                laps = line.optInt("NumberOfLaps").takeIf { it > 0 },
                bestLap = best.ifEmpty { null },
                sectors = sectors.map { it?.optString("Value").orEmpty() }.takeIf { s -> s.size == 3 && s.all { it.isNotEmpty() } }.orEmpty(),
                sectorFastest = sectors.map { it?.optInt("Position") == 1 },
                topSpeed = stat?.optJSONObject("BestSpeeds")?.optJSONObject("ST")?.optString("Value")?.toIntOrNull(),
                stints = list(app?.optJSONObject(number)?.opt("Stints")).mapNotNull { it as? JSONObject }
                    .map { it.optString("Compound").uppercase(Locale.ROOT) to (it.optInt("TotalLaps") - it.optInt("StartLaps")).coerceAtLeast(0) },
                pitStops = if (race) line.optInt("NumberOfPitStops") else null,
                status = if (retired) "Retired" else null,
                qualifying = if (qualifying) (0 until 3).map { parts.getOrNull(it).orEmpty() } else emptyList(),
            )
        }.sortedBy { it.first }.map { it.second }.toList()
        if (rows.isEmpty()) return null
        // In a race, the fastest lap of all.
        val fastest = if (race) rows.filter { it.bestLap != null }.minByOrNull { LiveTiming.parseLap(it.bestLap) ?: Long.MAX_VALUE }?.code else null
        return JolpicaSource.SessionResult(
            title = info.optString("Name"),
            rows = rows.map { if (it.code == fastest) it.copy(fastestLap = true) else it },
            final = status == "Finalised" || status == "Ends",
        )
    }

    private fun list(value: Any?): List<Any?> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        is JSONObject -> value.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to value.opt(k) } }.sortedBy { it.first }.map { it.second }.toList()
        else -> emptyList()
    }

    fun stored(context: Context, start: Long): JolpicaSource.SessionResult? = runCatching {
        file(context, start).takeIf { it.exists() }?.readText()?.let { fromJson(JSONObject(it)) }
    }.getOrNull()

    private fun store(context: Context, start: Long, result: JolpicaSource.SessionResult) {
        runCatching {
            file(context, start).writeText(toJson(result).toString())
            val old = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            dir(context).listFiles()?.filter { (it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE) < old }?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "could not keep the result", it) }
    }

    private fun toJson(result: JolpicaSource.SessionResult) = JSONObject()
        .put("title", result.title).put("final", result.final)
        .put("rows", JSONArray(result.rows.map { r ->
            JSONObject().put("position", r.position).put("code", r.code).put("name", r.name).put("teamId", r.teamId).put("time", r.time)
                .put("points", r.points).put("fastestLap", r.fastestLap).put("colour", r.colour).put("gap", r.gap).put("laps", r.laps)
                .put("bestLap", r.bestLap).put("sectors", JSONArray(r.sectors)).put("sectorFastest", JSONArray(r.sectorFastest))
                .put("topSpeed", r.topSpeed).put("stints", JSONArray(r.stints.map { JSONArray().put(it.first).put(it.second) }))
                .put("pitStops", r.pitStops).put("grid", r.grid).put("status", r.status).put("qualifying", JSONArray(r.qualifying))
        }))

    private fun fromJson(o: JSONObject): JolpicaSource.SessionResult {
        val rows = o.getJSONArray("rows")
        fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key)
        fun JSONObject.int(key: String) = if (isNull(key) || !has(key)) null else optInt(key)
        fun JSONArray?.strings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return JolpicaSource.SessionResult(
            title = o.optString("title"),
            final = o.optBoolean("final"),
            rows = (0 until rows.length()).map { i ->
                val r = rows.getJSONObject(i)
                JolpicaSource.ResultRow(
                    position = r.optString("position"), code = r.optString("code"), name = r.optString("name"), teamId = r.optString("teamId"),
                    time = r.optString("time"), points = r.str("points"), fastestLap = r.optBoolean("fastestLap"), colour = r.int("colour"),
                    gap = r.optString("gap"), laps = r.int("laps"), bestLap = r.str("bestLap"), sectors = r.optJSONArray("sectors").strings(),
                    sectorFastest = r.optJSONArray("sectorFastest")?.let { a -> (0 until a.length()).map { a.optBoolean(it) } }.orEmpty(),
                    topSpeed = r.int("topSpeed"),
                    stints = r.optJSONArray("stints")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.optString(0) to p.optInt(1) } } }.orEmpty(),
                    pitStops = r.int("pitStops"), grid = r.int("grid"), status = r.str("status"), qualifying = r.optJSONArray("qualifying").strings(),
                )
            },
        )
    }
}
