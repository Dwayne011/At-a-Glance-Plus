package com.ataglanceplus.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A small dashboard for a chip's data, opened by tapping the chip: steps (today by hour and the
 * last 7 days), sleep (last night's stages and the last 7 nights) or screen time (today's apps
 * and the last 7 days). Each links on to the app that keeps the data.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val kind = intent.getStringExtra(EXTRA_KIND)
        val refresh = Refresh()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var refreshing by remember { mutableStateOf(false) }
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                  CompositionLocalProvider(LocalRefresh provides refresh) {
                    // Pull down: refresh what the home screen shows for this dashboard (its card or
                    // chip), then load the dashboard again, so both agree.
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            refreshing = true
                            scope.launch {
                                runCatching { refreshHome(context, kind) }
                                    .onFailure { com.ataglanceplus.CrashLog.record(context, "Refreshing from a dashboard", it) }
                                refresh.generation++
                                val target = refresh.generation
                                withTimeoutOrNull(REFRESH_TIMEOUT_MS) { snapshotFlow { refresh.loaded }.first { it >= target } }
                                refreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    ) {
                    // Sports and F1 get a full-screen, swipeable layout of their own; the rest
                    // stack vertically in one scroll.
                    when {
                        kind?.startsWith(KIND_GAME_PREFIX) == true -> GameMatchDashboard(kind.removePrefix(KIND_GAME_PREFIX))
                        kind?.startsWith(KIND_RACE_PREFIX) == true -> F1EventDashboard(kind.removePrefix(KIND_RACE_PREFIX))
                        else -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (kind) {
                                KIND_STEPS -> StepsDashboard()
                                KIND_SLEEP -> SleepDashboard()
                                KIND_SCREEN -> ScreenDashboard()
                                KIND_ETORO -> EtoroDashboard()
                                else -> when {
                                    kind?.startsWith(KIND_STOCK_PREFIX) == true -> StockDashboard(kind.removePrefix(KIND_STOCK_PREFIX))
                                    else -> HealthMetric.fromId(kind?.removePrefix(KIND_HEALTH_PREFIX))?.let { MetricDashboard(it) } ?: Text("Nothing to show.")
                                }
                            }
                        }
                    }
                    }
                  }
                }
            }
        }
    }

    /**
     * The home screen's side of a refresh: prices for a stock (the finance card), schedules and
     * scores for a game or race (the sports cards), or every chip for the rest. Prices and sports
     * finish before the dashboard reloads, since it reads them; chips refresh in the service.
     */
    private suspend fun refreshHome(context: Context, kind: String?) {
        when {
            kind == null -> Unit
            kind == KIND_ETORO -> com.ataglanceplus.live.EtoroSource.refresh(context)
            kind.startsWith(KIND_STOCK_PREFIX) -> com.ataglanceplus.live.FinanceSource.refresh(context)
            kind.startsWith(KIND_GAME_PREFIX) || kind.startsWith(KIND_RACE_PREFIX) -> {
                com.ataglanceplus.live.SportsSource.refreshSchedules(context)
                com.ataglanceplus.live.SportsSource.refreshLive(context)
            }
            else -> com.ataglanceplus.complications.ComplicationEngine.requestRefresh()
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        /** The longest the refresh spinner waits for a dashboard to load again. */
        private const val REFRESH_TIMEOUT_MS = 20_000L
        const val KIND_STEPS = "steps"
        const val KIND_SLEEP = "sleep"
        const val KIND_SCREEN = "screen"
        const val KIND_ETORO = "etoro"
        /** Followed by a [com.ataglanceplus.complications.HealthMetric] id: "health:hr". */
        const val KIND_HEALTH_PREFIX = "health:"
        /** Followed by a ticker: "stock:AAPL". */
        const val KIND_STOCK_PREFIX = "stock:"
        /** Followed by an ESPN event id. */
        const val KIND_GAME_PREFIX = "game:"
        const val KIND_RACE_PREFIX = "race:"

        /**
         * Opens [kind]'s dashboard fresh. Android matches a running task by its intent without the
         * extras, so the kind also goes in the data (each dashboard's intent differs), and any
         * dashboard left open is cleared rather than brought back in place of the one tapped.
         */
        fun intent(context: Context, kind: String): Intent =
            Intent(context, DashboardActivity::class.java).putExtra(EXTRA_KIND, kind)
                .setData(android.net.Uri.fromParts("dashboard", kind, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/**
 * Pull to refresh: each pull raises [generation]; a dashboard's loader is keyed on it, loads again
 * (keeping what it shows until the new data is in), and reports the generation it finished in
 * [loaded], which ends the spinner.
 */
private class Refresh {
    var generation by mutableIntStateOf(0)
    var loaded by mutableIntStateOf(0)
}

private val LocalRefresh = staticCompositionLocalOf { Refresh() }

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
    val refresh = LocalRefresh.current
    val load by produceState<Load<Pair<List<Long>, List<Pair<LocalDate, Long>>>>>(Load.Loading, refresh.generation) {
        value = withContext(Dispatchers.IO) {
            if (HealthSource.STEPS !in HealthSource.granted(context)) Load.Missing("Allow Health Connect access for steps in the app's Chips tab.")
            else runCatching { Load.Ready(HealthSource.stepsWeek(context)) }.getOrElse { Load.Missing("Health Connect did not answer: ${it.message}") }
        }
        refresh.loaded = refresh.generation
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
    val refresh = LocalRefresh.current
    val load by produceState<Load<List<Pair<LocalDate, HealthSource.Night?>>>>(Load.Loading, refresh.generation) {
        value = withContext(Dispatchers.IO) {
            if (HealthSource.SLEEP !in HealthSource.granted(context)) Load.Missing("Allow Health Connect access for sleep in the app's Chips tab.")
            else runCatching { Load.Ready(HealthSource.sleepWeek(context)) }.getOrElse { Load.Missing("Health Connect did not answer: ${it.message}") }
        }
        refresh.loaded = refresh.generation
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
    val refresh = LocalRefresh.current
    val load by produceState<Load<Pair<List<Pair<String, Long>>, List<Pair<LocalDate, Long>>>>>(Load.Loading, refresh.generation) {
        value = withContext(Dispatchers.IO) {
            if (!SimpleSources.hasUsageAccess(context)) Load.Missing("Allow usage access for screen time in the app's Chips tab.")
            else Load.Ready(SimpleSources.screenTimeWeek(context))
        }
        refresh.loaded = refresh.generation
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
    val refresh = LocalRefresh.current
    val load by produceState<Load<MetricData>>(Load.Loading, metric, refresh.generation) {
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
        refresh.loaded = refresh.generation
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
 * its candles and VWAP over that range, and the day's figures. Links on to Google Finance.
 */
@Composable
private fun StockDashboard(initial: String) {
    val context = LocalContext.current
    val tickers = remember { Prefs.financeTickers(context).ifEmpty { listOf(initial) } }
    var symbol by remember { mutableStateOf(initial) }
    var range by remember { mutableStateOf(com.ataglanceplus.live.FinanceSource.Range.DAY) }
    val refresh = LocalRefresh.current
    var shown by remember { mutableStateOf<Pair<String, Any>?>(null) }
    var vwapSettings by remember { mutableStateOf(Prefs.financeVwap(context)) }
    var editingVwap by remember { mutableStateOf(false) }
    val load by produceState<Load<com.ataglanceplus.live.FinanceSource.History>>(Load.Loading, symbol, range, vwapSettings, refresh.generation) {
        // A new stock or range starts from "Loading…"; a refresh keeps the chart until the new one is in.
        if (shown != (symbol to range)) value = Load.Loading
        shown = symbol to range
        value = withContext(Dispatchers.IO) {
            com.ataglanceplus.live.FinanceSource.history(symbol, range, vwapSettings)?.let { Load.Ready(it) }
                ?: Load.Missing("Yahoo Finance did not answer. Try again in a moment.")
        }
        refresh.loaded = refresh.generation
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
            // Outside regular hours the latest price leads, with its move from the close (as eToro
            // shows it); the regular session's figures follow.
            val extended = q.extended
            Column {
                Text("${money(extended?.price ?: q.price)} ${q.currency}", style = MaterialTheme.typography.displaySmall)
                if (extended != null) {
                    Text(
                        (if (extended.change >= 0) "▲ +" else "▼ −") + money(kotlin.math.abs(extended.change)) +
                            String.format(locale, " (%+.2f%%) ", extended.changePercent) + extended.session.label.lowercase(locale),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (extended.change >= 0) UP else DOWN,
                    )
                }
                Text(
                    (if (extended != null) "At the close ${money(q.price)}: " else "") +
                        (if (change >= 0) "▲ +" else "▼ −") + money(kotlin.math.abs(change)) +
                        String.format(locale, " (%+.2f%%) ", percent) + if (range == com.ataglanceplus.live.FinanceSource.Range.DAY) "today" else "over ${range.label}",
                    style = if (extended != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = if (change >= 0) UP else DOWN,
                )
            }
            ChartCard("Price") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ataglanceplus.live.FinanceSource.Range.entries.forEach { r ->
                        FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) })
                    }
                }
                if (h.candles.size >= 2) {
                    val zone = ZoneId.systemDefault()
                    val intraday = range.ordinal <= com.ataglanceplus.live.FinanceSource.Range.WEEK.ordinal
                    fun whenText(c: com.ataglanceplus.live.FinanceSource.Candle): String {
                        val at = Instant.ofEpochMilli(c.time)
                        val date = dayAndDate(at.atZone(zone).toLocalDate())
                        return if (intraday) "$date ${clock(context, at)}" else date
                    }
                    // Four labels along the bottom: times for one day, days for five, dates beyond.
                    val labelPattern = when (range) {
                        com.ataglanceplus.live.FinanceSource.Range.DAY -> null
                        com.ataglanceplus.live.FinanceSource.Range.WEEK -> "EEE"
                        com.ataglanceplus.live.FinanceSource.Range.YEAR -> "MMM"
                        else -> "dMMM"
                    }?.let { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, it), locale) }
                    val last = h.candles.lastIndex
                    val axis = listOf(0, last / 3, 2 * last / 3, last).distinct().map { i ->
                        val at = Instant.ofEpochMilli(h.candles[i].time)
                        i to (labelPattern?.format(at.atZone(zone)) ?: clock(context, at))
                    }
                    CandleChart(
                        candles = h.candles,
                        vwap = h.vwap,
                        vwapStarts = h.vwapStarts,
                        describe = { i ->
                            val c = h.candles[i]
                            "${whenText(c)}\nOpen ${money(c.open)} · High ${money(c.high)} · Low ${money(c.low)} · Close ${money(c.close)}" +
                                (h.vwap.getOrNull(i)?.let { " · VWAP ${money(it)}" } ?: "")
                        },
                        format = { money(it) },
                        axis = axis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "VWAP · ${vwapSettings.source.label} · ${vwapSettings.anchor.label}" +
                                if (vwapSettings.extendedHours) " · extended hours" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { editingVwap = true }) { Text("Change") }
                    }
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
    if (editingVwap) {
        VwapDialog(vwapSettings, onDone = { editingVwap = false }) {
            vwapSettings = it
            Prefs.setFinanceVwap(context, it)
        }
    }
    Text("Prices from Yahoo Finance's free feed; some exchanges are 15 minutes behind.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The VWAP's inputs, as eToro names them: what each candle contributes (Source), when it starts
 * again (Anchor period), and whether the 1D and 5D charts include pre-market and after-hours
 * candles. Changes apply at once.
 */
@Composable
private fun VwapDialog(
    settings: com.ataglanceplus.live.FinanceSource.VwapSettings,
    onDone: () -> Unit,
    onChange: (com.ataglanceplus.live.FinanceSource.VwapSettings) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        title = { Text("VWAP") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ataglanceplus.live.FinanceSource.VwapSource.entries.forEach { option ->
                        FilterChip(selected = settings.source == option, onClick = { onChange(settings.copy(source = option)) }, label = { Text(option.label) })
                    }
                }
                Text("Anchor period", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ataglanceplus.live.FinanceSource.VwapAnchor.entries.forEach { option ->
                        FilterChip(selected = settings.anchor == option, onClick = { onChange(settings.copy(anchor = option)) }, label = { Text(option.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Extended hours")
                        Text(
                            "1D and 5D charts include pre-market and after-hours trading, as in eToro's 24/5 charts. Yahoo has no overnight candles.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(checked = settings.extendedHours, onCheckedChange = { onChange(settings.copy(extendedHours = it)) })
                }
            }
        },
    )
}

/** VWAP's line: the first categorical blue, in its dark-theme and light-theme steps. */
private val VWAP_DARK = Color(0xFF3987E5)
private val VWAP_LIGHT = Color(0xFF2A78D6)

/**
 * Candles over time, filled green for a rise (close at or above open) and red for a fall, with
 * wicks from low to high, and the VWAP as a 2dp blue line (broken where it starts again). Scaled
 * to the range's own low and high (marked at the left). A legend names the three marks; a tap
 * picks a candle and describes its open, high, low, close and VWAP above the chart.
 */
@Composable
private fun CandleChart(
    candles: List<com.ataglanceplus.live.FinanceSource.Candle>,
    vwap: List<Double?>,
    vwapStarts: Set<Int>,
    describe: (Int) -> String,
    format: (Double) -> String,
    axis: List<Pair<Int, String>>,
) {
    if (candles.isEmpty()) return
    var selected by remember(candles) { mutableIntStateOf(candles.lastIndex) }
    val vwapColour = if (isSystemInDarkTheme()) VWAP_DARK else VWAP_LIGHT
    val grid = MaterialTheme.colorScheme.outlineVariant
    val averages = vwap.filterNotNull()
    val low = minOf(candles.minOf { it.low }, averages.minOrNull() ?: Double.MAX_VALUE)
    val high = maxOf(candles.maxOf { it.high }, averages.maxOrNull() ?: -Double.MAX_VALUE).let { if (it == low) low + 1 else it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendKey(UP, "Up", line = false)
            LegendKey(DOWN, "Down", line = false)
            LegendKey(vwapColour, "VWAP", line = true)
        }
        Text(describe(selected), style = MaterialTheme.typography.bodyMedium)
        Row {
            Column(modifier = Modifier.height(180.dp).width(52.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(format(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(format(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .pointerInput(candles) {
                            detectTapGestures { at -> selected = (at.x / size.width * candles.size).toInt().coerceIn(0, candles.lastIndex) }
                        }
                ) {
                    val pad = 4.dp.toPx()
                    val slot = size.width / candles.size
                    val body = (slot * 0.7f).coerceAtLeast(1f)
                    val wick = 1.dp.toPx().coerceAtMost(body)
                    fun x(i: Int) = (i + 0.5f) * slot
                    fun y(v: Double) = pad + (1 - ((v - low) / (high - low)).toFloat()) * (size.height - 2 * pad)
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                    // The chosen candle's faint guide, behind the marks.
                    drawLine(grid, Offset(x(selected), 0f), Offset(x(selected), size.height), strokeWidth = 1.dp.toPx())
                    candles.forEachIndexed { i, c ->
                        val colour = if (c.close >= c.open) UP else DOWN
                        drawLine(colour, Offset(x(i), y(c.high)), Offset(x(i), y(c.low)), strokeWidth = wick)
                        val top = y(maxOf(c.open, c.close))
                        val height = (y(minOf(c.open, c.close)) - top).coerceAtLeast(1.dp.toPx())
                        drawRect(colour, topLeft = Offset(x(i) - body / 2, top), size = Size(body, height))
                    }
                    val path = Path()
                    var drawing = false
                    vwap.forEachIndexed { i, v ->
                        if (v == null || i in vwapStarts) drawing = false
                        if (v == null) return@forEachIndexed
                        if (drawing) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                        drawing = true
                    }
                    drawPath(path, vwapColour, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                AxisLabels(axis, candles.size)
            }
        }
    }
}

/** One legend entry: a filled square for candles, a short line for the VWAP. */
@Composable
private fun LegendKey(colour: Color, label: String, line: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 14.dp, height = 10.dp)) {
            if (line) drawLine(colour, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
            else drawRect(colour, topLeft = Offset(2.dp.toPx(), 0f), size = Size(size.width - 4.dp.toPx(), size.height))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- eToro ---

/** How the eToro positions list is ordered. */
private enum class EtoroSort(val label: String) { VALUE("Value"), PNL("P/L"), PERCENT("P/L %"), NAME("Name") }

/** A row of the eToro dashboard: a holding, a copied trader, or cash. */
private data class EtoroRow(val name: String, val detail: String?, val invested: Double, val pnl: Double, val isCash: Boolean = false) {
    val value: Double get() = invested + pnl
    val pnlPercent: Double get() = if (invested != 0.0) 100 * pnl / invested else 0.0
}

/**
 * The user's eToro portfolio: its value with profit/loss on open positions, the value over time
 * (recorded by this app, since eToro keeps no history for the API), where the money is, and every
 * holding and copied trader with its profit/loss, in a chosen order.
 */
@Composable
private fun EtoroDashboard() {
    val context = LocalContext.current
    val refresh = LocalRefresh.current
    val load by produceState<Load<Pair<com.ataglanceplus.live.EtoroSource.Portfolio, List<com.ataglanceplus.live.EtoroSource.Snapshot>>>>(Load.Loading, refresh.generation) {
        value = withContext(Dispatchers.IO) {
            val source = com.ataglanceplus.live.EtoroSource
            if (!source.hasKeys(context)) return@withContext Load.Missing("Add your eToro keys in the app's Chips tab: tap the eToro chip, then follow the steps there.")
            val current = source.portfolio.value
            val error = if (current == null || System.currentTimeMillis() - current.at > 60_000) source.refresh(context) else null
            source.portfolio.value?.let { Load.Ready(it to source.history(context)) } ?: Load.Missing(error ?: "eToro did not answer. Pull down to try again.")
        }
        refresh.loaded = refresh.generation
    }
    var sort by remember { mutableStateOf(EtoroSort.VALUE) }
    Title("eToro")
    when (val state = load) {
        Load.Loading -> Text("Loading…")
        is Load.Missing -> Text(state.why)
        is Load.Ready -> {
            val (p, history) = state.data
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            fun money(v: Double) = (if (v < 0) "−" else "") + "$" + String.format(locale, "%,.2f", kotlin.math.abs(v))
            fun change(amount: Double, percent: Double) =
                (if (amount >= 0) "▲ +" else "▼ −") + "$" + String.format(locale, "%,.2f", kotlin.math.abs(amount)) + String.format(locale, " (%+.2f%%)", percent)
            Column {
                Text(money(p.equity), style = MaterialTheme.typography.displaySmall)
                Text("${change(p.pnl, p.pnlPercent)} on open positions", style = MaterialTheme.typography.bodyLarge, color = if (p.pnl >= 0) UP else DOWN)
            }
            ChartCard("Summary") {
                Stat("Invested", money(p.invested))
                Stat("Profit/loss", (if (p.pnl >= 0) "+" else "") + money(p.pnl))
                Stat("Available cash", money(p.cash))
                Stat("Updated", clock(context, Instant.ofEpochMilli(p.at)))
            }

            ChartCard("Value over time") {
                val points = history.map { Reading(Instant.ofEpochMilli(it.at), it.equity) }
                if (points.size >= 2) {
                    LineChart(
                        points = points,
                        describe = { r ->
                            val at = r.time.atZone(ZoneId.systemDefault())
                            "${dayAndDate(at.toLocalDate())} ${clock(context, r.time)} · ${money(r.value)}"
                        },
                        format = { "$" + String.format(locale, "%,.0f", it) },
                        markers = points.size <= 12,
                    )
                } else {
                    Text("Nothing to draw yet.", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "eToro does not share your account's history, so this app records it each time it checks (every 5 minutes while the chip is on). " +
                        "Recorded since ${dayAndDate(Instant.ofEpochMilli(history.firstOrNull()?.at ?: p.at).atZone(ZoneId.systemDefault()).toLocalDate())}. " +
                        "Deposits and withdrawals change the value too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val rows = p.holdings.map { EtoroRow(it.name, it.symbol?.takeIf { s -> s != it.name }?.let { s -> if (it.positions > 1) "$s · ${it.positions} positions" else s }, it.invested, it.pnl) } +
                p.copies.map { EtoroRow(it.username, "Copying", it.invested, it.pnl) }
            ChartCard("Allocation") {
                val cash = EtoroRow("Cash", null, p.cash.coerceAtLeast(0.0), 0.0, isCash = true)
                AllocationBars((rows + cash).filter { it.value > 0 }.sortedByDescending { it.value }, locale)
            }

            ChartCard("Positions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EtoroSort.entries.forEach { s -> FilterChip(selected = s == sort, onClick = { sort = s }, label = { Text(s.label) }) }
                }
                val ordered = when (sort) {
                    EtoroSort.VALUE -> rows.sortedByDescending { it.value }
                    EtoroSort.PNL -> rows.sortedByDescending { it.pnl }
                    EtoroSort.PERCENT -> rows.sortedByDescending { it.pnlPercent }
                    EtoroSort.NAME -> rows.sortedBy { it.name.lowercase() }
                }
                if (ordered.isEmpty()) Text("No open positions.", style = MaterialTheme.typography.bodyMedium)
                ordered.forEach { row ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(row.value), style = MaterialTheme.typography.bodyLarge)
                                Text(change(row.pnl, row.pnlPercent), style = MaterialTheme.typography.bodySmall, color = if (row.pnl >= 0) UP else DOWN)
                            }
                        }
                        Text("Invested ${money(row.invested)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            context.packageManager.getLaunchIntentForPackage(com.ataglanceplus.cards.ChipSpec.ETORO_PKG)?.let { OpenButton("Open eToro", it) }
        }
    }
    Text(
        "From eToro's API with your read-only key. Profit/loss is on open positions and copies; the percentage is against what is invested in them. Amounts in US dollars.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Where the money is: one bar per holding (largest first), each as a share of the whole, with
 * anything past the ninth gathered as "Other". One series, so one colour and no legend.
 */
@Composable
private fun AllocationBars(rows: List<EtoroRow>, locale: Locale) {
    val total = rows.sumOf { it.value }.takeIf { it > 0 } ?: return
    val shown = if (rows.size > 10) rows.take(9) + EtoroRow("Other (${rows.size - 9})", null, rows.drop(9).sumOf { it.value }, 0.0) else rows
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { row ->
            val share = (row.value / total).toFloat()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(String.format(locale, "%.1f%%", share * 100), style = MaterialTheme.typography.bodyMedium)
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(track, size = size, cornerRadius = radius)
                    drawRoundRect(bar, size = Size((size.width * share).coerceAtLeast(size.height), size.height), cornerRadius = radius)
                }
            }
        }
    }
}

// --- Sports ---

/** The followed team (or series) a game or session belongs to, and its games or sessions. */
private suspend fun ensureSports(context: Context) {
    if (com.ataglanceplus.live.SportsSource.games.value.isEmpty() && com.ataglanceplus.live.SportsSource.races.value.isEmpty()) {
        runCatching { com.ataglanceplus.live.SportsSource.refreshSchedules(context) }
    }
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

// --- Sports & F1 full-screen dashboards (Material You, swipeable) ---------------------------------

private val SportBg = Color(0xFF0B0E14)
private val SportCard = Color(0xFF151922)
private val SportHigh = Color(0xFF1E2430)
private val SportBorder = Color(0x14FFFFFF)
private val SportText = Color(0xFFE2E2E6)
private val SportSub = Color(0xFF9AA0A6)
private val SportAccent = Color(0xFFA8C7FA)
private val F1Emerald = Color(0xFF1A5C48)
private val F1Mint = Color(0xFF6DD58C)
private val LiveRed = Color(0xFFE53935)

/**
 * Each team's own colour for the hero and the stat bars: ESPN's main colour (from the game, or
 * looked up for teams the feed gave none). A main colour too dark to see on the dark screen is
 * swapped for the team's second colour when that is a strong one (Cavaliers' gold), else lifted
 * towards white (Bucks' green). If the two teams still look alike, the away team takes its
 * second colour. Without any, the app's accent and gold.
 */
private fun teamColours(game: com.ataglanceplus.live.Game): Pair<Color, Color> {
    fun pick(side: com.ataglanceplus.live.Side, fallback: Color): Pair<Color, Color?> {
        val (main, alt) = com.ataglanceplus.live.SportsSource.colours(game, side) ?: (null to null)
        val second = alt?.let { Color(it) }?.takeIf { it.luminance() > 0.12f && it.saturation() > 0.35f }
        val first = main?.let { Color(it) } ?: return fallback to second
        return when {
            first.luminance() >= 0.06f -> first
            second != null -> second
            else -> first.lift(0.4f)
        } to second
    }
    val (home, _) = pick(game.home, SportAccent)
    val (away, awayAlt) = pick(game.away, Color(0xFFFFB81C))
    val alike = kotlin.math.abs(home.red - away.red) + kotlin.math.abs(home.green - away.green) + kotlin.math.abs(home.blue - away.blue) < 0.3f
    return home to if (alike && awayAlt != null) awayAlt else away
}

private fun Color.saturation(): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    return if (max == 0f) 0f else (max - min) / max
}

/** Mixes a colour toward white. */
private fun Color.lift(fraction: Float): Color = Color(
    red = red + (1 - red) * fraction, green = green + (1 - green) * fraction, blue = blue + (1 - blue) * fraction, alpha = 1f,
)

/** Mixes a colour toward black, for the hero's tonal wash. */
private fun Color.dim(fraction: Float): Color = Color(
    red = red * (1 - fraction), green = green * (1 - fraction), blue = blue * (1 - fraction), alpha = 1f,
)

@Composable
private fun teamLogo(url: String?): androidx.compose.ui.graphics.ImageBitmap? =
    remember(url) { com.ataglanceplus.live.SportsSource.logo(url)?.asImageBitmap() }

/** The swipeable pill tab bar (spec 2.2): equal pills, the selected one raised. */
@Composable
private fun SegmentedPills(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SportCard)
            .border(1.dp, SportBorder, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) SportHigh else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    color = if (on) Color.White else SportSub,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A dashboard header line (spec: 22sp medium). */
@Composable
private fun ScreenTitle(text: String) {
    Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun GameMatchDashboard(eventId: String) {
    val context = LocalContext.current
    val refresh = LocalRefresh.current
    val load by produceState<Load<Pair<com.ataglanceplus.live.Followed?, List<com.ataglanceplus.live.Game>>>>(Load.Loading, eventId, refresh.generation) {
        withContext(Dispatchers.IO) { ensureSports(context) }
        val all = com.ataglanceplus.live.SportsSource.games.value
        val key = all.entries.firstOrNull { (_, games) -> games.any { it.eventId == eventId } }?.key
        value = if (key == null) Load.Missing("This game is no longer in your teams' fixtures.")
        else Load.Ready(Prefs.sportsFollowed(context).firstOrNull { it.key == key } to all.getValue(key))
        refresh.loaded = refresh.generation
    }
    Column(modifier = Modifier.fillMaxSize().background(SportBg).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val state = load) {
            Load.Loading -> { ScreenTitle("Game"); Text("Loading…", color = SportSub) }
            is Load.Missing -> { ScreenTitle("Game"); Text(state.why, color = SportSub) }
            is Load.Ready -> {
                val (team, games) = state.data
                val game = games.first { it.eventId == eventId }
                var revealed by remember { mutableStateOf(false) }
                val hidden = !revealed && com.ataglanceplus.live.SportsSource.scoresHidden(context, eventId)
                val detail by produceState<com.ataglanceplus.live.GameDetail?>(null, game.eventId, refresh.generation) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { com.ataglanceplus.live.SportsSource.detail(game) } ?: value
                        if (game.state != "in") break
                        kotlinx.coroutines.delay(30_000)
                    }
                }
                ScreenTitle(team?.name ?: game.leagueName)
                MatchHero(game, hidden)
                if (hidden) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Scores are hidden for this team.", color = SportSub, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { revealed = true }) { Text("Reveal") }
                    }
                }
                val d = detail
                val tabs = buildList {
                    if (!hidden && d != null) {
                        if (d.leaders.isNotEmpty()) add("Leaders")
                        if (d.teamStats.isNotEmpty()) add("Team Stats")
                        if (d.boxScores.isNotEmpty()) add("Box Score")
                        if (d.lineups.isNotEmpty()) add("Line-ups")
                        if (d.events.isNotEmpty() || d.scoring.isNotEmpty()) add("Timeline")
                    } else if (d != null && d.lineups.isNotEmpty()) add("Line-ups")
                    add("Schedule")
                }
                val pager = rememberPagerState(pageCount = { tabs.size })
                val scope = rememberCoroutineScope()
                if (tabs.size > 1) {
                    SegmentedPills(tabs, pager.currentPage.coerceIn(0, tabs.lastIndex)) { idx -> scope.launch { pager.animateScrollToPage(idx) } }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (tabs.getOrNull(page)) {
                            "Leaders" -> if (d != null) LeadersTab(d, game)
                            "Team Stats" -> if (d != null) TeamStatsTab(d, game)
                            "Box Score" -> if (d != null) BoxScoreTab(d, game)
                            "Line-ups" -> if (d != null) d.lineups.forEachIndexed { i, l -> LineupCard(l, i == 0) }
                            "Timeline" -> if (d != null) TimelineTab(d)
                            "Schedule" -> ScheduleTab(games, eventId, team, hidden)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenButton("Open in Google", com.ataglanceplus.cards.LiveCards.googleSearch(context, "${game.home.name} vs ${game.away.name}"))
                    game.link?.let { OpenButton("ESPN", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
                }
            }
        }
    }
}

@Composable
private fun MatchHero(game: com.ataglanceplus.live.Game, hidden: Boolean) {
    val context = LocalContext.current
    val (homeColour, awayColour) = teamColours(game)
    val scored = game.state != "pre" && !hidden
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(homeColour.dim(0.72f), Color(0xFF161B26), awayColour.dim(0.72f))))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroTeam(game.home, "HOME", homeColour, Color(0xFFF2B8B5))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(game.leagueName.uppercase(), color = SportSub, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            when {
                scored -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(game.home.score ?: "0", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("–", color = Color(0xFF64748B), fontSize = 22.sp, modifier = Modifier.padding(horizontal = 9.dp))
                    Text(game.away.score ?: "0", color = SportText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                game.state == "pre" -> Text(clock(context, Instant.ofEpochMilli(game.start)), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                else -> Text("– : –", color = SportSub, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            val live = game.state == "in"
            Text(
                when (game.state) { "in" -> "LIVE · ${game.status}"; "post" -> game.status; else -> dayAndDate(Instant.ofEpochMilli(game.start).atZone(ZoneId.systemDefault()).toLocalDate()) },
                color = if (live) Color(0xFFFF8989) else SportSub,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (live) Color(0x33E03A3E) else Color(0x33000000))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        HeroTeam(game.away, "AWAY", awayColour, Color(0xFFC4C6D0))
    }
}

@Composable
private fun HeroTeam(side: com.ataglanceplus.live.Side, place: String, ring: Color, pill: Color) {
    Column(modifier = Modifier.width(86.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val logo = teamLogo(side.logoUrl)
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(ring.dim(0.1f)).border(2.dp, Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(34.dp))
            else Text(side.abbr, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
        Text(side.name, color = SportText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 15.sp)
        Text(place, color = pill, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(pill.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun LeadersTab(d: com.ataglanceplus.live.GameDetail, game: com.ataglanceplus.live.Game) {
    val home = d.leaders.getOrNull(0)?.second.orEmpty()
    val away = d.leaders.getOrNull(1)?.second.orEmpty()
    val categories = home.map { it.first }.ifEmpty { away.map { it.first } }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(game.home.abbr, color = Color(0xFFFF8989), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("LEADERS", color = SportSub, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(game.away.abbr, color = Color(0xFFFFE082), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    categories.forEach { category ->
        val h = home.firstOrNull { it.first == category }
        val a = away.firstOrNull { it.first == category }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SportHigh).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Each value sits beside its player; a long name shortens before it pushes the value away.
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(h?.second ?: "–", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(h?.third ?: "–", color = SportAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }
            Text(category, color = SportSub, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(a?.third ?: "–", color = SportAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(a?.second ?: "–", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** Reads a comparable number from a stat value: a percentage if present, else the first number. */
private fun statValue(s: String): Float {
    Regex("""(\d+(?:\.\d+)?)\s*%""").find(s)?.let { return it.groupValues[1].toFloat() }
    return Regex("""\d+(?:\.\d+)?""").find(s)?.value?.toFloatOrNull() ?: 0f
}

@Composable
private fun TeamStatsTab(d: com.ataglanceplus.live.GameDetail, game: com.ataglanceplus.live.Game) {
    val (homeColour, awayColour) = teamColours(game)
    d.teamStats.forEach { (label, homeText, awayText) ->
        val h = statValue(homeText)
        val a = statValue(awayText)
        val total = h + a
        val homeShare = if (total <= 0f) 0.5f else (h / total).coerceIn(0.05f, 0.95f)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(homeText, color = SportText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(label, color = SportSub, fontSize = 12.sp)
                Text(awayText, color = SportText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF262B36)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.weight(homeShare).fillMaxHeight().background(homeColour))
                Box(modifier = Modifier.weight(1f - homeShare).fillMaxHeight().background(awayColour))
            }
        }
    }
}

@Composable
private fun BoxScoreTab(d: com.ataglanceplus.live.GameDetail, game: com.ataglanceplus.live.Game) {
    if (d.boxScores.size < 2) { d.boxScores.forEach { BoxScoreTable(it) }; return }
    var showHome by remember { mutableStateOf(true) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1118)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(true, false).forEach { home ->
            val on = showHome == home
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (on) SportHigh else Color.Transparent).clickable { showHome = home }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (home) game.home.name else game.away.name, color = if (on) Color.White else SportSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
    BoxScoreTable(if (showHome) d.boxScores[0] else d.boxScores[1])
}

@Composable
private fun LineupCard(lineup: com.ataglanceplus.live.GameDetail.Lineup, home: Boolean) {
    ChartCard(listOfNotNull(lineup.team, if (home) "Home" else "Away", lineup.formation).joinToString(" · ")) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { lineup.starters.forEach { PlayerRow(it) } }
        if (lineup.subs.isNotEmpty()) {
            Text("Substitutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { lineup.subs.forEach { PlayerRow(it) } }
        }
    }
}

@Composable
private fun TimelineTab(d: com.ataglanceplus.live.GameDetail) {
    val rows = (d.events + d.scoring)
    rows.forEach { e ->
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SportHigh).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(e.clock, color = SportSub, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            Text(
                when (e.kind) {
                    com.ataglanceplus.live.GameDetail.Kind.GOAL -> "⚽ "
                    com.ataglanceplus.live.GameDetail.Kind.YELLOW -> "🟨 "
                    com.ataglanceplus.live.GameDetail.Kind.RED -> "🟥 "
                    com.ataglanceplus.live.GameDetail.Kind.SUB -> "⇄ "
                    else -> ""
                } + e.text + (e.team?.let { " · $it" } ?: ""),
                color = SportText, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScheduleTab(games: List<com.ataglanceplus.live.Game>, eventId: String, team: com.ataglanceplus.live.Followed?, hidden: Boolean) {
    val upcoming = games.filter { it.state == "pre" && it.eventId != eventId }.sortedBy { it.start }.take(6)
    val results = games.filter { it.state == "post" && it.eventId != eventId }.sortedByDescending { it.start }.take(6)
    if (upcoming.isNotEmpty()) {
        Text("Next games", color = SportSub, style = MaterialTheme.typography.labelLarge)
        upcoming.forEach {
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(12.dp)) { FixtureRow(it, team, hidden) }
        }
    }
    if (results.isNotEmpty()) {
        Text("Results", color = SportSub, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        results.forEach {
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(12.dp)) { FixtureRow(it, team, hidden) }
        }
    }
    if (upcoming.isEmpty() && results.isEmpty()) Text("No other fixtures.", color = SportSub)
}

@Composable
private fun F1EventDashboard(eventId: String) {
    val context = LocalContext.current
    val refresh = LocalRefresh.current
    val load by produceState<Load<List<com.ataglanceplus.live.RaceSession>>>(Load.Loading, eventId, refresh.generation) {
        withContext(Dispatchers.IO) { ensureSports(context) }
        val sessions = com.ataglanceplus.live.SportsSource.races.value.values.flatten().filter { it.eventId == eventId }.sortedBy { it.start }
        value = if (sessions.isEmpty()) Load.Missing("This race weekend is no longer in the calendar.") else Load.Ready(sessions)
        refresh.loaded = refresh.generation
    }
    // The session opened from the weekend list (by its name), or null for the weekend.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = opened != null) { opened = null }
    Column(modifier = Modifier.fillMaxSize().background(SportBg).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val state = load) {
            Load.Loading -> { ScreenTitle("Formula 1"); Text("Loading…", color = SportSub) }
            is Load.Missing -> { ScreenTitle("Formula 1"); Text(state.why, color = SportSub) }
            is Load.Ready -> {
                val sessions = state.data
                val first = sessions.first()
                val raceDay = (sessions.firstOrNull { it.session.equals("Race", ignoreCase = true) } ?: sessions.last()).start
                    .let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                val session = sessions.firstOrNull { it.session == opened }
                if (session != null) {
                    F1SessionScreen(context, session, raceDay, hidden = com.ataglanceplus.live.SportsSource.scoresHidden(context, eventId)) { opened = null }
                    return@Column
                }
                ScreenTitle(first.eventName)
                F1Hero(first)
                val next = sessions.firstOrNull { it.state == "pre" } ?: sessions.firstOrNull { it.state == "in" }
                if (next != null) F1UpNext(context, next)
                val standings by produceState<Pair<List<com.ataglanceplus.live.JolpicaSource.DriverStanding>?, List<com.ataglanceplus.live.JolpicaSource.TeamStanding>?>?>(null, refresh.generation) {
                    value = withContext(Dispatchers.IO) {
                        com.ataglanceplus.live.JolpicaSource.driverStandings() to com.ataglanceplus.live.JolpicaSource.teamStandings()
                    }
                }
                val tabs = listOf("Weekend", "Standings")
                val pager = rememberPagerState(pageCount = { tabs.size })
                val scope = rememberCoroutineScope()
                SegmentedPills(tabs, pager.currentPage.coerceIn(0, tabs.lastIndex)) { idx -> scope.launch { pager.animateScrollToPage(idx) } }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tabs.getOrNull(page)) {
                            "Weekend" -> {
                                // The live session stands out; with none on, the next one does.
                                val live = sessions.firstOrNull { it.state == "in" }
                                sessions.forEach { s ->
                                    Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { opened = s.session }) {
                                        F1SessionRow(context, s, isNext = live == null && s == next, isLive = s == live)
                                    }
                                }
                                Text("Tap a session: live timing while it is on, the results after, a preview before.", color = SportSub, fontSize = 11.sp)
                            }
                            "Standings" -> F1Standings(standings)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenButton("Open in Google", com.ataglanceplus.cards.LiveCards.googleSearch(context, "F1 ${first.eventName}"))
                    first.link?.let { OpenButton("ESPN", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
                }
            }
        }
    }
}

/** One session of the weekend: live timing while it is on, the results after, a preview before. */
@Composable
private fun F1SessionScreen(context: Context, session: com.ataglanceplus.live.RaceSession, raceDay: java.time.LocalDate, hidden: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ${session.eventName.replace("Grand Prix", "GP").trim()}", color = F1Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp))
    }
    ScreenTitle(f1SessionName(session.session))
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            hidden && session.state != "pre" -> Text("Scores are hidden for this weekend. Turn that off in Cards › Sports to see them.", color = SportSub)
            session.state == "in" -> F1LiveSession(context, session)
            session.state == "post" -> F1FinishedSession(session, raceDay)
            else -> F1UpcomingSession(context, session, raceDay)
        }
    }
}

/** A tyre's initial and colour: soft red, medium yellow, hard white, inters green, wets blue. */
private fun tyreLook(compound: String): Pair<String, Color>? = when (compound.uppercase()) {
    "SOFT" -> "S" to Color(0xFFE8002D); "MEDIUM" -> "M" to Color(0xFFFFD12E); "HARD" -> "H" to Color(0xFFF0F0F0)
    "INTERMEDIATE" -> "I" to Color(0xFF43B02A); "WET" -> "W" to Color(0xFF0067AD); else -> null
}

/** The tyre's initial in a ring of its colour, then [laps] on that set. */
@Composable
private fun TyreBadge(compound: String, laps: Int?) {
    val (letter, colour) = tyreLook(compound) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(CircleShape).border(2.dp, colour, CircleShape), contentAlignment = Alignment.Center) {
            Text(letter, color = colour, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        // A set width, so the rings line up down the column whether the age has one digit or two.
        if (laps != null) Text("$laps", color = SportText, fontSize = 11.5.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 4.dp).width(18.dp))
    }
}

/** A tyre column cell: the ring centred under "Tyre", the laps on that set centred under "Laps". */
@Composable
private fun TyreCell(compound: String, laps: Int?) {
    val (letter, colour) = tyreLook(compound) ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp).clip(CircleShape).border(2.dp, colour, CircleShape), contentAlignment = Alignment.Center) {
                Text(letter, color = colour, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(laps?.toString().orEmpty(), color = SportText, fontSize = 11.5.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A table's column titles, lined up with [F1TimingLine]'s columns (the last one centred, as its figures are). */
@Composable
private fun F1TableHeader(first: String, second: String, third: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Text("Pos", color = SportSub, fontSize = 10.5.sp, modifier = Modifier.width(24.dp))
        Text("Driver", color = SportSub, fontSize = 10.5.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text(first, color = SportSub, fontSize = 10.5.sp, modifier = Modifier.width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(second, color = SportSub, fontSize = 10.5.sp, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        // "Tyre · Laps" splits into two halves, each over its own figure (see [TyreCell]).
        val halves = third.split(" · ")
        Row(Modifier.padding(start = 8.dp).width(64.dp)) {
            halves.forEach { h ->
                Text(h, color = SportSub, fontSize = 10.5.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** A timing row: position, team bar, driver code, two figures, then [third] (a tyre, laps or points). */
@Composable
private fun F1TimingLine(position: String, colour: Int, code: String, faded: Boolean, first: String, second: String, third: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(position, color = SportSub, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(Color(colour)))
        Text(code, color = if (faded) SportSub else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
        Text(first, color = if (faded) SportSub else Color.White, fontSize = 12.5.sp, modifier = Modifier.width(76.dp), maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(second, color = SportSub, fontSize = 12.sp, modifier = Modifier.width(64.dp), maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        // Centred under its title, as the figures in it are narrower than the title.
        Box(Modifier.padding(start = 8.dp).width(64.dp), contentAlignment = Alignment.Center) { third() }
    }
}

/** While the session is on: F1's live timing — the map, the flag and clock, and the full order. */
@Composable
private fun F1LiveSession(context: Context, session: com.ataglanceplus.live.RaceSession) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        com.ataglanceplus.live.LiveTiming.want(scope, true)
        onDispose { com.ataglanceplus.live.LiveTiming.want(scope, false) }
    }
    val live by com.ataglanceplus.live.LiveTiming.state.collectAsStateWithLifecycle()
    val state = live
    if (state == null || state.cars.isEmpty()) {
        Text("Connecting to F1 live timing…", color = SportSub)
        if (session.top.isNotEmpty()) Text("Running order so far: " + session.top.joinToString(" · "), color = SportSub, fontSize = 12.5.sp)
        return
    }
    val (flag, flagColour) = when (state.trackStatus) {
        "2" -> "Yellow flag" to Color(0xFFFFD54F); "4" -> "Safety car" to Color(0xFFFFC107); "5" -> "Red flag" to Color(0xFFFF5252)
        "6", "7" -> "Virtual safety car" to Color(0xFFFFC107); else -> "Green flag" to Color(0xFF38E5B5)
    }
    // The clock ticks between the feed's updates.
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val clockText = when {
        state.race && state.lap != null -> "Lap ${state.lap}" + (state.totalLaps?.let { "/$it" } ?: "")
        state.remainingMs != null -> {
            val s = ((state.remainingMs - (now - state.at)) / 1000).coerceAtLeast(0)
            (if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)) + " left"
        }
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("● $flag", color = flagColour, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(flagColour.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 4.dp))
        Text(clockText, color = SportText, fontSize = 13.5.sp, modifier = Modifier.padding(start = 10.dp))
    }
    // The map is redrawn every frame or so, moving each car on at its pace between the feed's updates.
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 240.dp.roundToPx() }
    val map = remember(session.eventId, px) { com.ataglanceplus.cards.TrackMap.prepare(context, session, px) }
    if (map != null) {
        var frame by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                androidx.compose.runtime.withFrameMillis { }
                frame = System.currentTimeMillis()
                kotlinx.coroutines.delay(50)
            }
        }
        val cars = state.cars
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1F6DD58C)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(240.dp)) {
                drawIntoCanvas { map.drawOn(it.nativeCanvas, cars, frame) }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.race) F1TableHeader("Interval", "Last lap", "Tyre · Laps") else F1TableHeader("Best lap", "Gap", "Tyre · Laps")
        state.cars.forEach { car ->
            val first = when {
                car.out -> "Out"
                state.race -> if (car.inPit) "In pit" else if (car.position == 1) "Leader" else car.interval.ifEmpty { car.figure }
                else -> car.bestLap.ifEmpty { "No time" }
            }
            val second = when {
                state.race -> car.lastLap
                car.inPit && !car.out -> "In pit"
                car.position == 1 -> "Fastest"
                else -> car.figure
            }
            F1TimingLine("${car.position}", car.colour, car.code, car.out, first, second) { TyreCell(car.tyre, car.tyreAge.takeIf { car.tyre.isNotEmpty() }) }
        }
    }
    Text("Tyres: S soft, M medium, H hard, I intermediate, W wet. Laps: laps on that set of tyres.", color = SportSub, fontSize = 11.sp)
}

/** After the session: a summary, then the full classification; tap a driver for sectors, speed and tyres. */
@Composable
private fun F1FinishedSession(session: com.ataglanceplus.live.RaceSession, raceDay: java.time.LocalDate) {
    val context = LocalContext.current
    val result by produceState<Load<com.ataglanceplus.live.JolpicaSource.SessionResult>>(Load.Loading, session.session, session.start) {
        value = withContext(Dispatchers.IO) {
            com.ataglanceplus.live.F1Results.load(context, session, f1SessionName(session.session), raceDay)
                ?.let { Load.Ready(it) } ?: Load.Missing("No results for this session yet.")
        }
    }
    when (val r = result) {
        Load.Loading -> Text("Loading…", color = SportSub)
        is Load.Missing -> Text(r.why, color = SportSub)
        is Load.Ready -> {
            val name = session.session.lowercase()
            val kind = when {
                name == "race" || name == "r" || ((name.contains("sprint") || name == "sr") && !name.contains("qual") && !name.contains("shoot") && name != "sq" && name != "ss") -> F1Kind.Race
                name.startsWith("qual") || name == "q" || name == "sq" || name == "ss" || name.contains("shoot") -> F1Kind.Qualifying
                else -> F1Kind.Practice
            }
            F1Summary(r.data, kind)
            F1Classification(r.data, kind)
        }
    }
}

private enum class F1Kind { Practice, Qualifying, Race }

/** The session at a glance: the winner or fastest lap, the top speed, laps run. */
@Composable
private fun F1Summary(result: com.ataglanceplus.live.JolpicaSource.SessionResult, kind: F1Kind) {
    val rows = result.rows
    val lines = buildList {
        val first = rows.firstOrNull()
        if (kind == F1Kind.Race) {
            first?.let { add("Winner" to "${it.name.ifEmpty { it.code }}${it.time.takeIf { t -> t.contains(":") }?.let { t -> " · $t" }.orEmpty()}") }
            rows.firstOrNull { it.fastestLap }?.let { add("Fastest lap" to "${it.bestLap ?: it.time} · ${it.code}") }
        } else {
            first?.takeIf { it.time.contains(":") || it.time.contains(".") }?.let { add("Fastest lap" to "${it.time} · ${it.name.ifEmpty { it.code }}") }
        }
        rows.filter { it.topSpeed != null }.maxByOrNull { it.topSpeed!! }?.let { add("Top speed" to "${it.topSpeed} km/h · ${it.code}") }
        val laps = rows.mapNotNull { it.laps }
        if (laps.isNotEmpty() && kind != F1Kind.Race) {
            val most = rows.filter { it.laps != null }.maxBy { it.laps!! }
            add("Laps run" to "${laps.sum()} · most ${most.laps} by ${most.code}")
        }
        if (kind == F1Kind.Race) rows.mapNotNull { it.pitStops }.takeIf { it.isNotEmpty() }?.let { add("Pit stops" to "${it.sum()} in all") }
        if (!result.final) add("Status" to "Provisional")
    }
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = SportSub, fontSize = 12.5.sp, modifier = Modifier.width(96.dp))
                Text(value, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Every driver, with the figures that matter for the session; each opens to its detail. */
@Composable
private fun F1Classification(result: com.ataglanceplus.live.JolpicaSource.SessionResult, kind: F1Kind) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = result.rows.any { it.sectors.isNotEmpty() || it.stints.isNotEmpty() || it.topSpeed != null || it.qualifying.any { q -> q.isNotEmpty() } || it.grid != null }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (kind) {
            F1Kind.Race -> F1TableHeader("Time / gap", "Best lap", "Points")
            F1Kind.Qualifying -> F1TableHeader("Best lap", "Gap", "Out in")
            F1Kind.Practice -> F1TableHeader("Best lap", "Gap", "Laps")
        }
        val leader = result.rows.firstOrNull()?.time?.let { com.ataglanceplus.live.LiveTiming.parseLap(it) }
        result.rows.forEach { r ->
            val colour = r.colour ?: com.ataglanceplus.live.JolpicaSource.teamColour(r.teamId)
            val label = r.code.ifEmpty { r.name }
            // Qualifying from Jolpica has no gaps: work them out from the best laps.
            val gap = r.gap.ifEmpty {
                if (kind != F1Kind.Race && r.position != "1") {
                    val mine = com.ataglanceplus.live.LiveTiming.parseLap(r.time)
                    if (mine != null && leader != null) "+%.3f".format(java.util.Locale.ROOT, (mine - leader) / 1000.0) else ""
                } else ""
            }
            Column(Modifier.clip(RoundedCornerShape(8.dp)).then(if (detail) Modifier.clickable { open = if (open == label) null else label } else Modifier)) {
                when (kind) {
                    F1Kind.Race -> F1TimingLine(r.position, colour, label, r.status != null && r.status != "Finished" && !r.status.startsWith("+"),
                        r.time, (r.bestLap.orEmpty()) + if (r.fastestLap) " ⏱" else "") {
                        Text(r.points?.takeIf { it != "0" }.orEmpty(), color = SportText, fontSize = 12.sp)
                    }
                    F1Kind.Qualifying -> F1TimingLine(r.position, colour, label, false, r.time, gap) {
                        Text(r.qualifying.indexOfLast { it.isNotEmpty() }.takeIf { it >= 0 }?.let { "Q${it + 1}" } ?: r.points.orEmpty(), color = SportText, fontSize = 12.sp)
                    }
                    F1Kind.Practice -> F1TimingLine(r.position, colour, label, false, r.time, gap) {
                        Text(r.laps?.toString().orEmpty(), color = SportText, fontSize = 12.sp)
                    }
                }
                if (open == label) F1DriverDetail(r, kind)
            }
        }
    }
    if (detail) Text("Tap a driver for sectors, speed and tyres.", color = SportSub, fontSize = 11.sp)
}

/** One driver's detail: best sectors (the session's fastest in purple), top speed, tyres, and the race or qualifying story. */
@Composable
private fun F1DriverDetail(r: com.ataglanceplus.live.JolpicaSource.ResultRow, kind: F1Kind) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0FFFFFFF)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (r.name.isNotEmpty() && r.code.isNotEmpty()) Text(r.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (kind == F1Kind.Race) {
            listOfNotNull(
                r.grid?.let { g ->
                    val finish = r.position.toIntOrNull()
                    val moved = if (g > 0 && finish != null) g - finish else null
                    (if (g == 0) "From the pit lane" else "From P$g") + when { moved == null || moved == 0 -> ""; moved > 0 -> " (+$moved)"; else -> " ($moved)" }
                },
                r.laps?.let { "$it laps" },
                r.pitStops?.let { "$it stop${if (it == 1) "" else "s"}" },
                r.status?.takeIf { it != "Finished" && !it.startsWith("+") },
            ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = SportText, fontSize = 12.5.sp) }
        }
        if (kind == F1Kind.Qualifying && r.qualifying.any { it.isNotEmpty() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                r.qualifying.forEachIndexed { i, t -> F1Figure("Q${i + 1}", t.ifEmpty { "—" }) }
            }
        }
        if (r.sectors.size == 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                r.sectors.forEachIndexed { i, t -> F1Figure("Sector ${i + 1}", t, if (r.sectorFastest.getOrNull(i) == true) Color(0xFFC77DFF) else Color.White) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (kind != F1Kind.Race && r.laps != null) F1Figure("Laps", "${r.laps}")
            r.topSpeed?.let { F1Figure("Speed trap", "$it km/h") }
        }
        if (r.stints.isNotEmpty()) {
            Text("Tyres", color = SportSub, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.stints.forEach { (compound, laps) -> TyreBadge(compound, laps) }
            }
        }
    }
}

@Composable
private fun F1Figure(label: String, value: String, colour: Color = Color.White) {
    Column {
        Text(label, color = SportSub, fontSize = 10.5.sp)
        Text(value, color = colour, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Before the session: when (here and at the track), the countdown, the forecast at the circuit, and last year here. */
@Composable
private fun F1UpcomingSession(context: Context, session: com.ataglanceplus.live.RaceSession, raceDay: java.time.LocalDate) {
    val preview by produceState<com.ataglanceplus.live.F1Preview.Preview?>(null, session.session, session.start) {
        value = withContext(Dispatchers.IO) { com.ataglanceplus.live.F1Preview.load(raceDay, session.start) }
    }
    val start = Instant.ofEpochMilli(session.start)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(countdownTo(session.start).replaceFirstChar { it.uppercase() }, color = F1Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${dayAndDate(start.atZone(ZoneId.systemDefault()).toLocalDate())} · ${clock(context, start)} your time", color = Color.White, fontSize = 14.sp)
        preview?.utcOffsetSeconds?.let { offset ->
            val local = start.atOffset(java.time.ZoneOffset.ofTotalSeconds(offset))
            Text("${"%02d:%02d".format(local.hour, local.minute)} at the track", color = SportSub, fontSize = 12.5.sp)
        }
    }
    val p = preview
    if (p == null) {
        Text("Loading the forecast…", color = SportSub)
        return
    }
    p.forecast?.let { f ->
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Forecast at the circuit", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            val condition = com.ataglanceplus.weather.WeatherCondition.fromWmo(f.code).label
            Text("$condition · ${f.temperature.toInt()}°", color = SportText, fontSize = 14.sp)
            Text(listOfNotNull(f.rainChance?.let { "Rain $it%" }, f.windKmh?.let { "Wind ${it.toInt()} km/h" }).joinToString(" · "), color = SportSub, fontSize = 12.5.sp)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(p.circuit, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text(p.place, color = SportSub, fontSize = 12.5.sp)
        p.lastWinner?.let { Text("${p.lastYear} winner: $it", color = SportText, fontSize = 13.sp) }
        p.lastPole?.let { Text("${p.lastYear} pole: $it", color = SportText, fontSize = 13.sp) }
    }
}

/** A table row: position, a bar in the team's colour, the driver (or team), then the figures on the right. */
@Composable
private fun F1Row(position: String, teamId: String, code: String, name: String, figure: String, extra: String?, colour: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(position, color = SportSub, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(Color(colour ?: com.ataglanceplus.live.JolpicaSource.teamColour(teamId))))
        if (code.isNotEmpty()) Text(code, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).width(40.dp))
        Text(name, color = if (code.isEmpty()) Color.White else SportSub, modifier = Modifier.padding(start = if (code.isEmpty()) 8.dp else 0.dp).weight(1f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(figure, color = Color.White, fontSize = 12.5.sp, modifier = Modifier.padding(start = 8.dp))
        if (extra != null) Text(extra, color = SportSub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** The championship: drivers or teams, with points and wins. */
@Composable
private fun F1Standings(
    standings: Pair<List<com.ataglanceplus.live.JolpicaSource.DriverStanding>?, List<com.ataglanceplus.live.JolpicaSource.TeamStanding>?>?,
) {
    var teams by remember { mutableStateOf(false) }
    if (standings == null) {
        Text("Loading…", color = SportSub)
        return
    }
    val (drivers, constructors) = standings
    if (drivers == null && constructors == null) {
        Text("The standings could not be loaded. Try again in a moment.", color = SportSub)
        return
    }
    SegmentedPills(listOf("Drivers", "Teams"), if (teams) 1 else 0) { teams = it == 1 }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (teams) {
            constructors.orEmpty().forEach { t -> F1Row("${t.position}", t.teamId, "", t.name, "${t.points} pts", if (t.wins > 0) "${t.wins} won" else null) }
        } else {
            drivers.orEmpty().forEach { d -> F1Row("${d.position}", d.teamId, d.code, d.team, "${d.points} pts", if (d.wins > 0) "${d.wins} won" else null) }
        }
    }
}

@Composable
private fun F1Hero(session: com.ataglanceplus.live.RaceSession) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF10382C), Color(0xFF0D1F1B), Color(0xFF121722))))
            .border(1.dp, Color(0x406DD58C), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val outline = com.ataglanceplus.cards.CircuitIcons.find(session.circuitCity, session.circuitName, aod = false)
        Box(
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x1F6DD58C)).border(1.dp, Color(0x4D6DD58C), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (outline != null) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(outline), contentDescription = null, modifier = Modifier.size(44.dp), tint = F1Mint)
            else Text("F1", color = F1Mint, fontWeight = FontWeight.ExtraBold)
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(session.circuitName ?: session.eventName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            session.circuitCity?.let { Text(it, color = Color(0xFFA8C7B8), fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun F1UpNext(context: Context, session: com.ataglanceplus.live.RaceSession) {
    val label = if (session.state == "in") "Now: ${f1SessionName(session.session)}" else "Next: ${f1SessionName(session.session)}"
    val countdown = if (session.state == "in") "LIVE" else countdownTo(session.start)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x266DD58C)).border(1.dp, Color(0x596DD58C), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏱ $label", color = Color(0xFFD2FCE1), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(countdown, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(F1Emerald).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun F1SessionRow(context: Context, session: com.ataglanceplus.live.RaceSession, isNext: Boolean, isLive: Boolean = false) {
    val race = session.session.equals("Race", true) || session.session.equals("R", true)
    val dot = when { isLive -> LiveRed; isNext -> F1Mint; race -> Color(0xFFFFB74D); else -> Color(0xFF4A505C) }
    val start = Instant.ofEpochMilli(session.start)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (isLive) Color(0x2EFF5252) else if (isNext) Color(0x246DD58C) else if (race) Color(0x14FFB74D) else Color(0x08FFFFFF))
            .border(if (isLive) 1.5.dp else 1.dp, if (isLive) Color(0xB3FF6B6B) else if (isNext) Color(0x666DD58C) else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(f1SessionName(session.session), color = if (race) Color(0xFFFFE082) else Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                if (isLive) Text("On now · tap for live timing", color = Color(0xFFFF8989), fontSize = 11.sp)
                else if (isNext) Text("Up next", color = F1Mint, fontSize = 11.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (session.state == "in") {
                Text("● LIVE", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(LiveRed).padding(horizontal = 7.dp, vertical = 2.dp))
            } else {
                Text(if (session.state == "post") "Finished" else dayAndDate(start.atZone(ZoneId.systemDefault()).toLocalDate()),
                    color = SportText, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            if (session.state == "pre") Text(clock(context, start), color = SportSub, fontSize = 11.5.sp)
        }
    }
}

private fun f1SessionName(code: String): String = when (code.uppercase()) {
    "FP1" -> "Practice 1 (FP1)"; "FP2" -> "Practice 2 (FP2)"; "FP3" -> "Practice 3 (FP3)"
    "QUAL", "Q" -> "Qualifying"; "SQ", "SS" -> "Sprint Qualifying"; "SPRINT", "SR" -> "Sprint"
    "RACE", "R" -> "Grand Prix Race"
    else -> code
}

/** A short "in 3h 20m" / "in 2d" until [epochMillis]. */
private fun countdownTo(epochMillis: Long): String {
    val mins = ((epochMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    return when {
        mins >= 1440 -> "in ${mins / 1440}d ${(mins % 1440) / 60}h"
        mins >= 60 -> "in ${mins / 60}h ${mins % 60}m"
        else -> "in ${mins}m"
    }
}
