package com.ataglanceplus.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ataglanceplus.weather.WeatherCondition
import com.ataglanceplus.weather.WeatherDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val RainBlue = Color(0xFF74C0FC)

/** The screen's backdrop, after the weather now. */
private fun weatherTint(c: WeatherCondition, day: Boolean): List<Color> = when (c) {
    WeatherCondition.CLEAR, WeatherCondition.PARTLY_CLOUDY ->
        if (day) listOf(Color(0xFF1C7ED6), Color(0xFFF59F00), Color(0xFF1864AB)) else listOf(Color(0xFF1B2A6B), Color(0xFF5F3DC4), Color(0xFF0B1A3E))
    WeatherCondition.CLOUDY, WeatherCondition.FOG -> listOf(Color(0xFF495E7A), Color(0xFF3B5BDB), Color(0xFF2B3445))
    WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> listOf(Color(0xFF1F5C7A), Color(0xFF364FC7), Color(0xFF1B2B3A))
    WeatherCondition.SNOW -> listOf(Color(0xFF6C8EBF), Color(0xFFA5D8FF), Color(0xFF34495E))
    WeatherCondition.THUNDER -> listOf(Color(0xFF5F3DC4), Color(0xFF1F2A44), Color(0xFF862E9C))
}

/**
 * The weather screen: now, the next 24 hours with the chance of rain, a rain radar, UV, air
 * quality, sunrise and sunset, wind, and the week. [generation] changes on pull to refresh.
 */
@Composable
internal fun WeatherDashboard(generation: Int, onLoaded: () -> Unit) {
    val context = LocalContext.current
    val load by produceState<Pair<Boolean, WeatherDetail.Forecast?>>(false to null, generation) {
        value = true to withContext(Dispatchers.IO) { WeatherDetail.load(context, force = generation > 0) }
        onLoaded()
    }
    val (done, f) = load
    GlassScreen(f?.let { weatherTint(it.now.condition, it.now.isDay) } ?: listOf(Color(0xFF1C7ED6), Color(0xFF3B5BDB), Color(0xFF1B2B3A)), accent = Color(0xFFA5D8FF)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !done -> Text("Loading…", color = GlassSub)
                f == null -> Text("No forecast yet: weather needs a location first (Settings › Weather), and Open-Meteo has to answer.", color = GlassSub)
                else -> WeatherContent(f)
            }
        }
    }
}

