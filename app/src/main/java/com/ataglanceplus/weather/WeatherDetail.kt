package com.ataglanceplus.weather

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Everything the weather screen shows, fetched when it opens (the card keeps its own, smaller
 * reading): the next 24 hours, 7 days, UV, wind, humidity and pressure from Open-Meteo, and air
 * quality from Open-Meteo's air-quality service (CAMS). Free, no key; only the coordinates
 * rounded to two decimals (about 1 km) are sent, as for the card.
 */
object WeatherDetail {

    private const val TAG = "WeatherDetail"
    private const val UA = "AtAGlancePlus/1 (Android)"

    data class Now(
        val temperature: Double, val feelsLike: Double?, val code: Int, val isDay: Boolean,
        val wind: Double?, val windFrom: Int?, val gusts: Double?, val humidity: Int?, val pressure: Double?, val uv: Double?,
    ) {
        val condition get() = WeatherCondition.fromWmo(code)
    }

    data class Hour(val time: Long, val temperature: Double, val code: Int, val isDay: Boolean, val rainChance: Int?, val rainMm: Double?, val uv: Double?) {
        val condition get() = WeatherCondition.fromWmo(code)
    }

    data class Day(
        val date: LocalDate, val high: Double, val low: Double, val code: Int, val rainChance: Int?, val rainMm: Double?,
        val sunrise: Long?, val sunset: Long?, val uvMax: Double?, val daylightSeconds: Double?,
    ) {
        val condition get() = WeatherCondition.fromWmo(code)
    }

    /** Air quality now: the index ([european] scale, else the US one) and the main pollutants in µg/m³. */
    data class Air(val index: Int, val european: Boolean, val pm25: Double?, val pm10: Double?, val no2: Double?, val ozone: Double?)

    data class Forecast(
        val now: Now, val hours: List<Hour>, val days: List<Day>, val air: Air?,
        val unit: TemperatureUnit, val windUnit: String, val place: String?,
        val latitude: Double, val longitude: Double, val fetchedAt: Long,
    )

    @Volatile private var last: Forecast? = null

    /** Uses the European air-quality index except where the US one is the everyday scale. */
    private fun europeanAqi() = Locale.getDefault().country != "US"

    /**
     * The forecast for the last weather location; a copy under 10 minutes old unless [force].
     * Null before the card has had a location, or if Open-Meteo did not answer. Blocking.
     */
    suspend fun load(context: Context, force: Boolean = false): Forecast? {
        val (lat, lon) = WeatherRepository.lastLocation(context) ?: return null
        last?.let { f ->
            if (!force && System.currentTimeMillis() - f.fetchedAt < 10 * 60_000L && f.latitude == lat && f.longitude == lon) return f
        }
        val unit = TemperatureUnit.forLocale()
        val wind = WeatherRepository.windApi()
        val forecast = runCatching {
            val root = get(
                String.format(
                    Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                        "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m,relative_humidity_2m,pressure_msl,uv_index" +
                        "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,precipitation,uv_index&forecast_hours=26" +
                        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,precipitation_sum,sunrise,sunset,uv_index_max,daylight_duration&forecast_days=7" +
                        "&temperature_unit=%s&wind_speed_unit=%s&timezone=auto",
                    lat, lon, unit.apiValue, wind,
                ),
            ) ?: return@runCatching null
            parse(root, unit, if (wind == "mph") "mph" else "km/h", lat, lon)
        }.onFailure { Log.w(TAG, "forecast failed", it) }.getOrNull() ?: return last
        val air = runCatching {
            get(String.format(Locale.ROOT, "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.2f&longitude=%.2f&current=european_aqi,us_aqi,pm2_5,pm10,nitrogen_dioxide,ozone&timezone=auto", lat, lon))
                ?.optJSONObject("current")?.let { c ->
                    val european = europeanAqi()
                    val index = c.optDouble(if (european) "european_aqi" else "us_aqi", Double.NaN)
                    if (index.isNaN()) null
                    else Air(index.toInt(), european, c.num("pm2_5"), c.num("pm10"), c.num("nitrogen_dioxide"), c.num("ozone"))
                }
        }.onFailure { Log.w(TAG, "air quality failed", it) }.getOrNull()
        val place = last?.takeIf { it.latitude == lat && it.longitude == lon }?.place ?: placeName(context, lat, lon)
        return forecast.copy(air = air, place = place).also { last = it }
    }

