package com.ataglanceplus.weather

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Rain or snow on the way: when it starts and stops, how heavy, and the next hour by quarter. */
data class RainSoon(
    val startsAt: Long,
    /** When the wet spell ends, if it ends within the two hours fetched. */
    val endsAt: Long?,
    val snow: Boolean,
    /** "Light", "Moderate" or "Heavy". */
    val intensity: String,
    /** The next four quarter-hours: wet or not. */
    val quarters: List<Boolean>,
) {
    /** One instance: a dismissal lasts until the rain card goes (then the next spell shows again). */
    val key: String get() = "rain"
}

/**
 * The rain (or snow) card's source: Open-Meteo's 15-minute precipitation for the next two hours,
 * at the weather's location (rounded to about 1 km), fetched at most every 10 minutes while the
 * card is on. Each value is the precipitation in the 15 minutes before its time.
 */
object RainSource {

    private const val TAG = "RainSource"
    private const val STALE_MS = 10 * 60_000L
    /** Wet: at least this much in a quarter-hour (about drizzle). */
    private const val WET_MM = 0.1

    private data class Step(val start: Long, val end: Long, val mm: Double, val snow: Boolean)

    @Volatile private var steps: List<Step> = emptyList()
    @Volatile private var fetchedAt = 0L

    private val _changed = MutableStateFlow(0L)
    /** Bumped when a new forecast arrives, so the cards redraw. */
    val changed: StateFlow<Long> = _changed.asStateFlow()

    /** Fetches the next two hours unless the last fetch is under 10 minutes old. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - fetchedAt < STALE_MS) return@withContext
        val (lat, lon) = WeatherRepository.lastLocation(context) ?: return@withContext
        runCatching {
            val url = String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&minutely_15=precipitation,snowfall,weather_code&forecast_minutely_15=9&timezone=auto",
                lat, lon,
            )
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", "AtAGlancePlus/1 (Android)")
            }
            val body = try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
            val root = JSONObject(body ?: return@runCatching)
            val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
            val m = root.getJSONObject("minutely_15")
            val times = m.getJSONArray("time")
            val rain = m.optJSONArray("precipitation")
            val snow = m.optJSONArray("snowfall")
            val codes = m.optJSONArray("weather_code")
            steps = (0 until times.length()).map { i ->
                val end = LocalDateTime.parse(times.getString(i)).toInstant(offset).toEpochMilli()
                val code = codes?.optInt(i) ?: 0
                Step(
                    end - 15 * 60_000L, end, rain?.optDouble(i)?.takeUnless { it.isNaN() } ?: 0.0,
                    (snow?.optDouble(i)?.takeUnless { it.isNaN() } ?: 0.0) > 0 || code in 71..77 || code == 85 || code == 86,
                )
            }
            fetchedAt = System.currentTimeMillis()
            _changed.value = fetchedAt
        }.onFailure { Log.w(TAG, "fetch failed", it) }
    }

    /**
     * Rain or snow starting within the hour, while it is dry now; null otherwise (raining
     * already, or nothing coming, or no forecast under 40 minutes old).
     */
    fun soon(now: Long = System.currentTimeMillis()): RainSoon? = soon(steps, fetchedAt, now)

    private fun soon(steps: List<Step>, fetchedAt: Long, now: Long): RainSoon? {
        if (now - fetchedAt > 40 * 60_000L) return null
        fun wet(s: Step) = s.mm >= WET_MM
        val current = steps.firstOrNull { now >= it.start && now < it.end }
        if (current != null && wet(current)) return null
        val first = steps.firstOrNull { it.start >= now - 60_000L && wet(it) } ?: return null
        if (first.start - now > 60 * 60_000L) return null
        val spell = steps.dropWhile { it !== first }.takeWhile { wet(it) }
        val end = steps.dropWhile { it !== first }.firstOrNull { !wet(it) }?.start
        val perHour = spell.maxOf { it.mm } * 4
        val quarters = (0 until 4).map { q ->
            val at = now + q * 15 * 60_000L
            steps.firstOrNull { at >= it.start && at < it.end }?.let(::wet) ?: false
        }
        return RainSoon(
            startsAt = first.start, endsAt = end, snow = spell.any { it.snow },
            intensity = when { perHour < 2.5 -> "Light"; perHour < 7.6 -> "Moderate"; else -> "Heavy" },
            quarters = quarters,
        )
    }

    /** For tests: the card's choice from given quarter-hours ([mm] per quarter, the first ending at [firstEnd]). */
    internal fun soonFrom(firstEnd: Long, mm: List<Double>, snow: List<Boolean>, now: Long): RainSoon? =
        soon(mm.indices.map { i -> Step(firstEnd + (i - 1) * 15 * 60_000L, firstEnd + i * 15 * 60_000L, mm[i], snow[i]) }, now, now)
}