@Composable
private fun WeatherContent(f: WeatherDetail.Forecast) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val clock = remember(locale) { java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale) }
    fun at(ms: Long) = clock.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun deg(v: Double) = "${v.roundToInt()}°"
    val today = f.days.firstOrNull()
    val now = System.currentTimeMillis()

    // Now: the place, the temperature and the one picture.
    Glass(Modifier.fillMaxWidth(), padding = 16.dp) {
        Label(f.place ?: "Your location")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${f.now.temperature.roundToInt()}${f.unit.symbol}", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light, lineHeight = 60.sp)
                Text(if (f.now.condition == WeatherCondition.CLEAR && f.now.isDay) "Sunny" else f.now.condition.label, color = GlassText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(today?.let { "H ${deg(it.high)} · L ${deg(it.low)}" }, f.now.feelsLike?.let { "Feels ${deg(it)}" }).joinToString(" · "),
                    color = GlassSub, fontSize = 13.sp,
                )
            }
            Image(painterResource(f.now.condition.icon(f.now.isDay)), contentDescription = null, modifier = Modifier.size(84.dp))
        }
    }

    // The next 24 hours, each with its chance of rain as a bar; and a line on when rain comes.
    val hours = f.hours.filter { it.time > now - 3_600_000L }.take(25)
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Next 24 hours", Modifier.weight(1f))
        }
        val wet = hours.firstOrNull { (it.rainChance ?: 0) >= 50 }
        val total = hours.take(24).sumOf { it.rainMm ?: 0.0 }
        Text(
            when {
                wet != null && wet.time <= now -> "Rain likely now (${wet.rainChance}%)" + rainLasts(hours, wet, ::at)
                wet != null -> "Rain likely from ${at(wet.time)} (${wet.rainChance}%)" + rainLasts(hours, wet, ::at)
                (hours.maxOfOrNull { it.rainChance ?: 0 } ?: 0) >= 20 -> "A small chance of rain, ${hours.maxOf { it.rainChance ?: 0 }}% at most"
                else -> "No rain expected"
            } + if (total >= 0.1) String.format(locale, " · %.1f mm in all", total) else "",
            color = GlassText, fontSize = 13.5.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEachIndexed { i, h ->
                Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (i == 0) "Now" else at(h.time), color = GlassSub, fontSize = 11.sp, maxLines = 1)
                    Image(painterResource(h.condition.icon(h.isDay)), contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(deg(h.temperature), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val chance = h.rainChance ?: 0
                    Box(Modifier.height(40.dp).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(GlassTrack))
                        Box(Modifier.fillMaxWidth().fillMaxHeight(chance / 100f).clip(RoundedCornerShape(4.dp)).background(RainBlue))
                    }
                    Text("$chance%", color = if (chance >= 20) RainBlue else GlassSub, fontSize = 11.sp)
                }
            }
        }
        RadarButton(f)
    }

    // UV and air quality, as rings.
    GlassPair {
        Glass(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val uv = f.now.uv ?: 0.0
                val (level, colour) = uvLevel(uv)
                Label("UV index")
                Ring((uv / 11).toFloat().coerceIn(0f, 1f), colour, String.format(locale, "%.0f", uv))
                Text(level, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val peak = hours.filter { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == LocalDate.now() }.maxByOrNull { it.uv ?: 0.0 }
                Text(
                    today?.uvMax?.let { m -> String.format(locale, "Highest today %.0f", m) + (peak?.takeIf { (it.uv ?: 0.0) >= 1 && it.time > now }?.let { " at ${at(it.time)}" } ?: "") } ?: "",
                    color = GlassSub, fontSize = 11.5.sp, textAlign = TextAlign.Center,
                )
            }
        }
        Glass(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("Air quality")
                val air = f.air
                if (air == null) Text("Not available", color = GlassSub, fontSize = 13.sp)
                else {
                    val (level, colour) = aqiLevel(air)
                    Ring((air.index / if (air.european) 100f else 300f).coerceIn(0f, 1f), colour, "${air.index}")
                    Text(level, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(
                        (if (air.european) "European index" else "US index") + (air.pm25?.let { String.format(locale, " · PM2.5 %.0f µg/m³", it) } ?: ""),
                        color = GlassSub, fontSize = 11.5.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    // Sunrise and sunset, and the wind.
    GlassPair {
        Glass(Modifier.weight(1f).fillMaxHeight()) {
            Label("Sun")
            val rise = today?.sunrise
            val set = today?.sunset
            if (rise != null && set != null) {
                SunArc(((now - rise).toFloat() / (set - rise)).let { if (now in rise..set) it else -1f }, Modifier.fillMaxWidth().height(56.dp))
                val tomorrow = f.days.getOrNull(1)
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Sunrise", color = GlassSub, fontSize = 11.sp)
                        Figure(at(if (now > set && tomorrow?.sunrise != null) tomorrow.sunrise else rise), size = 15.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("Sunset", color = GlassSub, fontSize = 11.sp)
                        Figure(at(if (now > set && tomorrow?.sunset != null) tomorrow.sunset else set), size = 15.sp)
                    }
                }
                today.daylightSeconds?.let { s ->
                    val m = (s / 60).roundToInt()
                    Text("${m / 60} h ${m % 60} min of daylight" + if (now > set) " today" else "", color = GlassSub, fontSize = 11.5.sp)
                }
            } else Text("No sunrise or sunset today", color = GlassSub, fontSize = 13.sp)
        }
        Glass(Modifier.weight(1f).fillMaxHeight()) {
            Label("Wind")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                f.now.windFrom?.let { from ->
                    // The arrow points where the wind blows to.
                    Box(Modifier.size(34.dp).rotate(from + 180f)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val p = Path().apply {
                                moveTo(size.width / 2, 2f); lineTo(size.width * 0.85f, size.height * 0.8f)
                                lineTo(size.width / 2, size.height * 0.62f); lineTo(size.width * 0.15f, size.height * 0.8f); close()
                            }
                            drawPath(p, Color.White)
                        }
                    }
                }
                Column {
                    Figure("${f.now.wind?.roundToInt() ?: "–"} ${f.windUnit}", size = 18.sp)
                    f.now.windFrom?.let { Text("From the ${compass(it)}", color = GlassSub, fontSize = 11.5.sp) }
                }
            }
            f.now.gusts?.let { Text("Gusts ${it.roundToInt()} ${f.windUnit}", color = GlassText, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(6.dp))
            f.now.humidity?.let { Label("Humidity"); Figure("$it%", size = 15.sp) }
            f.now.pressure?.let { Label("Pressure"); Figure("${it.roundToInt()} hPa", size = 15.sp) }
        }
    }

    // The week: each day's weather, chance of rain and its range against the week's.
    if (f.days.isNotEmpty()) Glass(Modifier.fillMaxWidth()) {
        Label("7 days")
        val lo = f.days.minOf { it.low }
        val hi = f.days.maxOf { it.high }
        f.days.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (i == 0) "Today" else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), color = Color.White, fontSize = 14.sp, modifier = Modifier.width(52.dp))
                Image(painterResource(d.condition.icon(true)), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(d.rainChance?.takeIf { it >= 20 }?.let { "$it%" } ?: "", color = RainBlue, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                Text(deg(d.low), color = GlassSub, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
                RangeBar(((d.low - lo) / (hi - lo).coerceAtLeast(1.0)).toFloat(), ((d.high - lo) / (hi - lo).coerceAtLeast(1.0)).toFloat(), Modifier.weight(1f).height(6.dp))
                Text(deg(d.high), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (context.packageManager.getLaunchIntentForPackage("com.google.android.apps.weather") != null) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage("com.google.android.apps.weather")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Open Pixel Weather") }
        }
    }
    Text("Forecast from Open-Meteo; air quality from CAMS via Open-Meteo. Updated ${at(f.fetchedAt)}.", color = GlassSub, fontSize = 11.5.sp)
}

@Composable
private fun RadarButton(f: WeatherDetail.Forecast) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, WeatherDetail.radarUrl(f.latitude, f.longitude).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("Rain radar") }
}

/** ", until 18:00" when the rain's run (50% or more) ends within the hours shown. */
private fun rainLasts(hours: List<WeatherDetail.Hour>, from: WeatherDetail.Hour, at: (Long) -> String): String {
    val end = hours.dropWhile { it.time < from.time }.firstOrNull { (it.rainChance ?: 0) < 50 } ?: return ""
    return ", until ${at(end.time)}"
}

private fun uvLevel(uv: Double): Pair<String, Color> = when {
    uv < 3 -> "Low" to Color(0xFF69DB7C)
    uv < 6 -> "Moderate" to Color(0xFFFFD43B)
    uv < 8 -> "High" to Color(0xFFFFA94D)
    uv < 11 -> "Very high" to Color(0xFFFF6B6B)
    else -> "Extreme" to Color(0xFFDA77F2)
}

/** The European index's bands (0–20 good … over 100 extremely poor), or the US one's. */
private fun aqiLevel(air: WeatherDetail.Air): Pair<String, Color> = if (air.european) when {
    air.index <= 20 -> "Good" to Color(0xFF69DB7C)
    air.index <= 40 -> "Fair" to Color(0xFFA9E34B)
    air.index <= 60 -> "Moderate" to Color(0xFFFFD43B)
    air.index <= 80 -> "Poor" to Color(0xFFFFA94D)
    air.index <= 100 -> "Very poor" to Color(0xFFFF6B6B)
    else -> "Extremely poor" to Color(0xFFDA77F2)
} else when {
    air.index <= 50 -> "Good" to Color(0xFF69DB7C)
    air.index <= 100 -> "Moderate" to Color(0xFFFFD43B)
    air.index <= 150 -> "Unhealthy for sensitive groups" to Color(0xFFFFA94D)
    air.index <= 200 -> "Unhealthy" to Color(0xFFFF6B6B)
    air.index <= 300 -> "Very unhealthy" to Color(0xFFDA77F2)
    else -> "Hazardous" to Color(0xFFC92A2A)
}

private fun compass(degrees: Int): String =
    listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((degrees + 22.5) / 45).toInt() % 8]

