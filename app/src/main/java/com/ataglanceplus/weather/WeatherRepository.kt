package com.ataglanceplus.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.ataglanceplus.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** One hour of forecast. */
data class HourForecast(
    val time: Long, val temperature: Double, val code: Int, val isDay: Boolean,
    /** Chance of rain or snow in that hour, 0 to 100; null in old cached readings. */
    val rainChance: Int? = null,
) {
    val condition: WeatherCondition get() = WeatherCondition.fromWmo(code)
}

/** Current conditions for the date line. */
data class Weather(
    val temperature: Double,
    val feelsLike: Double?,
    val high: Double?,
    val low: Double?,
    val unit: TemperatureUnit,
    val code: Int,
    val isDay: Boolean,
    val fetchedAt: Long,
    /** Today's and tomorrow's sunrises and sunsets, epoch milliseconds; empty in old cached readings. */
    val sunrises: List<Long> = emptyList(),
    val sunsets: List<Long> = emptyList(),
    /** The next few hours, oldest first; empty in old cached readings. */
    val hourly: List<HourForecast> = emptyList(),
    /** Wind speed now in [windUnit] ("mph" or "km/h"); null in old cached readings. */
    val wind: Double? = null,
    val windUnit: String = "mph",
) {
    /**
     * One line for the card's extra row: when the weather changes ("Clear until 07:00, then
     * sunny"), rain on the way if it is not already the change, and the wind. Null when there
     * is nothing to say.
     */
    fun summary(now: Long = System.currentTimeMillis(), clock: (Long) -> String): String? {
        val upcoming = hourly.filter { it.time > now }
        val parts = mutableListOf<String>()
        val change = upcoming.firstOrNull { it.condition != condition }
        if (change != null) {
            parts += "${phrase(condition, isDayAt(now))} until ${clock(change.time)}, then ${phrase(change.condition, change.isDay).lowercase()}"
        } else if (upcoming.size >= 3) {
            parts += "${phrase(condition, isDayAt(now))} for the next ${upcoming.size} hours"
        }
        val wet = setOf(WeatherCondition.DRIZZLE, WeatherCondition.RAIN, WeatherCondition.SNOW, WeatherCondition.THUNDER)
        if (change?.condition !in wet && condition !in wet) {
            upcoming.firstOrNull { (it.rainChance ?: 0) >= 50 }?.let { parts += "Rain likely at ${clock(it.time)} (${it.rainChance}%)" }
        }
        wind?.let { parts += "Wind ${it.roundToInt()} $windUnit" }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** "Sunny" for a clear day, "Clear" at night; otherwise the condition's name. */
    private fun phrase(c: WeatherCondition, day: Boolean): String = if (c == WeatherCondition.CLEAR && day) "Sunny" else c.label

    /** The next [count] whole hours after now. */
    fun nextHours(count: Int, now: Long = System.currentTimeMillis()): List<HourForecast> =
        hourly.filter { it.time > now }.take(count)

    val condition: WeatherCondition get() = WeatherCondition.fromWmo(code)
    val temperatureText: String get() = "${temperature.roundToInt()}${unit.symbol}"

    /** "H16° L8° · Feels 13°": the same as [detailText], shorter, for the narrow weather layouts. */
    val compactDetail: String
        get() = listOfNotNull(
            listOfNotNull(high?.let { "H${it.roundToInt()}°" }, low?.let { "L${it.roundToInt()}°" }).joinToString(" ").ifEmpty { null },
            feelsLike?.let { "Feels ${it.roundToInt()}°" }
        ).joinToString(" · ")

    /**
     * Day or night now, from the sun times rather than the reading, which can be up to half an
     * hour old (otherwise the sun icon could stay up after sunset).
     */
    fun isDayAt(now: Long = System.currentTimeMillis()): Boolean {
        val last = (sunrises.map { it to true } + sunsets.map { it to false })
            .filter { it.first <= now }
            .maxByOrNull { it.first }
        return last?.second ?: isDay
    }

    /** The next sunrise or sunset: its time and whether it is a sunrise. */
    fun nextSunEvent(now: Long = System.currentTimeMillis()): Pair<Long, Boolean>? =
        (sunrises.map { it to true } + sunsets.map { it to false })
            .filter { it.first > now }
            .minByOrNull { it.first }

    /** "H 15° · L 8° · Feels 9°", leaving out anything the service did not return. */
    val detailText: String
        get() = listOfNotNull(
            high?.let { "H ${it.roundToInt()}°" },
            low?.let { "L ${it.roundToInt()}°" },
            feelsLike?.let { "Feels ${it.roundToInt()}°" }
        ).joinToString(" · ")
}

enum class TemperatureUnit(val apiValue: String, val symbol: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    companion object {
        /** Fahrenheit only where it is the everyday unit. */
        fun forLocale(locale: Locale = Locale.getDefault()): TemperatureUnit =
            if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) FAHRENHEIT else CELSIUS
    }
}

