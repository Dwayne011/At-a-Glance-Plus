package com.ataglanceplus.live

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Formula 1's live timing during a session, from F1's own live timing feed (the one behind
 * formula1.com's Live Timing, which FastF1 and OpenF1 also read). It is not a published API:
 * F1 could change or close it at any time, and then the race card simply keeps showing what
 * ESPN gives. No account is used; the cars' exact positions need one, so each car is placed by
 * the last mini-sector it crossed (about 22 a lap) and moved on from there at its lap pace.
 *
 * The feed speaks SignalR Core; this uses its long-polling transport (plain HTTP requests),
 * subscribes to the topics the card needs, keeps the full state from the first answer and merges
 * each change into it.
 */
object LiveTiming {

    private const val TAG = "LiveTiming"
    private const val BASE = "https://livetiming.formula1.com/signalrcore"
    private const val RS = '\u001e'
    private val TOPICS = listOf("TimingStats", "SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "TimingData", "TimingAppData", "DriverList", "ExtrapolatedClock")

    /**
     * One car. [segment] is the last mini-sector it crossed (counted through the lap from 0, of
     * [segments]), at [segmentAt]; [lapMs] is its usual lap time, to move it on between crossings.
     */
    data class Car(
        val number: String,
        val code: String,
        val colour: Int,
        val position: Int,
        /** Practice and qualifying: the best lap (leader) or the gap to it; the race: the gap to the leader. */
        val figure: String,
        val segment: Int?,
        val segmentAt: Long,
        val segments: Int,
        val lapMs: Long?,
        val inPit: Boolean,
        val out: Boolean,
        val name: String = "",
        /** The race: the gap to the car ahead. */
        val interval: String = "",
        val bestLap: String = "",
        val lastLap: String = "",
        val laps: Int = 0,
        val pitStops: Int = 0,
        /** The current tyre ("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET") and its age in laps. */
        val tyre: String = "",
        val tyreAge: Int = 0,
    ) {
        /**
         * How far round the lap the car is at [now] (0 to 1): just past its last mini-sector, moved
         * on at its lap pace but never past the next one (the feed then says it crossed it).
         */
        fun fractionAt(now: Long): Float {
            val at = segment ?: return 0f
            val each = lapMs?.let { it / segments.toFloat() }
            val ahead = if (each == null || inPit || out) 0f else ((now - segmentAt) / each).coerceIn(0f, 0.95f)
            return ((at + 1 + ahead) / segments) % 1f
        }
    }

    data class State(
        val meeting: String,
        /** "Practice 1", "Qualifying", "Race"… */
        val session: String,
        val race: Boolean,
        /** The track status code ("1" all clear, "2" yellow, "4" safety car, "5" red, "6"/"7" virtual safety car). */
        val trackStatus: String,
        val lap: Int?,
        val totalLaps: Int?,
        /** Time left in the session, in milliseconds, worked out at [at]. */
        val remainingMs: Long?,
        val cars: List<Car>,
        val at: Long,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var job: Job? = null
    private var data = JSONObject()
    /** Each car's last mini-sector, counted through the lap from 0, and when it crossed it. */
    private val lastSegment = HashMap<String, Int>()
    private val lastSegmentAt = HashMap<String, Long>()

    /** Connects while [wanted] is true and stays connected; disconnects (and forgets the state) when it turns false. */
    @Synchronized
    fun want(scope: CoroutineScope, wanted: Boolean) {
        if (wanted && job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) { run() }
        } else if (!wanted && job != null) {
            job?.cancel()
            job = null
            _state.value = null
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.run() {
        var wait = 5_000L
        while (isActive) {
            try {
                connect()
                wait = 5_000L
            } catch (e: Exception) {
                if (!isActive) return
                Log.w(TAG, "live timing dropped: ${e.message}")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }

    /** One connection, until the feed closes it or it fails. */
    private suspend fun kotlinx.coroutines.CoroutineScope.connect() {
        val cookies = mutableMapOf<String, String>()
        val url = open(cookies)
        while (isActive) {
            val body = http(url, "GET", null, cookies)
            body.split(RS).filter { it.isNotBlank() }.forEach { handle(JSONObject(it)) }
        }
    }

    /** Negotiates, shakes hands and subscribes; returns the connection's URL. */
    private fun open(cookies: MutableMap<String, String>): String {
        val negotiate = JSONObject(http("$BASE/negotiate?negotiateVersion=1", "POST", "", cookies))
        val url = "$BASE?id=" + java.net.URLEncoder.encode(negotiate.getString("connectionToken"), "UTF-8")
        http(url, "POST", """{"protocol":"json","version":1}$RS""", cookies)
        http(url, "GET", null, cookies) // the handshake's answer
        val subscribe = JSONObject().put("type", 1).put("target", "Subscribe").put("invocationId", "0")
            .put("arguments", JSONArray().put(JSONArray(TOPICS)))
        http(url, "POST", subscribe.toString() + RS, cookies)
        return url
    }

    /**
     * The feed's full state once (it keeps the last session's until the next one starts), or
     * null if it did not answer. Used to keep a finished session's classification.
     */
    fun snapshot(): JSONObject? = runCatching {
        val cookies = mutableMapOf<String, String>()
        val url = open(cookies)
        repeat(4) {
            http(url, "GET", null, cookies).split(RS).filter { it.isNotBlank() }.forEach { m ->
                val message = JSONObject(m)
                if (message.optInt("type") == 3) return@runCatching message.optJSONObject("result")
            }
        }
        null
    }.onFailure { Log.w(TAG, "snapshot failed: ${it.message}") }.getOrNull()

    private fun handle(message: JSONObject) {
        when (message.optInt("type")) {
            // The subscription's answer: the full state of every topic.
            3 -> message.optJSONObject("result")?.let { result ->
                data = result
                lastSegment.clear()
                lastSegment.putAll(segmentsFrom(result.optJSONObject("TimingData")))
                lastSegmentAt.clear()
                val now = System.currentTimeMillis()
                lastSegment.keys.forEach { lastSegmentAt[it] = now }
                publish()
            }
            // A change: ["topic", change, time].
            1 -> if (message.optString("target") == "feed") {
                val args = message.optJSONArray("arguments") ?: return
                val topic = args.optString(0)
                val change = args.opt(1) as? JSONObject ?: return
                if (topic == "TimingData") noteSegments(change)
                val current = data.optJSONObject(topic)
                if (current == null) data.put(topic, change) else merge(current, change)
                publish()
            }
            // 7: the feed closed the connection.
            7 -> throw IllegalStateException("closed by the feed: ${message.optString("error")}")
        }
    }

    /** From a timing change: a car that just crossed a mini-sector is there now. */
    private fun noteSegments(change: JSONObject) {
        val lines = change.optJSONObject("Lines") ?: return
        val counts = segmentCounts()
        val now = System.currentTimeMillis()
        lines.keys().forEach { number ->
            lines.optJSONObject(number)?.opt("Sectors").indexed().forEach { (sector, s) ->
                (s as? JSONObject)?.opt("Segments").indexed().forEach { (g, segment) ->
                    val status = (segment as? JSONObject)?.optInt("Status") ?: 0
                    if (crossed(status)) {
                        lastSegment[number] = counts.take(sector).sum() + g
                        lastSegmentAt[number] = now
                    }
                }
            }
        }
    }

    /** From the full state: each car's furthest mini-sector with a status this lap. */
    private fun segmentsFrom(timing: JSONObject?): Map<String, Int> {
        val lines = timing?.optJSONObject("Lines") ?: return emptyMap()
        val out = HashMap<String, Int>()
        lines.keys().forEach { number ->
            val sectors = lines.optJSONObject(number)?.opt("Sectors").asList()
            var index = 0
            sectors.forEach { sector ->
                (sector as? JSONObject)?.opt("Segments").asList().forEach { segment ->
                    if (crossed((segment as? JSONObject)?.optInt("Status") ?: 0)) out[number] = index
                    index++
                }
            }
        }
        return out
    }

    /**
     * Whether a mini-sector's status says the car has crossed it this lap: 0 is not yet (each
     * lap starts with them all at 0); 2048 (a normal, "yellow" time), 2049 (personal best), 2051
     * (fastest) and 2064 (pit lane) mean it has.
     */
    private fun crossed(status: Int) = status != 0

    /** How many mini-sectors each of the three sectors has, from the first car that says. */
    private fun segmentCounts(): List<Int> {
        val lines = data.optJSONObject("TimingData")?.optJSONObject("Lines") ?: return listOf(8, 8, 8)
        lines.keys().forEach { number ->
            val sectors = lines.optJSONObject(number)?.opt("Sectors").asList()
            val counts = sectors.map { (it as? JSONObject)?.opt("Segments").asList().size }
            if (counts.size == 3 && counts.all { it > 0 }) return counts
        }
        return listOf(8, 8, 8)
    }

    /** A JSON array, or an object keyed "0", "1", … (as changes send them), as index and value pairs. */
    private fun Any?.indexed(): List<Pair<Int, Any?>> = when (this) {
        is JSONArray -> (0 until length()).map { it to opt(it) }
        is JSONObject -> keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to opt(k) } }.sortedBy { it.first }.toList()
        else -> emptyList()
    }

    /** A JSON array, or an object keyed "0", "1", … (as changes send them), as a list. */
    private fun Any?.asList(): List<Any?> = when (this) {
        is JSONArray -> (0 until length()).map { opt(it) }
        is JSONObject -> keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to opt(k) } }.sortedBy { it.first }.map { it.second }.toList()
        else -> emptyList()
    }

    /** Merges a change into the state: objects key by key; arrays by the change's "0", "1"… keys. */
    private fun merge(target: Any, change: JSONObject) {
        change.keys().forEach { key ->
            val value = change.opt(key)
            when (target) {
                is JSONObject -> {
                    val existing = target.opt(key)
                    if (value is JSONObject && (existing is JSONObject || existing is JSONArray)) merge(existing, value)
                    else target.put(key, value)
                }
                is JSONArray -> {
                    val index = key.toIntOrNull() ?: return@forEach
                    val existing = target.opt(index)
                    if (value is JSONObject && (existing is JSONObject || existing is JSONArray)) merge(existing, value)
                    else target.put(index, value)
                }
            }
        }
    }

    private fun publish() {
        _state.value = runCatching { build() }.onFailure { Log.w(TAG, "could not read the state", it) }.getOrNull()
    }

    private fun build(): State? {
        val info = data.optJSONObject("SessionInfo") ?: return null
        val type = info.optString("Type")
        val race = type.equals("Race", ignoreCase = true)
        val drivers = data.optJSONObject("DriverList")
        val lines = data.optJSONObject("TimingData")?.optJSONObject("Lines") ?: JSONObject()
        val total = segmentCounts().sum().coerceAtLeast(1)
        val cars = lines.keys().asSequence().mapNotNull { number ->
            val line = lines.optJSONObject(number) ?: return@mapNotNull null
            val driver = drivers?.optJSONObject(number)
            val position = line.optString("Position").toIntOrNull() ?: return@mapNotNull null
            val best = line.optJSONObject("BestLapTime")?.optString("Value").orEmpty()
            val gap = if (race) line.optString("GapToLeader") else line.optString("TimeDiffToFastest")
            Car(
                number = number,
                code = driver?.optString("Tla")?.ifEmpty { null } ?: number,
                colour = driver?.optString("TeamColour")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: 0xFF9AA0A6.toInt(),
                position = position,
                figure = when {
                    position == 1 && !race -> best
                    position == 1 && race -> line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty().ifEmpty { "Leader" }
                    else -> gap
                },
                segment = lastSegment[number],
                segmentAt = lastSegmentAt[number] ?: 0L,
                segments = total,
                lapMs = lapMs(line.optJSONObject("LastLapTime")?.optString("Value"), best),
                inPit = line.optBoolean("InPit") || line.optBoolean("PitOut"),
                out = line.optBoolean("Retired") || line.optBoolean("Stopped") || line.optBoolean("KnockedOut"),
                name = driver?.optString("FullName")?.ifEmpty { null } ?: driver?.optString("BroadcastName").orEmpty(),
                interval = line.optJSONObject("IntervalToPositionAhead")?.optString("Value").orEmpty(),
                bestLap = best,
                lastLap = line.optJSONObject("LastLapTime")?.optString("Value").orEmpty(),
                laps = line.optInt("NumberOfLaps"),
                pitStops = line.optInt("NumberOfPitStops"),
                tyre = stint(number)?.optString("Compound").orEmpty(),
                tyreAge = stint(number)?.optInt("TotalLaps") ?: 0,
            )
        }.sortedBy { it.position }.toList()
        val clock = data.optJSONObject("ExtrapolatedClock")
        val remaining = clock?.optString("Remaining")?.let { parseClock(it) }?.let { left ->
            if (clock.optBoolean("Extrapolating")) {
                val since = runCatching { System.currentTimeMillis() - java.time.Instant.parse(clock.optString("Utc")).toEpochMilli() }.getOrDefault(0L)
                (left - since).coerceAtLeast(0L)
            } else left
        }
        val laps = data.optJSONObject("LapCount")
        return State(
            meeting = info.optJSONObject("Meeting")?.optString("Name").orEmpty(),
            session = info.optString("Name"),
            race = race,
            trackStatus = data.optJSONObject("TrackStatus")?.optString("Status").orEmpty().ifEmpty { "1" },
            lap = laps?.optInt("CurrentLap")?.takeIf { it > 0 },
            totalLaps = laps?.optInt("TotalLaps")?.takeIf { it > 0 },
            remainingMs = remaining,
            cars = cars,
            at = System.currentTimeMillis(),
        )
    }

    /** A car's current tyre stint (its last), from TimingAppData. */
    private fun stint(number: String): JSONObject? =
        data.optJSONObject("TimingAppData")?.optJSONObject("Lines")?.optJSONObject(number)?.opt("Stints").asList().lastOrNull() as? JSONObject

    /** The car's usual lap: its last if it was close to its best (not an out lap or a slow one), else its best. */
    private fun lapMs(last: String?, best: String?): Long? {
        val b = parseLap(best)
        val l = parseLap(last)
        return when {
            l != null && b != null -> if (l < b * 1.15) l else b
            else -> l ?: b
        }
    }

    /** "1:17.930" (or "57.3") as milliseconds. */
    fun parseLap(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(":")
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = if (parts.size > 1) parts[parts.size - 2].toLongOrNull() ?: return null else 0L
        return minutes * 60_000 + (seconds * 1000).toLong()
    }

    /** "00:31:39" as milliseconds. */
    private fun parseClock(text: String): Long? {
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) return null
        return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000
    }

    /** One request, keeping the feed's cookies (it needs the same server for the whole connection). */
    private fun http(url: String, method: String, body: String?, cookies: MutableMap<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            connection.headerFields["Set-Cookie"]?.forEach { header ->
                val pair = header.substringBefore(';')
                cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
            val code = connection.responseCode
            if (code == 204) throw IllegalStateException("the feed ended the connection")
            if (code !in 200..299) throw IllegalStateException("the feed answered $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