/** The sun's path from sunrise to sunset, with the sun where it is now ([progress] 0–1; below 0 when it is down). */
@Composable
private fun SunArc(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val r = minOf(size.width / 2 - 8f, size.height - 8f)
        val centre = Offset(size.width / 2, size.height - 4f)
        drawArc(
            GlassTrack, 180f, 180f, false, Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r),
            style = Stroke(3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
        )
        drawLine(GlassTrack, Offset(0f, centre.y), Offset(size.width, centre.y), 2f)
        if (progress in 0f..1f) {
            drawArc(Color(0xFFFFD43B), 180f, 180f * progress, false, Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), style = Stroke(3f, cap = StrokeCap.Round))
            val a = PI * (1 - progress)
            val sun = Offset(centre.x + (r * cos(a)).toFloat(), centre.y - (r * sin(a)).toFloat())
            drawCircle(Brush.radialGradient(listOf(Color(0x66FFD43B), Color.Transparent), sun, 22f), 22f, sun)
            drawCircle(Color(0xFFFFD43B), 9f, sun)
        }
    }
}

/** A day's low-to-high span along the week's range. */
@Composable
private fun RangeBar(from: Float, to: Float, modifier: Modifier) {
    Canvas(modifier) {
        val h = size.height
        drawRoundRect(GlassTrack, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        val x0 = size.width * from.coerceIn(0f, 1f)
        val x1 = (size.width * to.coerceIn(0f, 1f)).coerceAtLeast(x0 + h)
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF74C0FC), Color(0xFFFFD43B), Color(0xFFFF922B)), startX = 0f, endX = size.width),
            topLeft = Offset(x0, 0f), size = Size(x1 - x0, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
        )
    }
}
