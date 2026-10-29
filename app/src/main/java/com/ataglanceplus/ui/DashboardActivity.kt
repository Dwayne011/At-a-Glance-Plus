package com.ataglanceplus.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ataglanceplus.Prefs
import com.ataglanceplus.complications.HealthMetric
import com.ataglanceplus.complications.HealthMetrics
import com.ataglanceplus.complications.HealthSource
import com.ataglanceplus.complications.MetricShape
import com.ataglanceplus.complications.Reading
import com.ataglanceplus.complications.SimpleSources
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small dashboard for a chip's data, opened by tapping the chip: steps (today by hour and the
 * last 7 days), sleep (last night's stages and the last 7 nights) or screen time (today's apps
 * and the last 7 days). Each links on to the app that keeps the data.
 */
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val kind = intent.getStringExtra(EXTRA_KIND)
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (kind) {
                            KIND_STEPS -> StepsDashboard()
                            KIND_SLEEP -> SleepDashboard()
                            KIND_SCREEN -> ScreenDashboard()
                            else -> when {
                                kind?.startsWith(KIND_STOCK_PREFIX) == true -> StockDashboard(kind.removePrefix(KIND_STOCK_PREFIX))
                                kind?.startsWith(KIND_GAME_PREFIX) == true -> GameDashboard(kind.removePrefix(KIND_GAME_PREFIX))
                                kind?.startsWith(KIND_RACE_PREFIX) == true -> RaceDashboard(kind.removePrefix(KIND_RACE_PREFIX))
                                else -> HealthMetric.fromId(kind?.removePrefix(KIND_HEALTH_PREFIX))?.let { MetricDashboard(it) } ?: Text("Nothing to show.")
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        const val KIND_STEPS = "steps"
        const val KIND_SLEEP = "sleep"
        const val KIND_SCREEN = "screen"
        /** Followed by a [com.ataglanceplus.complications.HealthMetric] id: "health:hr". */
        const val KIND_HEALTH_PREFIX = "health:"
        /** Followed by a ticker: "stock:AAPL". */
        const val KIND_STOCK_PREFIX = "stock:"
        /** Followed by an ESPN event id. */
        const val KIND_GAME_PREFIX = "game:"
        const val KIND_RACE_PREFIX = "race:"

        fun intent(context: Context, kind: String): Intent =
            Intent(context, DashboardActivity::class.java).putExtra(EXTRA_KIND, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** What a dashboard has loaded: still loading, the data, or why there is none. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Missing(val why: String) : Load<Nothing>
}

private val numbers: NumberFormat get() = NumberFormat.getIntegerInstance()
private fun day(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun dayAndDate(date: LocalDate) = date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")))

// --- Steps ---

@Composable
private fun StepsDashboard() {
    val context = LocalContext.current
    val load by produceState<Load<Pair<List<Long>, List<Pair<LocalDate, Long>>>>>(Load.Loading) {
        value = withContext(Dispatchers.IO) {
            if (HealthSource.STEPS !in HealthSource.granted(context)) Load.Missing("Allow Health Connect access for steps in the app's Chips tab.")
            else runCatching { Load.Ready(HealthSource.stepsWeek(context)) }.getOrElse { Load.Missing("Health Connect did not answer: ${it.message}") }
        }
    }
    Title("Steps")
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> {
            val (hours, days) = state.data
            val today = days.last().second
            val goal = Prefs.stepGoal(context)
            Hero(numbers.format(today), "steps today")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(progress = { (today.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${(100 * today / goal.coerceAtLeast(1))}% of your ${numbers.format(goal)} goal", style = MaterialTheme.typography.bodyMedium)
            }
            ChartCard("Today by hour") {
                BarChart(
                    values = hours,
                    describe = { i, v -> "%02d:00–%02d:00 · %s steps".format(i, (i + 1) % 24, numbers.format(v)) },
                    axis = listOf(0 to "00:00", 6 to "06:00", 12 to "12:00", 18 to "18:00"),
                    initial = java.time.LocalTime.now().hour,
                )
            }
            ChartCard("Last 7 days") {
                BarChart(
                    values = days.map { it.second },
                    describe = { i, v -> "${dayAndDate(days[i].first)} · ${numbers.format(v)} steps" },
                    axis = days.mapIndexed { i, (date, _) -> i to day(date) },
                    initial = days.lastIndex,
                )
            }
        }
    }
    OpenButton("Open Health Connect", Intent("android.health.connect.action.MANAGE_HEALTH_DATA"))
}

// --- Sleep ---

@Composable
private fun SleepDashboard() {
    val context = LocalContext.current
    val load by produceState<Load<List<Pair<LocalDate, HealthSource.Night?>>>>(Load.Loading) {
        value = withContext(Dispatchers.IO) {
            if (HealthSource.SLEEP !in HealthSource.granted(context)) Load.Missing("Allow Health Connect access for sleep in the app's Chips tab.")
            else runCatching { Load.Ready(HealthSource.sleepWeek(context)) }.getOrElse { Load.Missing("Health Connect did not answer: ${it.message}") }
        }
    }
    Title("Sleep")
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> {
            val week = state.data
            val night = week.last().second
            if (night == null) {
                Text("No sleep recorded last night.")
            } else {
                Hero(SimpleSources.duration(night.minutes), "asleep · ${clock(context, night.start)} – ${clock(context, night.end)}")
                ChartCard("Last night") { Hypnogram(night) }
            }
            ChartCard("Last 7 nights") {
                BarChart(
                    values = week.map { it.second?.minutes ?: 0L },
                    describe = { i, v -> "${dayAndDate(week[i].first)} · " + if (v > 0) SimpleSources.duration(v) else "no sleep recorded" },
                    axis = week.mapIndexed { i, (date, _) -> i to day(date) },
                    initial = week.lastIndex,
                )
            }
        }
    }
    OpenButton("Open Health Connect", Intent("android.health.connect.action.MANAGE_HEALTH_DATA"))
}

private fun clock(context: Context, at: Instant): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at.toEpochMilli()))

/**
 * Sleep stages through the night, one row each (Awake on top, Deep at the bottom), so a stage
 * is told by its row and label as well as its colour. Below: time in each stage.
 */
@Composable
private fun Hypnogram(night: HealthSource.Night) {
    val context = LocalContext.current
    if (night.stages.isEmpty()) {
        Text("Your sleep tracker did not record sleep stages for this night.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val colours = stageColours()
    val rows = HealthSource.StageKind.entries
    val span = (night.end.toEpochMilli() - night.start.toEpochMilli()).coerceAtLeast(1).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Column(modifier = Modifier.width(56.dp)) {
                rows.forEach { kind ->
                    Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text(kind.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val grid = MaterialTheme.colorScheme.outlineVariant
            Canvas(modifier = Modifier.weight(1f).height(24.dp * rows.size)) {
                val rowH = size.height / rows.size
                rows.indices.forEach { r ->
                    drawLine(grid, Offset(0f, (r + 0.5f) * rowH), Offset(size.width, (r + 0.5f) * rowH), strokeWidth = 1f)
                }
                night.stages.forEach { stage ->
                    val r = rows.indexOf(stage.kind)
                    val x0 = (stage.start.toEpochMilli() - night.start.toEpochMilli()) / span * size.width
                    val x1 = (stage.end.toEpochMilli() - night.start.toEpochMilli()) / span * size.width
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        colours.getValue(stage.kind),
                        topLeft = Offset(x0.coerceAtLeast(0f), r * rowH + inset),
                        size = Size((x1 - x0).coerceAtLeast(2f), rowH - 2 * inset),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(start = 56.dp)) {
            Text(clock(context, night.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(clock(context, night.end), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Legend, with each stage's total: the labels carry identity, not colour alone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { kind ->
                val minutes = night.stages.filter { it.kind == kind }.sumOf { (it.end.toEpochMilli() - it.start.toEpochMilli()) / 60_000 }
                if (minutes > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(colours.getValue(kind), CircleShape))
                        Text("  ${kind.label} ${SimpleSources.duration(minutes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Stage colours: the first three categorical slots, which stay apart for every pair and for
 * colour-blind viewers in both modes; awake is neutral grey, being no sleep at all.
 */
@Composable
private fun stageColours(): Map<HealthSource.StageKind, Color> {
    val dark = isSystemInDarkTheme()
    return mapOf(
        HealthSource.StageKind.DEEP to Color(if (dark) 0xFF3987E5 else 0xFF2A78D6),
        HealthSource.StageKind.REM to Color(if (dark) 0xFFD95926 else 0xFFEB6834),
        HealthSource.StageKind.LIGHT to Color(if (dark) 0xFF199E70 else 0xFF1BAF7A),
        HealthSource.StageKind.AWAKE to MaterialTheme.colorScheme.outline,
    )
}

// --- Screen time ---

@Composable
private fun ScreenDashboard() {
    val context = LocalContext.current
    val load by produceState<Load<Pair<List<Pair<String, Long>>, List<Pair<LocalDate, Long>>>>>(Load.Loading) {
        value = withContext(Dispatchers.IO) {
            if (!SimpleSources.hasUsageAccess(context)) Load.Missing("Allow usage access for screen time in the app's Chips tab.")
            else Load.Ready(SimpleSources.screenTimeWeek(context))
        }
    }
    Title("Screen time")
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> {
            val (apps, days) = state.data
            Hero(SimpleSources.duration(days.last().second / 60_000), "on screen today")
            ChartCard("Last 7 days") {
                BarChart(
                    values = days.map { it.second / 60_000 },
                    describe = { i, v -> "${dayAndDate(days[i].first)} · ${SimpleSources.duration(v)}" },
                    axis = days.mapIndexed { i, (date, _) -> i to day(date) },
                    initial = days.lastIndex,
                )
            }
            val shown = apps.filter { it.second >= 60_000 }.take(10)
            if (shown.isNotEmpty()) {
                ChartCard("Apps today") {
                    val most = shown.first().second.toFloat()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shown.forEach { (pkg, ms) -> AppRow(pkg, ms, ms / most) }
                    }
                }
            }
        }
    }
    val wellbeing = com.ataglanceplus.cards.wellbeingDashboard(context)
        ?: context.packageManager.getLaunchIntentForPackage("com.google.android.apps.wellbeing")
    if (wellbeing != null) OpenButton("Open Digital Wellbeing", wellbeing)
}

@Composable
private fun AppRow(pkg: String, ms: Long, share: Float) {
    val context = LocalContext.current
    val icon = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(32.dp)) else Spacer(Modifier.size(32.dp))
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(SimpleSources.appName(context, pkg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(SimpleSources.duration(ms / 60_000), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
    }
}

// --- Health metrics ---

/** What a metric's dashboard shows, by its shape. */
private sealed interface MetricData {
    data class Totals(val today: Double, val hours: List<Double>, val days: List<Pair<LocalDate, Double>>) : MetricData
    data class Readings(val readings: List<Reading>) : MetricData
}

/**
 * A health metric: a daily total (today by hour and the last 7 days, like steps); readings
 * (the latest, today's line and each day's range over the last week, or each reading's range
 * for blood pressure); or occasional measurements (the latest and the last 30 days).
 */
@Composable
private fun MetricDashboard(metric: HealthMetric) {
    val context = LocalContext.current
    val load by produceState<Load<MetricData>>(Load.Loading, metric) {
        value = withContext(Dispatchers.IO) {
            if (metric.permission !in HealthSource.granted(context)) {
                Load.Missing("Allow Health Connect access for ${metric.label.lowercase()} in the app's Chips tab.")
            } else runCatching {
                Load.Ready(
                    when (metric.shape) {
                        MetricShape.TOTAL -> MetricData.Totals(
                            HealthMetrics.totalToday(context, metric),
                            HealthMetrics.totalsByHour(context, metric),
                            HealthMetrics.totalsByDay(context, metric),
                        )
                        MetricShape.READINGS -> MetricData.Readings(HealthMetrics.readings(context, metric, Instant.now().minus(java.time.Duration.ofDays(7))))
                        MetricShape.OCCASIONAL -> MetricData.Readings(HealthMetrics.readings(context, metric, Instant.now().minus(java.time.Duration.ofDays(30))))
                    }
                )
            }.getOrElse { Load.Missing("Health Connect did not answer: ${it.message}") }
        }
    }
    Title(metric.label)
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> when (val data = state.data) {
            is MetricData.Totals -> {
                Hero(HealthMetrics.full(metric, data.today), "today")
                ChartCard("Today by hour") {
                    BarChart(
                        values = data.hours.map { (it * 100).toLong() },
                        describe = { i, _ -> "%02d:00–%02d:00 · %s".format(i, (i + 1) % 24, HealthMetrics.full(metric, data.hours[i])) },
                        axis = listOf(0 to "00:00", 6 to "06:00", 12 to "12:00", 18 to "18:00"),
                        initial = java.time.LocalTime.now().hour,
                    )
                }
                ChartCard("Last 7 days") {
                    BarChart(
                        values = data.days.map { (it.second * 100).toLong() },
                        describe = { i, _ -> "${dayAndDate(data.days[i].first)} · ${HealthMetrics.full(metric, data.days[i].second)}" },
                        axis = data.days.mapIndexed { i, (date, _) -> i to day(date) },
                        initial = data.days.lastIndex,
                    )
                }
            }
            is MetricData.Readings -> ReadingsDashboard(metric, data.readings)
        }
    }
    OpenButton("Open Health Connect", Intent("android.health.connect.action.MANAGE_HEALTH_DATA"))
}

@Composable
private fun ReadingsDashboard(metric: HealthMetric, readings: List<Reading>) {
    val context = LocalContext.current
    val latest = readings.lastOrNull()
    if (latest == null) {
        Text("No ${metric.label.lowercase()} recorded in the last ${if (metric.shape == MetricShape.OCCASIONAL) "30" else "7"} days.")
        return
    }
    Hero(HealthMetrics.full(metric, latest.value, latest.second), HealthMetrics.ago(context, latest.time))
    fun at(time: Instant) = "${dayAndDate(time.atZone(ZoneId.systemDefault()).toLocalDate())} ${clock(context, time)}"
    when {
        metric == HealthMetric.BLOOD_PRESSURE -> {
            val recent = readings.takeLast(14)
            ChartCard("Recent readings") {
                Text("Each bar runs from diastolic (bottom) to systolic (top).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RangeChart(
                    ranges = recent.map { (it.second ?: it.value) to it.value },
                    describe = { i -> "${at(recent[i].time)} · ${HealthMetrics.full(metric, recent[i].value, recent[i].second)}" },
                    axis = emptyList(),
                    format = { HealthMetrics.short(metric, it) },
                )
            }
        }
        metric.shape == MetricShape.READINGS -> {
            val today = LocalDate.now()
            val todays = readings.filter { it.time.atZone(ZoneId.systemDefault()).toLocalDate() == today }
            if (todays.size >= 2) {
                ChartCard("Today") {
                    LineChart(
                        points = todays,
                        describe = { r -> "${clock(context, r.time)} · ${HealthMetrics.full(metric, r.value)}" },
                        format = { HealthMetrics.short(metric, it) },
                        markers = todays.size <= 48,
                    )
                }
            }
            // Each day's lowest to highest, with the average when tapped.
            val week = (6 downTo 0).map { back -> today.minusDays(back.toLong()) }
            val byDay = week.map { day -> readings.filter { it.time.atZone(ZoneId.systemDefault()).toLocalDate() == day } }
            ChartCard("Last 7 days") {
                RangeChart(
                    ranges = byDay.map { r -> if (r.isEmpty()) null else r.minOf { it.value } to r.maxOf { it.value } },
                    describe = { i ->
                        val r = byDay[i]
                        if (r.isEmpty()) "${dayAndDate(week[i])} · no readings"
                        else "${dayAndDate(week[i])} · ${HealthMetrics.short(metric, r.minOf { it.value })}–${HealthMetrics.full(metric, r.maxOf { it.value })}, average ${HealthMetrics.short(metric, r.map { it.value }.average())}"
                    },
                    axis = week.mapIndexed { i, d -> i to day(d) },
                    format = { HealthMetrics.short(metric, it) },
                )
            }
        }
        else -> {
            ChartCard("Last 30 days") {
                LineChart(
                    points = readings,
                    describe = { r -> "${at(r.time)} · ${HealthMetrics.full(metric, r.value)}" },
                    format = { HealthMetrics.short(metric, it) },
                    markers = readings.size <= 60,
                )
                if (readings.size >= 2) {
                    val change = latest.value - readings.first().value
                    Text(
                        "Since ${dayAndDate(readings.first().time.atZone(ZoneId.systemDefault()).toLocalDate())}: " +
                            (if (change >= 0) "+" else "−") + HealthMetrics.full(metric, kotlin.math.abs(change)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Readings over time as one 2dp line, scaled to their own low and high (marked at the left), with
 * markers when there are few. A tap picks the nearest reading and describes it above the chart.
 */
@Composable
private fun LineChart(points: List<Reading>, describe: (Reading) -> String, format: (Double) -> String, markers: Boolean) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val grid = MaterialTheme.colorScheme.outlineVariant
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }.let { if (it == low) low + 1 else it }
    val t0 = points.first().time.toEpochMilli()
    val span = (points.last().time.toEpochMilli() - t0).coerceAtLeast(1).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(describe(points[selected]), style = MaterialTheme.typography.bodyMedium)
        Row {
            Column(modifier = Modifier.height(120.dp).width(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(format(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(format(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
                    .pointerInput(points) {
                        detectTapGestures { at ->
                            val time = t0 + at.x / size.width * span
                            selected = points.indices.minByOrNull { kotlin.math.abs(points[it].time.toEpochMilli() - time) } ?: selected
                        }
                    }
            ) {
                val pad = 6.dp.toPx()
                fun x(r: Reading) = if (points.size == 1) size.width / 2 else (r.time.toEpochMilli() - t0) / span * size.width
                fun y(r: Reading) = pad + (1 - ((r.value - low) / (high - low)).toFloat()) * (size.height - 2 * pad)
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                val path = Path()
                points.forEachIndexed { i, r -> if (i == 0) path.moveTo(x(r), y(r)) else path.lineTo(x(r), y(r)) }
                drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                if (markers) points.forEach { r -> drawCircle(line, 4.dp.toPx(), Offset(x(r), y(r))) }
                // The chosen reading: a ringed marker and a faint guide.
                val chosen = points[selected]
                drawLine(grid, Offset(x(chosen), 0f), Offset(x(chosen), size.height), strokeWidth = 1.dp.toPx())
                drawCircle(surface, 7.dp.toPx(), Offset(x(chosen), y(chosen)))
                drawCircle(line, 5.dp.toPx(), Offset(x(chosen), y(chosen)))
            }
        }
    }
}

/**
 * Floating bars from a low to a high value (null for none), scaled to all of them together,
 * with the overall low and high marked at the left. A tap describes that bar above the chart.
 */
@Composable
private fun RangeChart(ranges: List<Pair<Double, Double>?>, describe: (Int) -> String, axis: List<Pair<Int, String>>, format: (Double) -> String) {
    if (ranges.isEmpty()) return
    var selected by remember(ranges) { mutableIntStateOf(ranges.indexOfLast { it != null }.coerceAtLeast(0)) }
    val bar = MaterialTheme.colorScheme.primary
    val dimmed = bar.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val present = ranges.filterNotNull()
    val low = present.minOfOrNull { it.first } ?: 0.0
    val high = (present.maxOfOrNull { it.second } ?: 1.0).let { if (it == low) low + 1 else it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(describe(selected), style = MaterialTheme.typography.bodyMedium)
        Row {
            Column(modifier = Modifier.height(120.dp).width(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(format(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(format(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .pointerInput(ranges) {
                            detectTapGestures { at -> selected = (at.x / size.width * ranges.size).toInt().coerceIn(0, ranges.lastIndex) }
                        }
                ) {
                    val slot = size.width / ranges.size
                    val gap = if (ranges.size > 12) 2.dp.toPx() else 8.dp.toPx()
                    val pad = 4.dp.toPx()
                    fun y(v: Double) = pad + (1 - ((v - low) / (high - low)).toFloat()) * (size.height - 2 * pad)
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                    ranges.forEachIndexed { i, range ->
                        range ?: return@forEachIndexed
                        val top = y(range.second)
                        val height = (y(range.first) - top).coerceAtLeast(6.dp.toPx())
                        drawRoundRect(
                            if (i == selected) bar else dimmed,
                            topLeft = Offset(i * slot + gap / 2, top),
                            size = Size(slot - gap, height),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                if (axis.isNotEmpty()) AxisLabels(axis, ranges.size)
            }
        }
    }
}

// --- Finance ---

/** Up and down: the reserved good and critical colours, always with an arrow and a sign. */
private val UP = Color(0xFF0CA30C)
private val DOWN = Color(0xFFD03B3B)

/**
 * A stock: the user's stocks to switch between, the price and its change over the chosen range,
 * the price over that range, and the day's figures. Links on to Google Finance.
 */
@Composable
private fun StockDashboard(initial: String) {
    val context = LocalContext.current
    val tickers = remember { Prefs.financeTickers(context).ifEmpty { listOf(initial) } }
    var symbol by remember { mutableStateOf(initial) }
    var range by remember { mutableStateOf(com.ataglanceplus.live.FinanceSource.Range.DAY) }
    val load by produceState<Load<com.ataglanceplus.live.FinanceSource.History>>(Load.Loading, symbol, range) {
        value = Load.Loading
        value = withContext(Dispatchers.IO) {
            com.ataglanceplus.live.FinanceSource.history(symbol, range)?.let { Load.Ready(it) }
                ?: Load.Missing("Yahoo Finance did not answer. Try again in a moment.")
        }
    }
    Title("Stocks")
    if (tickers.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tickers.forEach { t -> FilterChip(selected = t == symbol, onClick = { symbol = t }, label = { Text(t) }) }
        }
    }
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> {
            val h = state.data
            val q = h.quote
            // The screen's locale, read so prices redraw if the phone's language changes.
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            fun money(v: Double) = String.format(locale, "%,.2f", v)
            Column {
                Text(q.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${q.symbol} · ${h.exchangeName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val change = h.rangeChange
            val base = q.price - change
            val percent = if (base != 0.0) 100 * change / base else 0.0
            Column {
                Text("${money(q.price)} ${q.currency}", style = MaterialTheme.typography.displaySmall)
                Text(
                    (if (change >= 0) "▲ +" else "▼ −") + money(kotlin.math.abs(change)) +
                        String.format(locale, " (%+.2f%%) ", percent) + if (range == com.ataglanceplus.live.FinanceSource.Range.DAY) "today" else "over ${range.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (change >= 0) UP else DOWN,
                )
            }
            ChartCard("Price") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ataglanceplus.live.FinanceSource.Range.entries.forEach { r ->
                        FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) })
                    }
                }
                val points = h.points.map { (t, v) -> Reading(Instant.ofEpochMilli(t), v) }
                if (points.size >= 2) {
                    LineChart(
                        points = points,
                        describe = { r ->
                            val at = r.time.atZone(ZoneId.systemDefault())
                            val whenText = if (range.ordinal <= com.ataglanceplus.live.FinanceSource.Range.WEEK.ordinal) "${dayAndDate(at.toLocalDate())} ${clock(context, r.time)}" else dayAndDate(at.toLocalDate())
                            "$whenText · ${money(r.value)}"
                        },
                        format = { money(it) },
                        markers = false,
                    )
                } else {
                    Text("No prices for this range yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            ChartCard("Today") {
                Stat("Previous close", money(q.previousClose))
                if (h.dayLow != null && h.dayHigh != null) Stat("Day's range", "${money(h.dayLow)} – ${money(h.dayHigh)}")
                if (h.yearLow != null && h.yearHigh != null) Stat("52-week range", "${money(h.yearLow)} – ${money(h.yearHigh)}")
                h.volume?.let { Stat("Volume", numbers.format(it)) }
            }
            OpenButton("Open in Google Finance", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.ataglanceplus.live.FinanceSource.googleFinanceUrl(q))))
        }
    }
    Text("Prices from Yahoo Finance's free feed; some exchanges are 15 minutes behind.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stat(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Sports ---

/** The followed team (or series) a game or session belongs to, and its games or sessions. */
private suspend fun ensureSports(context: Context) {
    if (com.ataglanceplus.live.SportsSource.games.value.isEmpty() && com.ataglanceplus.live.SportsSource.races.value.isEmpty()) {
        runCatching { com.ataglanceplus.live.SportsSource.refreshSchedules(context) }
    }
}

/**
 * A game: the scoreboard (live, final or kick-off); each side's line-up; then, unless the user
 * hides this team's scores, the match's events (goals with scorer and assist, cards, subs), team
 * stats, each team's leaders and the box score; then the team's next games and results. Hidden
 * scores can be revealed for this visit. While the game is on, the detail refreshes every 30s.
 */
@Composable
private fun GameDashboard(eventId: String) {
    val context = LocalContext.current
    val load by produceState<Load<Pair<com.ataglanceplus.live.Followed?, List<com.ataglanceplus.live.Game>>>>(Load.Loading, eventId) {
        withContext(Dispatchers.IO) { ensureSports(context) }
        val all = com.ataglanceplus.live.SportsSource.games.value
        val key = all.entries.firstOrNull { (_, games) -> games.any { it.eventId == eventId } }?.key
        value = if (key == null) Load.Missing("This game is no longer in your teams' fixtures.")
        else Load.Ready(Prefs.sportsFollowed(context).firstOrNull { it.key == key } to all.getValue(key))
    }
    var revealed by remember { mutableStateOf(false) }
    when (val state = load) {
        Load.Loading -> { Title("Game"); Text("Loading…") }
        is Load.Missing -> { Title("Game"); Text(state.why) }
        is Load.Ready -> {
            val (team, games) = state.data
            val game = games.first { it.eventId == eventId }
            val hidden = !revealed && com.ataglanceplus.live.SportsSource.scoresHidden(context, eventId)
            val detail by produceState<com.ataglanceplus.live.GameDetail?>(null, game.eventId) {
                while (true) {
                    value = withContext(Dispatchers.IO) { com.ataglanceplus.live.SportsSource.detail(game) } ?: value
                    if (game.state != "in") break
                    kotlinx.coroutines.delay(30_000)
                }
            }
            Title(team?.name ?: game.leagueName)
            Scoreboard(game, hidden)
            if (hidden) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Scores are hidden for this team. Line-ups and fixtures still show.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { revealed = true }, modifier = Modifier.padding(start = 12.dp)) { Text("Reveal") }
                    }
                }
            }
            detail?.let { d -> GameDetailSections(d, hidden) }
            val upcoming = games.filter { it.state == "pre" && it.eventId != eventId }.sortedBy { it.start }.take(5)
            if (upcoming.isNotEmpty()) {
                ChartCard("Next games") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { upcoming.forEach { FixtureRow(it, team, hidden) } }
                }
            }
            val results = games.filter { it.state == "post" && it.eventId != eventId }.sortedByDescending { it.start }.take(5)
            if (results.isNotEmpty()) {
                ChartCard("Results") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { results.forEach { FixtureRow(it, team, hidden) } }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenButton("Open in Google", com.ataglanceplus.cards.LiveCards.googleSearch(context, "${game.home.name} vs ${game.away.name}"))
                game.link?.let { OpenButton("ESPN", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
            }
        }
    }
    Text("Scores from ESPN's free public feed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The game's detail, as far as the sport has it. With scores hidden, only the line-ups. */
@Composable
private fun GameDetailSections(d: com.ataglanceplus.live.GameDetail, hidden: Boolean) {
    if (d.lineups.isNotEmpty()) {
        ChartCard("Line-ups") {
            d.lineups.forEachIndexed { i, lineup ->
                if (i > 0) androidx.compose.material3.HorizontalDivider()
                Text(listOfNotNull(lineup.team, if (i == 0) "Home" else "Away", lineup.formation).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lineup.starters.forEach { PlayerRow(it) }
                }
                if (lineup.subs.isNotEmpty()) {
                    Text("Substitutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { lineup.subs.forEach { PlayerRow(it) } }
                }
            }
        }
    }
    if (hidden) return
    if (d.events.isNotEmpty()) {
        ChartCard("Match events") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.events.forEach { e ->
                    Row {
                        Text(e.clock, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                        Text(
                            when (e.kind) {
                                com.ataglanceplus.live.GameDetail.Kind.GOAL -> "⚽ "
                                com.ataglanceplus.live.GameDetail.Kind.YELLOW -> "🟨 "
                                com.ataglanceplus.live.GameDetail.Kind.RED -> "🟥 "
                                com.ataglanceplus.live.GameDetail.Kind.SUB -> "⇄ "
                                else -> ""
                            } + e.text + (e.team?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (d.scoring.isNotEmpty()) {
        ChartCard("Scoring") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.scoring.forEach { e ->
                    Row {
                        Text(e.clock, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                        Text(listOfNotNull(e.team, e.text).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (d.leaders.isNotEmpty()) {
        ChartCard("Top players") {
            d.leaders.forEach { (team, rows) ->
                Text(team, style = MaterialTheme.typography.titleSmall)
                rows.forEach { (what, who, value) -> Stat(what, "$who · $value") }
            }
        }
    }
    if (d.teamStats.isNotEmpty()) {
        ChartCard("Team stats") {
            d.teamStats.forEach { (label, home, away) ->
                Row {
                    Text(home, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(away, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
    d.boxScores.forEach { box -> BoxScoreTable(box) }
}

@Composable
private fun PlayerRow(p: com.ataglanceplus.live.GameDetail.Player) {
    Row {
        Text(p.number.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Text(p.name + when {
            p.subbedIn -> "  ↑"
            p.subbedOut -> "  ↓"
            else -> ""
        }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(p.position.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One team's box score: a fixed name column and the sport's stat columns, scrolling sideways. */
@Composable
private fun BoxScoreTable(box: com.ataglanceplus.live.GameDetail.BoxScore) {
    ChartCard("Box score · ${box.team}") {
        Row {
            Column(modifier = Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Player", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                box.players.forEach { p ->
                    Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (p.starter) androidx.compose.ui.text.font.FontWeight.Medium else null)
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                box.labels.forEachIndexed { c, label ->
                    Column(modifier = Modifier.width(48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        box.players.forEach { p -> Text(p.stats.getOrElse(c) { "" }, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(game: com.ataglanceplus.live.Game, hidden: Boolean = false) {
    val context = LocalContext.current
    val scored = game.state != "pre" && !hidden
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(game.leagueName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamBlock(game.home, "Home", Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        scored -> Text("${game.home.score ?: "0"} – ${game.away.score ?: "0"}", style = MaterialTheme.typography.displaySmall)
                        game.state == "pre" -> Text(clock(context, Instant.ofEpochMilli(game.start)), style = MaterialTheme.typography.headlineMedium)
                        else -> Text("– : –", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when (game.state) {
                            "in" -> "LIVE · ${game.status}"
                            "post" -> game.status
                            else -> dayAndDate(Instant.ofEpochMilli(game.start).atZone(ZoneId.systemDefault()).toLocalDate())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (game.state == "in") DOWN else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TeamBlock(game.away, "Away", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TeamBlock(side: com.ataglanceplus.live.Side, place: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val logo = remember(side.logoUrl) { com.ataglanceplus.live.SportsSource.logo(side.logoUrl)?.asImageBitmap() }
        if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(48.dp)) else Spacer(Modifier.size(48.dp))
        Text(side.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(place, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A fixture or result from the followed team's side: when, home or away, the opponent, and the score with W, D or L. */
@Composable
private fun FixtureRow(game: com.ataglanceplus.live.Game, team: com.ataglanceplus.live.Followed?, hidden: Boolean = false) {
    val context = LocalContext.current
    val home = team?.teamId == null || game.home.id == team.teamId
    val us = if (home) game.home else game.away
    val them = if (home) game.away else game.home
    val date = Instant.ofEpochMilli(game.start)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val logo = remember(them.logoUrl) { com.ataglanceplus.live.SportsSource.logo(them.logoUrl)?.asImageBitmap() }
        if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(28.dp)) else Spacer(Modifier.size(28.dp))
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text((if (home) "vs " else "at ") + them.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                dayAndDate(date.atZone(ZoneId.systemDefault()).toLocalDate()) + if (game.state == "pre") " · ${clock(context, date)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (game.state == "post" && hidden) {
            Text("Result hidden", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (game.state == "post") {
            val outcome = when {
                us.winner -> "W"
                them.winner -> "L"
                else -> "D"
            }
            Text("$outcome ${us.score ?: "?"}–${them.score ?: "?"}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A race weekend: each session's time, and its leaders once it has started. */
@Composable
private fun RaceDashboard(eventId: String) {
    val context = LocalContext.current
    val load by produceState<Load<List<com.ataglanceplus.live.RaceSession>>>(Load.Loading, eventId) {
        withContext(Dispatchers.IO) { ensureSports(context) }
        val sessions = com.ataglanceplus.live.SportsSource.races.value.values.flatten().filter { it.eventId == eventId }.sortedBy { it.start }
        value = if (sessions.isEmpty()) Load.Missing("This race weekend is no longer in the calendar.") else Load.Ready(sessions)
    }
    when (val state = load) {
        Load.Loading -> { Title("Formula 1"); Text("Loading…") }
        is Load.Missing -> { Title("Formula 1"); Text(state.why) }
        is Load.Ready -> {
            val sessions = state.data
            Title(sessions.first().eventName)
            val first = sessions.first()
            com.ataglanceplus.cards.CircuitIcons.find(first.circuitCity, first.circuitName, aod = false)?.let { outline ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(outline), contentDescription = first.circuitName,
                        modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        first.circuitName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        first.circuitCity?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    sessions.forEach { s ->
                        val start = Instant.ofEpochMilli(s.start)
                        Column {
                            Row {
                                Text(s.session, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(
                                    when (s.state) {
                                        "in" -> "LIVE"
                                        "post" -> "Finished"
                                        else -> "${dayAndDate(start.atZone(ZoneId.systemDefault()).toLocalDate())} ${clock(context, start)}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (s.state == "in") DOWN else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (s.top.isNotEmpty() && s.state != "pre" && !com.ataglanceplus.live.SportsSource.scoresHidden(context, eventId)) {
                                Text(s.top.mapIndexed { i, n -> "${i + 1} $n" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenButton("Open in Google", com.ataglanceplus.cards.LiveCards.googleSearch(context, "F1 ${sessions.first().eventName}"))
                sessions.first().link?.let { OpenButton("ESPN", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
            }
        }
    }
}

// --- Shared pieces ---

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
}

/** The headline number and what it is. */
@Composable
private fun Hero(value: String, caption: String) {
    Column {
        Text(value, style = MaterialTheme.typography.displayMedium)
        Text(caption, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun OpenButton(label: String, intent: Intent) {
    val context = LocalContext.current
    OutlinedButton(onClick = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(label) }
}

/**
 * One series of bars from a shared baseline, 4dp rounded at the top only, with a gap between
 * bars. A tap on a bar (or anywhere in its column) shows its exact value above the chart; until
 * then the [initial] bar (now, or today) is the one described. A single series, so no legend:
 * the card's title names it.
 */
@Composable
private fun BarChart(values: List<Long>, describe: (Int, Long) -> String, axis: List<Pair<Int, String>>, initial: Int) {
    var selected by remember(values) { mutableIntStateOf(initial.coerceIn(0, (values.size - 1).coerceAtLeast(0))) }
    val bar = MaterialTheme.colorScheme.primary
    val dimmed = bar.copy(alpha = 0.45f)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(describe(selected, values.getOrElse(selected) { 0L }), style = MaterialTheme.typography.bodyMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(values) {
                    detectTapGestures { at -> selected = (at.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex) }
                }
        ) {
            val slot = size.width / values.size
            val gap = if (values.size > 12) 2.dp.toPx() else 8.dp.toPx()
            val radius = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v / max * size.height).coerceAtLeast(radius)
                val left = i * slot + gap / 2
                val rect = RoundRect(
                    left = left, top = size.height - h, right = left + slot - gap, bottom = size.height,
                    topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                    bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                )
                drawPath(Path().apply { addRoundRect(rect) }, if (i == selected) bar else dimmed)
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        AxisLabels(axis, values.size)
    }
}

/** Axis labels centred under their bars (kept inside the chart at the ends). */
@Composable
private fun AxisLabels(axis: List<Pair<Int, String>>, count: Int) {
    Layout(
        content = {
            axis.forEach { (_, label) ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val slot = width.toFloat() / count
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { k, placeable ->
                val centre = (axis[k].first + 0.5f) * slot
                placeable.place((centre - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
