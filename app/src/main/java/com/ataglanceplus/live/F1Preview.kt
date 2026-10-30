package com.ataglanceplus.live

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * What to know before a Formula 1 session: the forecast at the circuit for its start (Open-Meteo,
 * as the weather card uses), the circuit's local time, and last year's winner and pole there
 * (Jolpica).
 */
object F1Preview {

    private const val TAG = "F1Preview"

    data class Forecast(val temperature: Double, val rainChance: Int?, val windKmh: Double?, val code: Int)

    data class Preview(
        val circuit: String,
        val place: String,
        /** The circuit's offset from UTC at the session's start, in seconds, from the forecast. */
        val utcOffsetSeconds: Int?,
        val forecast: Forecast?,
        val lastWinner: String?,
        val lastPole: String?,
        val lastYear: Int,
    )

    private fun json(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) null else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** The preview for a session starting at [startMs] in the weekend whose race is on [raceDay]. */
    fun load(raceDay: LocalDate, startMs: Long): Preview? {
        val year = raceDay.year
        val races = json("https://api.jolpi.ca/ergast/f1/$year/races.json?limit=40")
            ?.getJSONObject("MRData")?.getJSONObject("RaceTable")?.getJSONArray("Races") ?: return null
        val race = (0 until races.length()).map { races.getJSONObject(it) }.firstOrNull {
            runCatching { kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(it.getString("date")), raceDay)) <= 3 }.getOrDefault(false)
        } ?: return null
        val circuit = race.getJSONObject("Circuit")
        val location = circuit.getJSONObject("Location")
        val lat = location.optString("lat").toDoubleOrNull()
        val lon = location.optString("long").toDoubleOrNull()
        val id = circuit.optString("circuitId")

        // The forecast hour nearest the start, in the circuit's own time zone.
        var offset: Int? = null
        val forecast = if (lat != null && lon != null) {
            val day = Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
            json(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto" +
                    "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code" +
                    "&start_date=${day.minusDays(1)}&end_date=${day.plusDays(1)}",
            )?.let { w ->
                offset = w.optInt("utc_offset_seconds")
                val hourly = w.optJSONObject("hourly") ?: return@let null
                val times = hourly.getJSONArray("time")
                val wanted = Instant.ofEpochMilli(startMs).plusSeconds((offset ?: 0).toLong()).atZone(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.HOURS).toString()
                val i = (0 until times.length()).firstOrNull { times.getString(it).startsWith(wanted.take(13)) } ?: return@let null
                Forecast(
                    temperature = hourly.getJSONArray("temperature_2m").optDouble(i),
                    rainChance = hourly.optJSONArray("precipitation_probability")?.takeIf { !it.isNull(i) }?.optInt(i),
                    windKmh = hourly.optJSONArray("wind_speed_10m")?.optDouble(i)?.takeIf { !it.isNaN() },
                    code = hourly.optJSONArray("weather_code")?.optInt(i) ?: 0,
                )
            }
        } else null

        fun last(kind: String, key: String): String? =
            json("https://api.jolpi.ca/ergast/f1/${year - 1}/circuits/$id/$kind/1.json")
                ?.getJSONObject("MRData")?.getJSONObject("RaceTable")?.getJSONArray("Races")?.optJSONObject(0)
                ?.optJSONArray(key)?.optJSONObject(0)?.let { r ->
                    val d = r.getJSONObject("Driver")
                    "${d.optString("givenName")} ${d.optString("familyName")} · ${r.optJSONObject("Constructor")?.optString("name").orEmpty()}"
                }

        return Preview(
            circuit = circuit.optString("circuitName"),
            place = listOf(location.optString("locality"), location.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
            utcOffsetSeconds = offset,
            forecast = forecast,
            lastWinner = last("results", "Results"),
            lastPole = last("qualifying", "QualifyingResults"),
            lastYear = year - 1,
        )
    }
}
