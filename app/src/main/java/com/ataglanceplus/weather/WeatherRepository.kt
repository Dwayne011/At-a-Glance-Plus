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

/** Current conditions for the date line. */
data class Weather(
    val temperature: Double,
    val unit: TemperatureUnit,
    val code: Int,
    val isDay: Boolean,
    val fetchedAt: Long
) {
    val condition: WeatherCondition get() = WeatherCondition.fromWmo(code)
    val temperatureText: String get() = "${temperature.roundToInt()}${unit.symbol}"
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
                unit = TemperatureUnit.valueOf(json.getString("u")),
                code = json.getInt("c"),
                isDay = json.getBoolean("d"),
                fetchedAt = json.getLong("f")
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

    private fun fetch(lat: Double, lon: Double, unit: TemperatureUnit): Weather {
        // Two decimal places is about 1 km: enough for weather, not a precise position.
        val url = URL(
            String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,is_day&temperature_unit=%s&timezone=auto",
                lat, lon, unit.apiValue
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
            val current = JSONObject(body).getJSONObject("current")
            return Weather(
                temperature = current.getDouble("temperature_2m"),
                unit = unit,
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                fetchedAt = System.currentTimeMillis()
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
            .put("u", weather.unit.name)
            .put("c", weather.code)
            .put("d", weather.isDay)
            .put("f", weather.fetchedAt)
        Prefs.get(context).edit().putString(KEY_CACHE, json.toString()).apply()
    }
}