/**
 * Weather from Open-Meteo (https://open-meteo.com): free, no API key. The location is coarse
 * (city level) and only the rounded coordinates are sent.
 */
object WeatherRepository {

    private const val TAG = "Weather"
    private const val STALE_AFTER_MS = 30 * 60 * 1000L
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"
    private const val KEY_CACHE = "weather_cache"

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val _status = MutableStateFlow("")
    /** Why the last refresh did or did not work, for the settings screen. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val lock = Mutex()

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Loads the last saved reading so the card has something to show straight after a restart. */
    fun loadCached(context: Context) {
        if (_weather.value != null) return
        val raw = Prefs.get(context).getString(KEY_CACHE, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            _weather.value = Weather(
                temperature = json.getDouble("t"),
                feelsLike = json.optDoubleOrNull("fl"),
                high = json.optDoubleOrNull("hi"),
                low = json.optDoubleOrNull("lo"),
                unit = TemperatureUnit.valueOf(json.getString("u")),
                code = json.getInt("c"),
                isDay = json.getBoolean("d"),
                fetchedAt = json.getLong("f"),
                sunrises = json.optJSONArray("sr").toLongList(),
                sunsets = json.optJSONArray("ss").toLongList(),
                hourly = json.optJSONArray("h")?.let { array ->
                    (0 until array.length()).map { i ->
                        val h = array.getJSONObject(i)
                        HourForecast(h.getLong("t"), h.getDouble("v"), h.getInt("c"), h.getBoolean("d"), if (h.has("r")) h.getInt("r") else null)
                    }
                }.orEmpty(),
                wind = json.optDoubleOrNull("w"),
                windUnit = json.optString("wu", "mph"),
            )
        }
    }

