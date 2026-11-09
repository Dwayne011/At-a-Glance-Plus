package com.ataglanceplus.live

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.CardRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The commute card's journey: to work or home in the windows the user sets, from where the phone
 * is (or the other saved place), by public transport (TfL's journey planner: London and around,
 * free, no key) and by car (OSRM's public router: free, no live traffic). Fetched every 5 minutes
 * in a window while the screen is on and the card is on.
 */
object CommuteSource {

    private const val TAG = "Commute"
    private const val KEY_HOME = "commute_home"
    private const val KEY_WORK = "commute_work"
    private const val KEY_TO_WORK = "commute_to_work"
    private const val KEY_TO_HOME = "commute_to_home"
    private const val KEY_TAP = "commute_tap_drive"
    private const val STALE_MS = 5 * 60_000L
    private val LONDON = ZoneId.of("Europe/London")

    data class Place(val label: String, val lat: Double, val lon: Double) {
        fun toJson(): JSONObject = JSONObject().put("l", label).put("a", lat).put("o", lon)
        companion object { fun fromJson(o: JSONObject) = Place(o.getString("l"), o.getDouble("a"), o.getDouble("o")) }
    }

    /** One journey: by public transport (its first ride, times, line trouble) and by car. */
    data class Trip(
        val toWork: Boolean,
        val destination: Place,
        val transitMinutes: Int?,
        val departs: Long?,
        val arrives: Long?,
        /** "Jubilee line" or "N343 bus": the first ride. */
        val firstRide: String?,
        val firstRideAt: Long?,
        val mode: TravelMode?,
        /** "Minor delays on Jubilee", for lines on the journey that are not in good service. */
        val trouble: String?,
        val driveMinutes: Int?,
        val fetchedAt: Long,
    ) {
        val key: String get() = "commute:" + if (toWork) "work" else "home"
    }

    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip.asStateFlow()

    private fun place(context: Context, key: String): Place? =
        Prefs.get(context).getString(key, null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    fun home(context: Context) = place(context, KEY_HOME)
    fun work(context: Context) = place(context, KEY_WORK)
    fun setHome(context: Context, p: Place?) = Prefs.get(context).edit { if (p == null) remove(KEY_HOME) else putString(KEY_HOME, p.toJson().toString()) }
    fun setWork(context: Context, p: Place?) = Prefs.get(context).edit { if (p == null) remove(KEY_WORK) else putString(KEY_WORK, p.toJson().toString()) }

    private fun window(context: Context, key: String, default: CardRules.Rule): CardRules.Rule =
        Prefs.get(context).getString(key, null)?.let { runCatching { CardRules.Rule.fromJson(JSONObject(it)) }.getOrNull() } ?: default

    fun toWorkWindow(context: Context) = window(context, KEY_TO_WORK, CardRules.Rule(7 * 60, 9 * 60 + 30, (1..5).toSet(), emptyList()))
    fun toHomeWindow(context: Context) = window(context, KEY_TO_HOME, CardRules.Rule(16 * 60 + 30, 19 * 60, (1..5).toSet(), emptyList()))
    fun setToWorkWindow(context: Context, r: CardRules.Rule) = Prefs.get(context).edit { putString(KEY_TO_WORK, r.toJson().toString()) }
    fun setToHomeWindow(context: Context, r: CardRules.Rule) = Prefs.get(context).edit { putString(KEY_TO_HOME, r.toJson().toString()) }

    /** Whether tapping the card starts driving navigation (else public transport directions). */
    fun tapDrives(context: Context) = Prefs.get(context).getBoolean(KEY_TAP, false)
    fun setTapDrives(context: Context, on: Boolean) = Prefs.get(context).edit { putBoolean(KEY_TAP, on) }

    /** Where the journey goes now: true to work, false home, null outside both windows. */
    fun direction(context: Context, now: LocalDateTime = LocalDateTime.now()): Boolean? = when {
        toWorkWindow(context).covers(now) -> true
        toHomeWindow(context).covers(now) -> false
        else -> null
    }

    /** The trip to show now: fetched for the window we are in, under 20 minutes old. */
    fun showing(context: Context): Trip? {
        val t = _trip.value ?: return null
        val dir = direction(context) ?: return null
        return t.takeIf { it.toWork == dir && System.currentTimeMillis() - it.fetchedAt < 20 * 60_000L }
    }

    /** Fetches the journey if in a window, both places are set and the last fetch is stale. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val toWork = direction(context) ?: return@withContext
        val home = home(context) ?: return@withContext
        val work = work(context) ?: return@withContext
        val last = _trip.value
        if (last != null && last.toWork == toWork && System.currentTimeMillis() - last.fetchedAt < STALE_MS) return@withContext
        val destination = if (toWork) work else home
        // From where the phone is, when known lately; else from the other saved place.
        val here = com.ataglanceplus.wallet.WalletGeofences.cachedLocation?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
        val from = here?.let { it.latitude to it.longitude } ?: (if (toWork) home else work).let { it.lat to it.lon }
        // Already there: no card.
        val left = FloatArray(1).also { Location.distanceBetween(from.first, from.second, destination.lat, destination.lon, it) }[0]
        if (left < 300f) { _trip.value = null; return@withContext }
        val transit = runCatching { tfl(from, destination) }.onFailure { Log.w(TAG, "TfL failed", it) }.getOrNull()
        val drive = runCatching { osrm(from, destination) }.onFailure { Log.w(TAG, "OSRM failed", it) }.getOrNull()
        if (transit == null && drive == null) return@withContext
        _trip.value = Trip(
            toWork, destination,
            transit?.minutes, transit?.departs, transit?.arrives, transit?.firstRide, transit?.firstRideAt, transit?.mode, transit?.trouble,
            drive, System.currentTimeMillis(),
        )
    }

    private data class Transit(val minutes: Int, val departs: Long?, val arrives: Long?, val firstRide: String?, val firstRideAt: Long?, val mode: TravelMode, val trouble: String?)

    private fun get(url: String): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "AtAGlancePlus/1 (Android)")
        }
        return try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    }

    private fun london(text: String?): Long? = text?.let { runCatching { LocalDateTime.parse(it).atZone(LONDON).toInstant().toEpochMilli() }.getOrNull() }

    /** TfL's soonest journey: null outside its area (or when it has none). */
    private fun tfl(from: Pair<Double, Double>, to: Place): Transit? {
        val root = get(String.format(Locale.ROOT, "https://api.tfl.gov.uk/Journey/JourneyResults/%.5f,%.5f/to/%.5f,%.5f", from.first, from.second, to.lat, to.lon)) ?: return null
        val j = root.optJSONArray("journeys")?.optJSONObject(0) ?: return null
        val legs = j.optJSONArray("legs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val ride = legs.firstOrNull { it.optJSONObject("mode")?.optString("name") != "walking" }
        val rideMode = ride?.optJSONObject("mode")?.optString("name")
        val summary = ride?.optJSONObject("instruction")?.optString("summary")?.ifEmpty { null }
        // Lines on this journey that are not in good service.
        val used = legs.flatMap { leg -> leg.optJSONArray("routeOptions")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("lineIdentifier")?.optString("name") } }.orEmpty() }.toSet()
        val trouble = root.optJSONArray("lines")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("name") in used }
            .mapNotNull { l ->
                val status = l.optJSONArray("lineStatuses")?.optJSONObject(0)?.optString("statusSeverityDescription").orEmpty()
                if (status.isEmpty() || status == "Good Service") null else "$status on ${l.optString("name")}"
            }.firstOrNull()
        return Transit(
            j.optInt("duration"), london(j.optString("startDateTime")), london(j.optString("arrivalDateTime")),
            summary?.substringBefore(" to ")?.trim(), london(ride?.optString("departureTime")),
            when (rideMode) { null -> TravelMode.WALK; "bus" -> TravelMode.BUS; else -> TravelMode.TRANSIT },
            trouble,
        )
    }

    /** Driving minutes by OSRM's public router (no traffic). */
    private fun osrm(from: Pair<Double, Double>, to: Place): Int? {
        val root = get(String.format(Locale.ROOT, "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=false", from.second, from.first, to.lon, to.lat)) ?: return null
        val seconds = root.optJSONArray("routes")?.optJSONObject(0)?.optDouble("duration") ?: return null
        return (seconds / 60).toInt().coerceAtLeast(1)
    }
}