    /** The town or district, from the phone's geocoder; null if it has none. */
    private suspend fun placeName(context: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            val a = addresses.firstOrNull()
                            if (cont.isActive) cont.resume(a?.locality ?: a?.subAdminArea ?: a?.adminArea)
                        }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private fun parse(root: JSONObject, unit: TemperatureUnit, windUnit: String, lat: Double, lon: Double): Forecast {
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
        fun time(text: String?): Long? = text?.takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { LocalDateTime.parse(it).toInstant(offset).toEpochMilli() }.getOrNull() }
        val c = root.getJSONObject("current")
        val now = Now(
            c.getDouble("temperature_2m"), c.num("apparent_temperature"), c.optInt("weather_code"), c.optInt("is_day", 1) == 1,
            c.num("wind_speed_10m"), c.num("wind_direction_10m")?.toInt(), c.num("wind_gusts_10m"),
            c.num("relative_humidity_2m")?.toInt(), c.num("pressure_msl"), c.num("uv_index"),
        )
        val h = root.optJSONObject("hourly")
        val start = System.currentTimeMillis() - 3_600_000L
        val hours = h?.optJSONArray("time")?.let { times ->
            (0 until times.length()).mapNotNull { i ->
                val t = time(times.optString(i)) ?: return@mapNotNull null
                val temp = h.optJSONArray("temperature_2m").at(i) ?: return@mapNotNull null
                Hour(
                    t, temp, h.optJSONArray("weather_code").at(i)?.toInt() ?: 0, h.optJSONArray("is_day").at(i) == 1.0,
                    h.optJSONArray("precipitation_probability").at(i)?.toInt(), h.optJSONArray("precipitation").at(i), h.optJSONArray("uv_index").at(i),
                )
            }.filter { it.time > start }.take(25)
        }.orEmpty()
        val d = root.optJSONObject("daily")
        val days = d?.optJSONArray("time")?.let { dates ->
            (0 until dates.length()).mapNotNull { i ->
                val date = runCatching { LocalDate.parse(dates.getString(i)) }.getOrNull() ?: return@mapNotNull null
                Day(
                    date,
                    d.optJSONArray("temperature_2m_max").at(i) ?: return@mapNotNull null,
                    d.optJSONArray("temperature_2m_min").at(i) ?: return@mapNotNull null,
                    d.optJSONArray("weather_code").at(i)?.toInt() ?: 0,
                    d.optJSONArray("precipitation_probability_max").at(i)?.toInt(),
                    d.optJSONArray("precipitation_sum").at(i),
                    time(d.optJSONArray("sunrise")?.optString(i)), time(d.optJSONArray("sunset")?.optString(i)),
                    d.optJSONArray("uv_index_max").at(i), d.optJSONArray("daylight_duration").at(i),
                )
            }
        }.orEmpty()
        return Forecast(now, hours, days, null, unit, windUnit, null, lat, lon, System.currentTimeMillis())
    }

    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeUnless { it.isNaN() } else null
    private fun JSONArray?.at(i: Int): Double? = this?.takeIf { i < it.length() && !it.isNull(i) }?.optDouble(i)?.takeUnless { it.isNaN() }

    private fun get(url: String): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
        }
        return try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
    }

    /** A rain radar centred on the location (RainViewer's map). */
    fun radarUrl(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "https://www.rainviewer.com/map.html?loc=%.2f,%.2f,8&layer=radar&sm=1&sn=1", lat, lon)
}