    /** Fetches fresh weather unless the current reading is recent. Safe to call often. */
    suspend fun refresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCached(context)
            val current = _weather.value
            if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < STALE_AFTER_MS) return@withLock

            val location = currentLocation(context)
            val (lat, lon) = when {
                location != null -> {
                    saveLocation(context, location.latitude, location.longitude)
                    location.latitude to location.longitude
                }
                else -> savedLocation(context) ?: run {
                    _status.value = if (hasLocationPermission(context)) "Waiting for a location fix" else "Location permission needed for weather"
                    return@withLock
                }
            }
            try {
                val weather = fetch(lat, lon, TemperatureUnit.forLocale())
                _weather.value = weather
                save(context, weather)
                _status.value = if (location == null) "Updated using your last known location" else "Updated"
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed", e)
                _status.value = "Could not reach the weather service"
            }
        }
    }

    /** Miles an hour where road speeds are in mph (the UK, the US and a few others), else km/h. */
    private fun windApiUnit(): String = if (Locale.getDefault().country in setOf("GB", "US", "LR", "MM")) "mph" else "kmh"

    private fun fetch(lat: Double, lon: Double, unit: TemperatureUnit): Weather {
        // Two decimal places is about 1 km: enough for weather, not a precise position.
        val url = URL(
            String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=2" +
                    "&hourly=temperature_2m,weather_code,is_day,precipitation_probability&forecast_hours=12" +
                    "&temperature_unit=%s&wind_speed_unit=%s&timezone=auto",
                lat, lon, unit.apiValue, windApiUnit()
            )
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "AtAGlancePlus/1 (Android)")
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val current = root.getJSONObject("current")
            val daily = root.optJSONObject("daily")
            // Times come in the location's local time; this offset turns them into instants.
            val offset = java.time.ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
            fun times(name: String): List<Long> {
                val array = daily?.optJSONArray(name) ?: return emptyList()
                return (0 until array.length()).mapNotNull { i ->
                    if (array.isNull(i)) null // no sunrise or sunset that day (polar regions)
                    else runCatching { java.time.LocalDateTime.parse(array.getString(i)).toInstant(offset).toEpochMilli() }.getOrNull()
                }
            }
            return Weather(
                temperature = current.getDouble("temperature_2m"),
                feelsLike = current.optDoubleOrNull("apparent_temperature"),
                high = daily?.optJSONArray("temperature_2m_max")?.optDoubleOrNull(0),
                low = daily?.optJSONArray("temperature_2m_min")?.optDoubleOrNull(0),
                unit = unit,
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                fetchedAt = System.currentTimeMillis(),
                sunrises = times("sunrise"),
                sunsets = times("sunset"),
                hourly = hours(root.optJSONObject("hourly"), offset),
                wind = current.optDoubleOrNull("wind_speed_10m"),
                windUnit = if (windApiUnit() == "mph") "mph" else "km/h",
            )
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission
    private suspend fun currentLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val recent = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < STALE_AFTER_MS) return recent
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                try {
                    manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, context.mainExecutor) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return fresh ?: recent
    }

    private fun saveLocation(context: Context, lat: Double, lon: Double) {
        Prefs.get(context).edit().putFloat(KEY_LAT, lat.toFloat()).putFloat(KEY_LON, lon.toFloat()).apply()
    }

    private fun savedLocation(context: Context): Pair<Double, Double>? {
        val prefs = Prefs.get(context)
        if (!prefs.contains(KEY_LAT)) return null
        return prefs.getFloat(KEY_LAT, 0f).toDouble() to prefs.getFloat(KEY_LON, 0f).toDouble()
    }

    private fun save(context: Context, weather: Weather) {
        val json = JSONObject()
            .put("t", weather.temperature)
            .put("fl", weather.feelsLike)
            .put("hi", weather.high)
            .put("lo", weather.low)
            .put("u", weather.unit.name)
            .put("c", weather.code)
            .put("d", weather.isDay)
            .put("f", weather.fetchedAt)
            .put("sr", org.json.JSONArray(weather.sunrises))
            .put("ss", org.json.JSONArray(weather.sunsets))
            .put("h", org.json.JSONArray(weather.hourly.map {
                JSONObject().put("t", it.time).put("v", it.temperature).put("c", it.code).put("d", it.isDay).apply { it.rainChance?.let { r -> put("r", r) } }
            }))
            .put("w", weather.wind)
            .put("wu", weather.windUnit)
        Prefs.get(context).edit().putString(KEY_CACHE, json.toString()).apply()
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeUnless { it.isNaN() } else null

/** Hourly forecast entries; times come in the location's local time, like the sun times. */
private fun hours(hourly: JSONObject?, offset: java.time.ZoneOffset): List<HourForecast> {
    if (hourly == null) return emptyList()
    val times = hourly.optJSONArray("time") ?: return emptyList()
    val temps = hourly.optJSONArray("temperature_2m") ?: return emptyList()
    val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
    val days = hourly.optJSONArray("is_day")
    val rain = hourly.optJSONArray("precipitation_probability")
    return (0 until times.length()).mapNotNull { i ->
        if (temps.isNull(i) || codes.isNull(i)) return@mapNotNull null
        val time = runCatching { java.time.LocalDateTime.parse(times.getString(i)).toInstant(offset).toEpochMilli() }.getOrNull()
            ?: return@mapNotNull null
        HourForecast(time, temps.getDouble(i), codes.getInt(i), days?.optInt(i, 1) != 0, rain?.takeIf { !it.isNull(i) }?.optInt(i))
    }
}

private fun org.json.JSONArray?.toLongList(): List<Long> =
    if (this == null) emptyList() else (0 until length()).map { getLong(it) }

private fun org.json.JSONArray.optDoubleOrNull(index: Int): Double? =
    if (length() > index && !isNull(index)) optDouble(index).takeUnless { it.isNaN() } else null
