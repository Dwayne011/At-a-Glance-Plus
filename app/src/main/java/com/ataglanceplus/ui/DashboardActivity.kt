package com.ataglanceplus.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.drawText
import com.ataglanceplus.live.FinanceSource.Session
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
                        // Stocks and eToro share the sports dashboards' dark look.
                        kind?.startsWith(KIND_STOCK_PREFIX) == true -> FinanceScreen { StockDashboard(kind.removePrefix(KIND_STOCK_PREFIX)) }
                        kind == KIND_ETORO -> FinanceScreen { EtoroDashboard() }
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
                                else -> when {
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
 * Readings over time as one 2dp line, scaled to the low and high of those on screen (marked at the
 * left), with markers when there are few. Tap or drag along it to read the nearest reading above
 * the chart; with [zoomable], pinch to zoom and two fingers to move along.
 */
@Composable
private fun LineChart(points: List<Reading>, describe: (Reading) -> String, format: (Double) -> String, markers: Boolean, zoomable: Boolean = false) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val grid = MaterialTheme.colorScheme.outlineVariant
    val t0 = points.first().time.toEpochMilli()
    val span = (points.last().time.toEpochMilli() - t0).coerceAtLeast(1).toFloat()
    // Zoom works on the share of the time span on screen; at least about six readings stay in view.
    val window = remember(points.size, t0) { ChartWindow(1f, (6f / points.size).coerceIn(0.01f, 1f)) }
    fun share(r: Reading) = (r.time.toEpochMilli() - t0) / span
    val inView = points.filter { share(it) >= window.start - 0.001f && share(it) <= window.start + window.count + 0.001f }.ifEmpty { points }
    val low = inView.minOf { it.value }
    val high = inView.maxOf { it.value }.let { if (it == low) low + 1 else it }
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
                    .clipToBounds()
                    .chartGestures(points, if (zoomable) window else null) { fraction ->
                        val time = t0 + window.at(fraction) * span
                        selected = points.indices.minByOrNull { kotlin.math.abs(points[it].time.toEpochMilli() - time) } ?: selected
                    }
            ) {
                val pad = 6.dp.toPx()
                fun x(r: Reading) = if (points.size == 1) size.width / 2 else (share(r) - window.start) / window.count * size.width
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
        if (zoomable) ZoomHint(window)
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
    var shown by remember { mutableStateOf<Triple<String, Any, Int?>?>(null) }
    var vwapSettings by remember { mutableStateOf(Prefs.financeVwap(context)) }
    var editingVwap by remember { mutableStateOf(false) }
    // The candles' size on the intraday ranges: null for Auto (grouped to fit), else 5, 30 or 60 minutes.
    var candleSize by remember { mutableStateOf<Int?>(null) }
    // The stock screen's tab: 0 Overview (price, chart, the day), 1 What if (eToro positions).
    var stockTab by rememberSaveable { mutableIntStateOf(0) }
    // Live while the screen is open and in front: a new reading every 15 seconds. Nothing runs
    // once the user leaves it, switches app or turns the screen off.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var returning = false
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // Back on the screen after leaving it: read the price at once rather than in 15 seconds.
            if (returning) tick++
            returning = true
            while (true) {
                kotlinx.coroutines.delay(LIVE_REFRESH_MS)
                tick++
            }
        }
    }
    val load by produceState<Load<com.ataglanceplus.live.FinanceSource.History>>(Load.Loading, symbol, range, vwapSettings, candleSize, refresh.generation, tick) {
        // A new stock, range or candle size starts from "Loading…"; a refresh keeps the chart until
        // the new one is in, and keeps it if the feed does not answer this time.
        val view = Triple(symbol, range, candleSize)
        val fresh = shown == view
        if (!fresh) value = Load.Loading
        shown = view
        val next = withContext(Dispatchers.IO) { com.ataglanceplus.live.FinanceSource.history(context, symbol, range, vwapSettings, candleSize) }
        value = next?.let { Load.Ready(it) }
            ?: (value.takeIf { fresh && it is Load.Ready } ?: Load.Missing("Yahoo Finance did not answer. Try again in a moment."))
        refresh.loaded = refresh.generation
    }
    // The user's eToro positions in this stock, for "What if" (refreshed when over a minute old).
    val etoro by produceState<com.ataglanceplus.live.EtoroSource.Holding?>(null, symbol, refresh.generation, tick) {
        value = withContext(Dispatchers.IO) {
            val source = com.ataglanceplus.live.EtoroSource
            val current = source.portfolio.value
            // Kept as fresh as the price, so "What if" measures from a price and a P/L of the same moment.
            if (source.hasKeys(context) && (current == null || System.currentTimeMillis() - current.at > LIVE_REFRESH_MS)) source.refresh(context)
            val wanted = setOf(symbol.uppercase(), symbol.uppercase().replace('-', '.'))
            source.portfolio.value?.holdings?.firstOrNull { it.symbol?.uppercase() in wanted && it.open.isNotEmpty() }
        }
    }
    ScreenTitle("Stocks")
    if (tickers.size > 1) PillRow(tickers, tickers.indexOf(symbol)) { symbol = tickers[it] }
    when (val state = load) {
        Load.Loading -> Text("Loading…", color = SportSub)
        is Load.Missing -> Text(state.why, color = SportSub)
        is Load.Ready -> {
            val h = state.data
            val q = h.quote
            // The screen's locale, read so prices redraw if the phone's language changes.
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            fun money(v: Double) = String.format(locale, "%,.2f", v)
            val change = h.rangeChange
            val base = q.price - change
            val percent = if (base != 0.0) 100 * change / base else 0.0
            // Outside regular hours the latest price leads, with its move from the close (as eToro
            // shows it); the regular session's figures follow.
            val extended = q.extended
            FinanceHero(
                name = q.name,
                detail = "${q.symbol} · ${h.exchangeName}",
                value = "${money(extended?.price ?: q.price)} ${q.currency}",
                rising = (extended?.change ?: change) >= 0,
            ) {
                if (extended != null) {
                    Text(
                        (if (extended.change >= 0) "▲ +" else "▼ −") + money(kotlin.math.abs(extended.change)) +
                            String.format(locale, " (%+.2f%%) ", extended.changePercent) + extended.session.label.lowercase(locale),
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = if (extended.change >= 0) UP_TEXT else DOWN_TEXT,
                    )
                }
                Text(
                    (if (extended != null) "At the close ${money(q.price)}: " else "") +
                        (if (change >= 0) "▲ +" else "▼ −") + money(kotlin.math.abs(change)) +
                        String.format(locale, " (%+.2f%%) ", percent) + if (range == com.ataglanceplus.live.FinanceSource.Range.DAY) "today" else "over ${range.label}",
                    fontSize = if (extended != null) 13.sp else 15.sp, fontWeight = FontWeight.Medium,
                    color = if (change >= 0) UP_TEXT else DOWN_TEXT,
                )
            }
            // "What if" has a tab of its own, beside the chart, once there are eToro positions in
            // this (US dollar) stock.
            val holding = etoro?.takeIf { it.open.isNotEmpty() && q.currency == "USD" }
            if (holding != null) SegmentedPills(listOf("Overview", "What if"), stockTab) { stockTab = it }
            if (holding != null && stockTab == 1) {
                WhatIfPanels(
                    symbol = q.symbol, holding = holding, now = extended?.price ?: q.price,
                    close = q.price.takeIf { extended != null } ?: q.previousClose, dayHigh = h.dayHigh, money = ::money,
                )
            } else {
            FinancePanel("Price") {
                val ranges = com.ataglanceplus.live.FinanceSource.Range.entries
                SegmentedPills(ranges.map { it.label }, range.ordinal) { range = ranges[it] }
                // Candle size on 1D and 5D: Auto groups them to fit; 5m, 30m and 1h fix it.
                if (range.minutes != null) {
                    val sizes = listOf<Int?>(null, 5, 30, 60)
                    SegmentedPills(listOf("Auto", "5m", "30m", "1h"), sizes.indexOf(candleSize).coerceAtLeast(0)) { candleSize = sizes[it] }
                }
                // Pre-market, after hours and overnight on the 1D and 5D charts, at a tap (it was
                // only in the VWAP dialog, so it was easy to miss that it was off).
                if (range.minutes != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Extended hours", style = MaterialTheme.typography.bodyMedium)
                            Text("Pre-market, after hours and overnight", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = vwapSettings.extendedHours,
                            onCheckedChange = {
                                vwapSettings = vwapSettings.copy(extendedHours = it)
                                Prefs.setFinanceVwap(context, vwapSettings)
                            },
                        )
                    }
                }
                if (h.candles.size >= 2) {
                    val zone = ZoneId.systemDefault()
                    // Dates along the bottom for the longer ranges (the intraday ones label whole hours).
                    val labelPattern = when (range) {
                        com.ataglanceplus.live.FinanceSource.Range.DAY -> null
                        com.ataglanceplus.live.FinanceSource.Range.WEEK -> "EEE"
                        com.ataglanceplus.live.FinanceSource.Range.YEAR -> "MMM"
                        else -> "dMMM"
                    }?.let { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, it), locale) }
                    CandleChart(
                        candles = h.candles,
                        vwap = h.vwap,
                        vwapStarts = h.vwapStarts,
                        minutes = h.minutes,
                        candleMinutes = candleSize?.takeIf { range.minutes != null },
                        viewKey = "$symbol/${range.name}/$candleSize/${vwapSettings.extendedHours}",
                        previousClose = q.previousClose.takeIf { range == com.ataglanceplus.live.FinanceSource.Range.DAY },
                        latest = h.latest,
                        format = { money(it) },
                        dateLabel = { t -> labelPattern?.format(Instant.ofEpochMilli(t).atZone(zone)) ?: clock(context, Instant.ofEpochMilli(t)) },
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
            FinancePanel("Today") {
                Stat("Previous close", money(q.previousClose))
                if (h.dayLow != null && h.dayHigh != null) Stat("Day's range", "${money(h.dayLow)} – ${money(h.dayHigh)}")
                if (h.yearLow != null && h.yearHigh != null) Stat("52-week range", "${money(h.yearLow)} – ${money(h.yearHigh)}")
                h.volume?.let { Stat("Volume", numbers.format(it)) }
            }
            OpenButton("Open in Google Finance", Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.ataglanceplus.live.FinanceSource.googleFinanceUrl(q))))
            }
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
internal fun VwapDialog(
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
                            "1D and 5D charts of US stocks include pre-market and after-hours trading (Yahoo's prices) and the overnight session (eToro's, as Yahoo has none), as in eToro's 24/5 charts. The VWAP leaves out candles with no reported volume.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(checked = settings.extendedHours, onCheckedChange = { onChange(settings.copy(extendedHours = it)) })
                }
            }
        },
    )
}

// --- What if (the user's eToro positions in this stock, at a chosen price) ---

/** A preset price on the "What if" panel: its label, price and colour, and whether it is one of the user's targets. */
private class WhatIfPreset(val label: String, val price: Double, val colour: Color, val target: Boolean = false)

private val TARGET_BLUE = Color(0xFF8AB4F8)

/**
 * The user's eToro positions in this stock at a price they choose (see [com.ataglanceplus.live.WhatIf]):
 * a slider and ±1¢/±10¢ steps, presets (now, break-even, the biggest position's break-even, the
 * close, the day's high and the user's own targets, kept per stock), the totals with what each
 * cent and dollar is worth, and each position. [now] is the price eToro's figures belong to.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WhatIfPanels(
    symbol: String,
    holding: com.ataglanceplus.live.EtoroSource.Holding,
    now: Double,
    close: Double?,
    dayHigh: Double?,
    money: (Double) -> String,
) {
    val context = LocalContext.current
    val positions = holding.open
    if (positions.isEmpty()) return
    val WhatIf = com.ataglanceplus.live.WhatIf
    var targets by remember(symbol) { mutableStateOf(Prefs.financeTargets(context, symbol)) }
    // The chosen price; until the user picks one (or after "Now"), it follows the live price.
    var chosen by remember(symbol) { mutableStateOf<Double?>(null) }
    val price = chosen ?: now
    var adding by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun cents(v: Double) = kotlin.math.round(v * 100) / 100

    val breakEven = WhatIf.breakEven(positions, now)
    val biggest = positions.maxByOrNull { it.amount }?.takeIf { positions.size > 1 }?.let { WhatIf.breakEven(listOf(it), now) }
    val presets = buildList {
        add(WhatIfPreset("Now", now, Color.White))
        breakEven?.let { add(WhatIfPreset("Break-even", it, UP_TEXT)) }
        biggest?.let { add(WhatIfPreset("Biggest B/E", it, Color.White)) }
        targets.forEach { add(WhatIfPreset("My target", it, TARGET_BLUE, target = true)) }
        close?.takeIf { kotlin.math.abs(it - now) >= 0.005 }?.let { add(WhatIfPreset("Close", it, Color.White)) }
        dayHigh?.let { add(WhatIfPreset("Day high", it, Color.White)) }
    }
    // The slider spans the presets with room either side, its ends on a round step (1, 2, 2.5
    // or 5 times a power of ten) so the scale under it reads in round prices.
    val rawLow = (presets.minOf { it.price } * 0.7).coerceAtLeast(0.01)
    val rawHigh = presets.maxOf { it.price } * 1.3
    val step = run {
        val rough = (rawHigh - rawLow) / 4
        val power = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(rough)))
        listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * power }.first { it >= rough }
    }
    val low = (kotlin.math.floor(rawLow / step) * step).coerceAtLeast(0.0)
    val high = low + 4 * step
    val result = WhatIf.at(positions, now, price)
    val exposure = WhatIf.exposure(positions)

    FinancePanel("What if") {
        Text(
            "Your ${positions.size} eToro position${if (positions.size == 1) "" else "s"} in ${holding.symbol ?: symbol}. Drag, step or tap a price.",
            color = SportSub, fontSize = 12.5.sp, lineHeight = 17.sp,
        )
        Row {
            Text(
                "$" + money(price), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.alignByBaseline().clickable { typing = true },
            )
            Text(if (chosen == null) " now" else " if", color = SportSub, fontSize = 13.sp, modifier = Modifier.alignByBaseline().weight(1f))
            val move = price - now
            if (chosen != null && price != now) {
                Text(
                    (if (move >= 0) "▲ " else "▼ ") + money(kotlin.math.abs(move)) + String.format(locale, " (%+.2f%%)", 100 * move / now),
                    color = if (move >= 0) UP_TEXT else DOWN_TEXT, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-0.10 to "−10¢", -0.01 to "−1¢", 0.01 to "+1¢", 0.10 to "+10¢").forEach { (step, label) ->
                Text(
                    label, textAlign = TextAlign.Center, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = when { step <= -0.1 -> DOWN_TEXT; step >= 0.1 -> UP_TEXT; else -> Color.White },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(SportCard)
                        .clickable { chosen = cents((price + step).coerceAtLeast(0.01)) }.padding(vertical = 8.dp),
                )
            }
        }
        androidx.compose.material3.Slider(
            value = price.coerceIn(low, high).toFloat(),
            onValueChange = { chosen = cents(it.toDouble()) },
            valueRange = low.toFloat()..high.toFloat(),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = SportAccent, activeTrackColor = SportAccent, inactiveTrackColor = Color(0xFF2A303D)),
        )
        val wholeSteps = kotlin.math.abs(step - kotlin.math.round(step)) < 1e-9 && kotlin.math.abs(low - kotlin.math.round(low)) < 1e-9
        SliderScale((0..4).map { k -> val v = low + (high - low) * k / 4; if (wholeSteps) String.format(locale, "$%,.0f", v) else "$" + money(v) })
        // Presets, four to a row, then a place to add a target. A long press removes a target.
        (presets.map<WhatIfPreset, WhatIfPreset?> { it } + null).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                row.forEach { preset ->
                    val on = preset != null && kotlin.math.abs(preset.price - price) < 0.005
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(SportCard)
                            .then(if (on) Modifier.border(1.5.dp, SportAccent, RoundedCornerShape(12.dp)) else Modifier)
                            .then(Modifier.combinedClickable(
                                onClick = { if (preset == null) adding = true else chosen = if (preset.label == "Now") null else cents(preset.price) },
                                onLongClick = {
                                    if (preset?.target == true) {
                                        targets = targets - preset.price
                                        Prefs.setFinanceTargets(context, symbol, targets)
                                    }
                                },
                            )),
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        // Label over price, each kept to one line and shrunk to fit the tile.
                        val (label, value, colour) = if (preset == null) Triple("Target", "+ Add", SportAccent)
                            else Triple(preset.label, "$" + money(preset.price), preset.colour)
                        Text(
                            label, color = SportSub, maxLines = 1, textAlign = TextAlign.Center,
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.5.sp, stepSize = 0.5.sp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                        Text(
                            value, color = colour, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center,
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportCard).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WhatIfRow("Value", "$" + money(result.value), Color.White, big = true)
            WhatIfRow("Profit/loss", (if (result.pnl >= 0) "▲ " else "▼ ") + money(kotlin.math.abs(result.pnl)) + String.format(locale, " (%+.2f%%)", result.percent),
                if (result.pnl >= 0) UP_TEXT else DOWN_TEXT)
            WhatIfRow("Units", String.format(locale, "%,.2f", positions.sumOf { it.units }), Color.White)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Each 1¢: ±$" + money(kotlin.math.abs(exposure) / 100), color = SportSub, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                Text("Each $1: ±$" + money(kotlin.math.abs(exposure)), color = SportSub, fontSize = 11.5.sp)
            }
        }
    }
    FinancePanel("Your positions") {
        Text("Units @ open price, and value and P/L at the price above", color = SportSub, fontSize = 12.5.sp, lineHeight = 17.sp)
        val dates = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
        result.legs.sortedByDescending { it.position.openedAt ?: 0L }.forEachIndexed { i, leg ->
            val p = leg.position
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SportBorder))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.isBuy) "BUY" else "SELL", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    listOfNotNull(
                        when (p.real) { true -> "Real"; false -> "CFD"; null -> null },
                        "×${p.leverage}".takeIf { p.leverage > 1 },
                        p.openedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dates) },
                    ).forEach { tag ->
                        Text(tag, color = Color(0xFFCFD6E2), fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("$" + money(leg.value), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row {
                    // Units at the open price, the same size on every row (one line; "…" only if it cannot fit).
                    Text(String.format(locale, "%,.2f @ %s", p.units, p.openRate.toBigDecimal().stripTrailingZeros().toPlainString()),
                        color = SportSub, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp).alignByBaseline())
                    Text((if (leg.pnl >= 0) "▲ " else "▼ ") + money(kotlin.math.abs(leg.pnl)) + String.format(locale, " (%+.2f%%)", leg.percent),
                        color = if (leg.pnl >= 0) UP_TEXT else DOWN_TEXT, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
    if (adding || typing) {
        PriceDialog(
            title = if (adding) "Add a target" else "Price",
            initial = if (adding) "" else String.format(Locale.ROOT, "%.2f", price),
            onDismiss = { adding = false; typing = false },
        ) { value ->
            if (adding) {
                targets = (targets + cents(value)).distinct().sorted()
                Prefs.setFinanceTargets(context, symbol, targets)
            }
            chosen = cents(value)
            adding = false
            typing = false
        }
    }
}

/**
 * The prices under the slider, each centred under its place on the track (the ends kept inside
 * the panel), rather than spread evenly, which put the middle ones off their marks.
 */
@Composable
private fun SliderScale(labels: List<String>) {
    androidx.compose.ui.layout.Layout(
        content = { labels.forEach { Text(it, color = SportSub, fontSize = 10.5.sp, maxLines = 1) } },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        // The track runs between the centres of the thumb at its ends (the thumb is 4dp wide).
        val inset = 2.dp.roundToPx()
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { k, p ->
                val centre = inset + (width - 2 * inset) * k / (labels.size - 1).coerceAtLeast(1)
                p.placeRelative((centre - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

@Composable
private fun WhatIfRow(label: String, value: String, colour: Color, big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SportSub, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = colour, fontSize = if (big) 18.sp else 14.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Asks for a price (a target, or the "What if" price typed in). */
@Composable
private fun PriceDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, prefix = { Text("$") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onDone) }, enabled = value != null) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How often the stock screen reads the price again while it is open and in front. */
private const val LIVE_REFRESH_MS = 15_000L

/** Up and down as text on the dark screen: the same hues, lifted to read on it. */
private val UP_TEXT = Color(0xFF5BD35B)
private val DOWN_TEXT = Color(0xFFFF7B72)

/**
 * The stock and eToro dashboards' frame: the sports dashboards' dark screen and type, with
 * Material parts (dialogs, buttons, the charts' greys) taking the same colours.
 */
@Composable
private fun FinanceScreen(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val scheme = androidx.compose.material3.darkColorScheme(
        primary = SportAccent, onPrimary = Color(0xFF0A2A5C), secondaryContainer = SportHigh, onSecondaryContainer = Color.White,
        background = SportBg, onBackground = SportText, surface = SportBg, onSurface = SportText, onSurfaceVariant = SportSub,
        surfaceContainerLowest = SportBg, surfaceContainerLow = SportCard, surfaceContainer = SportCard,
        surfaceContainerHigh = SportHigh, surfaceContainerHighest = SportHigh,
        outline = Color(0x40FFFFFF), outlineVariant = Color(0x24FFFFFF), error = Color(0xFFFF8A80),
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SportText) {
            Column(
                modifier = Modifier.fillMaxSize().background(SportBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/** The headline: what it is, the value large, then its moves, on a wash of green or red. */
@Composable
private fun FinanceHero(name: String, detail: String, value: String, rising: Boolean, moves: @Composable () -> Unit) {
    val tone = if (rising) UP else DOWN
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(tone.copy(alpha = 0.22f), SportHigh)))
            .border(1.dp, SportBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = SportSub, fontSize = 12.5.sp)
        Text(value, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        moves()
    }
}

/** A titled panel, as the sports dashboards draw theirs. */
@Composable
private fun FinancePanel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SportHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Pills to choose one of many (the user's stocks): equal pills while four fit, else a row that scrolls. */
@Composable
private fun PillRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (items.size <= 4) {
        SegmentedPills(items, selected, onSelect)
        return
    }
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            val on = index == selected
            Text(
                item, color = if (on) Color.White else SportSub, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) SportHigh else SportCard)
                    .border(1.dp, if (on) Color(0x33FFFFFF) else SportBorder, RoundedCornerShape(12.dp))
                    .clickable { onSelect(index) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** VWAP's line: the first categorical blue, in its dark-theme step (the stock screen is always dark). */
private val VWAP_DARK = Color(0xFF3987E5)

/**
 * Candles over time, filled green for a rise (close at or above open) and red for a fall, with
 * wicks from low to high, and the VWAP as a 2dp blue line (broken where it starts again). Scaled
 * to the low and high of the candles on screen (marked at the left). A legend names the three
 * marks. Touch: tap or drag along the chart to read a candle (its open, high, low, close and VWAP
 * show above); pinch to zoom in and out, two fingers to move along; "Reset zoom" shows it all.
 */
/** The stock chart's candles (its "Clean" look): a softer green and red than the other charts'. */
private val CANDLE_UP = Color(0xFF2EBD85)
private val CANDLE_DOWN = Color(0xFFF6465D)
/** The overnight session's tint, band and price tag. */
private val OVERNIGHT = Color(0xFF8E7CFF)
private val READOUT_BG = Color(0xFF232836)
private val TAG_GREY = Color(0xFF3A404D)

/** Candles drawn as one: indices [from] to [to] of the chart's candles, from [time] to [end]. */
private class Bar(
    val from: Int, val to: Int, val time: Long, val end: Long,
    val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long,
    val session: com.ataglanceplus.live.FinanceSource.Session,
)

/**
 * Candles [first] to [last] as bars: each its own, or, with [stepMs], those in the same clock
 * step (15 minutes, an hour…) and session together.
 */
private fun bars(candles: List<com.ataglanceplus.live.FinanceSource.Candle>, first: Int, last: Int, baseMs: Long?, stepMs: Long?): List<Bar> {
    val out = mutableListOf<Bar>()
    var i = first
    while (i <= last) {
        val c = candles[i]
        var j = i
        if (stepMs != null) {
            val bucket = Math.floorDiv(c.time, stepMs)
            while (j + 1 <= last && candles[j + 1].session == c.session && Math.floorDiv(candles[j + 1].time, stepMs) == bucket) j++
        }
        val group = candles.subList(i, j + 1)
        val start = if (stepMs != null) Math.floorDiv(c.time, stepMs) * stepMs else c.time
        out += Bar(
            i, j, start, if (stepMs != null) start + stepMs else c.time + (baseMs ?: 0L),
            c.open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close, group.sumOf { it.volume }, c.session,
        )
        i = j + 1
    }
    return out
}

/**
 * The stock's candle chart: candles (grouped into 15 minutes, an hour… until zoomed in far
 * enough to show each), the VWAP, the previous close ([previousClose], one day), a tag on the
 * latest price, volume, and for US listings with extended hours the pre-market, after-hours and
 * overnight sessions shaded and named. Prices on a scale at the right; times along the bottom at
 * whole hours ([minutes] is the candles' step for the intraday ranges; others show dates). A
 * finger reads a candle (above the chart); two zoom and move along.
 */
@Composable
private fun CandleChart(
    candles: List<com.ataglanceplus.live.FinanceSource.Candle>,
    vwap: List<Double?>,
    vwapStarts: Set<Int>,
    minutes: Int?,
    /** The user's chosen candle size in minutes, or null to group automatically. */
    candleMinutes: Int?,
    /** Names the view, so a refresh keeps its zoom and chosen candle and a new view starts afresh. */
    viewKey: String,
    previousClose: Double?,
    latest: Double,
    format: (Double) -> String,
    dateLabel: (Long) -> String,
) {
    if (candles.isEmpty()) return
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // Kept across refreshes of the same view: the zoom (following new candles when it shows the
    // latest) and the chosen candle (moving on to the newest when it was on the latest).
    val firstTime = candles.firstOrNull()?.time
    var selected by remember(viewKey, firstTime) { mutableIntStateOf(candles.lastIndex) }
    var lastSeen by remember(viewKey, firstTime) { mutableIntStateOf(candles.lastIndex) }
    val window = remember(viewKey, firstTime) { ChartWindow(candles.size.toFloat(), minOf(12f, candles.size.toFloat())) }
    if (lastSeen != candles.lastIndex || window.total != candles.size.toFloat()) {
        SideEffect {
            if (selected == lastSeen || selected > candles.lastIndex) selected = candles.lastIndex
            lastSeen = candles.lastIndex
            window.resize(candles.size.toFloat())
        }
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = muted)
    val first = window.start.toInt().coerceIn(0, candles.lastIndex)
    val last = kotlin.math.ceil(window.start + window.count - 1).toInt().coerceIn(first, candles.lastIndex)
    val hasOvernight = candles.any { it.session == Session.OVERNIGHT }
    val axisWidth = 46.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(CANDLE_UP, "Up", line = false)
            LegendKey(CANDLE_DOWN, "Down", line = false)
            LegendKey(VWAP_DARK, "VWAP", line = true)
            if (previousClose != null) LegendKey(Color(0xFF9AA3B2), "Prev close", line = true)
            if (hasOvernight) LegendKey(OVERNIGHT, "Overnight", line = false)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // A little room after the last candle, before the price scale.
            val plotWidth = (maxWidth - axisWidth).value
            val room = 14f
            val pickScale = plotWidth / (plotWidth - room)
            // Group the candles until each bar is at least 3.5dp wide (a whole 1D chart with
            // extended hours shows 15-minute bars; zoomed in, the 5-minute candles).
            val perCandle = (plotWidth - room) / window.count
            val stepMs = minutes?.let { m ->
                val step = candleMinutes?.takeIf { it >= m && it % m == 0 }
                    ?: listOf(5, 10, 15, 30, 60, 120, 240).filter { it >= m && it % m == 0 }.firstOrNull { perCandle * (it / m) >= 3.5f } ?: 240
                if (step == m) null else step * 60_000L
            }
            val shown = bars(candles, first, last, minutes?.let { it * 60_000L }, stepMs)
            val chosen = shown.firstOrNull { selected in it.from..it.to } ?: shown.last()
            val lastBar = candles.lastIndex in first..last
            // The scale: the bars and the VWAP on screen, the latest price, and the previous close when it is near.
            val averages = (first..last).mapNotNull { vwap.getOrNull(it) }
            var low = minOf(shown.minOf { it.low }, averages.minOrNull() ?: Double.MAX_VALUE)
            var high = maxOf(shown.maxOf { it.high }, averages.maxOrNull() ?: -Double.MAX_VALUE)
            if (lastBar) { low = minOf(low, latest); high = maxOf(high, latest) }
            if (previousClose != null && previousClose > low - (high - low) * 0.3 && previousClose < high + (high - low) * 0.3) {
                low = minOf(low, previousClose); high = maxOf(high, previousClose)
            }
            if (high - low < 1e-9) { high += 0.5; low -= 0.5 }
            val pad = (high - low) * 0.07
            low -= pad
            high += pad
            val reference = previousClose ?: candles.first().open
            val tagColour = when {
                candles.last().session == Session.OVERNIGHT -> OVERNIGHT
                latest >= reference -> CANDLE_UP
                else -> CANDLE_DOWN
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Readout(chosen, vwap.getOrNull(chosen.to), stepMs != null, minutes != null, format, zone, locale, context)
                Row(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .chartGestures(candles, window) { fraction -> selected = window.at((fraction * pickScale).coerceAtMost(1f)).toInt().coerceIn(0, candles.lastIndex) }
                    ) {
                        val top = 14.dp.toPx()
                        val plot = 176.dp.toPx()
                        val volTop = top + plot + 6.dp.toPx()
                        val volH = 28.dp.toPx()
                        val slot = (size.width - room.dp.toPx()) / window.count
                        fun xi(i: Int) = (i + 0.5f - window.start) * slot
                        fun xb(b: Bar) = (xi(b.from) + xi(b.to)) / 2
                        fun y(v: Double) = top + (1 - ((v - low) / (high - low)).toFloat()) * plot
                        // Sessions: shaded outside regular hours, each named above the chart.
                        val runs = mutableListOf<Pair<Int, Int>>()
                        shown.forEachIndexed { k, b -> if (k == 0 || b.session != shown[k - 1].session) runs += k to k else runs[runs.lastIndex] = runs.last().first to k }
                        val banded = shown.any { it.session != Session.REGULAR }
                        runs.forEachIndexed { r, (a, z) ->
                            val left = xi(shown[a].from) - slot / 2
                            val right = xi(shown[z].to) + slot / 2
                            val session = shown[a].session
                            if (session != Session.REGULAR) {
                                drawRect(
                                    if (session == Session.OVERNIGHT) OVERNIGHT.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.035f),
                                    topLeft = Offset(left, top), size = Size(right - left, volTop + volH - top),
                                )
                            }
                            if (r > 0) drawLine(Color.White.copy(alpha = 0.12f), Offset(left, top), Offset(left, volTop + volH), strokeWidth = 1.dp.toPx())
                            if (banded) {
                                val name = measurer.measure(session.label, small.copy(color = if (session == Session.OVERNIGHT) Color(0xFFC9BFFF) else muted))
                                if (name.size.width < right - left - 4.dp.toPx()) drawText(name, topLeft = Offset(maxOf(left, 0f) + 4.dp.toPx(), 0f))
                            }
                        }
                        // Grid.
                        for (k in 0..3) {
                            val yy = y(low + (high - low) * (0.1 + 0.8 * k / 3))
                            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                        }
                        // The previous close, dashed, and the latest price, dotted, across the chart.
                        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                        if (previousClose != null && previousClose in low..high) {
                            drawLine(Color(0xFF9AA3B2), Offset(0f, y(previousClose)), Offset(size.width, y(previousClose)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                        }
                        if (latest in low..high) {
                            drawLine(tagColour.copy(alpha = 0.8f), Offset(0f, y(latest)), Offset(size.width, y(latest)), strokeWidth = 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                        }
                        // Volume: each feed on its own scale (eToro's overnight volume is not the exchange's).
                        val maxDay = shown.filter { it.session != Session.OVERNIGHT }.maxOfOrNull { it.volume }?.coerceAtLeast(1) ?: 1
                        val maxNight = shown.filter { it.session == Session.OVERNIGHT }.maxOfOrNull { it.volume }?.coerceAtLeast(1) ?: 1
                        val bodyWidth = { b: Bar -> ((b.to - b.from + 1) * slot * 0.62f).coerceAtLeast(1.2.dp.toPx()) }
                        shown.forEach { b ->
                            if (b.volume <= 0) return@forEach
                            val h = volH * b.volume / (if (b.session == Session.OVERNIGHT) maxNight else maxDay).toFloat()
                            val w = bodyWidth(b)
                            drawRoundRect((if (b.close >= b.open) CANDLE_UP else CANDLE_DOWN).copy(alpha = 0.35f), Offset(xb(b) - w / 2, volTop + volH - h), Size(w, h), CornerRadius(minOf(1.5.dp.toPx(), w / 4)))
                        }
                        // Candles: a light wick and a rounded body.
                        shown.forEach { b ->
                            val colour = if (b.close >= b.open) CANDLE_UP else CANDLE_DOWN
                            val x = xb(b)
                            drawLine(colour.copy(alpha = 0.7f), Offset(x, y(b.high)), Offset(x, y(b.low)), strokeWidth = 1.dp.toPx())
                            val w = bodyWidth(b)
                            val t = y(maxOf(b.open, b.close))
                            val h = (y(minOf(b.open, b.close)) - t).coerceAtLeast(1.2.dp.toPx())
                            drawRoundRect(colour, Offset(x - w / 2, t), Size(w, h), CornerRadius(minOf(1.6.dp.toPx(), w / 4)))
                        }
                        // VWAP, broken where it starts again or has no value.
                        val path = Path()
                        var drawing = false
                        for (i in (first - 1).coerceAtLeast(0)..minOf(last + 1, vwap.lastIndex)) {
                            val v = vwap[i]
                            if (v == null || i in vwapStarts) drawing = false
                            if (v == null) continue
                            if (drawing) path.lineTo(xi(i), y(v)) else path.moveTo(xi(i), y(v))
                            drawing = true
                        }
                        drawPath(path, VWAP_DARK, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        // The chosen bar: a guide through it and a ring at its close.
                        run {
                            val x = xb(chosen)
                            drawLine(Color.White.copy(alpha = 0.45f), Offset(x, top), Offset(x, volTop + volH), strokeWidth = 1.dp.toPx())
                            drawCircle(Color.White, 4.dp.toPx(), Offset(x, y(chosen.close)))
                            drawCircle(if (chosen.close >= chosen.open) CANDLE_UP else CANDLE_DOWN, 2.5.dp.toPx(), Offset(x, y(chosen.close)))
                        }
                        // Times along the bottom: whole hours (with the day where it changes) for the
                        // intraday ranges, else four dates.
                        val labelY = volTop + volH + 3.dp.toPx()
                        val edge = 16.dp.toPx()
                        fun label(text: String, x: Float) {
                            val m = measurer.measure(text, small)
                            drawText(m, topLeft = Offset((x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), labelY))
                        }
                        if (minutes != null) {
                            val local = (first..last).map { java.time.Instant.ofEpochMilli(candles[it].time).atZone(zone) }
                            val gap = 46.dp.toPx()
                            val hourly = listOf(1, 2, 3, 4, 6).firstOrNull { step ->
                                val xs = local.indices.filter { local[it].minute == 0 && local[it].hour % step == 0 }.map { xi(first + it) }
                                xs.zipWithNext().all { (a, b) -> b - a >= gap }
                            }
                            val ticks = if (hourly != null) local.indices.filter { local[it].minute == 0 && local[it].hour % hourly == 0 }
                            else local.indices.filter { it == 0 || local[it].toLocalDate() != local[it - 1].toLocalDate() }
                            val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h a"
                            var lastX = -gap
                            var lastDate: java.time.LocalDate? = null
                            ticks.forEach { k ->
                                val x = xi(first + k)
                                if (x < edge || x > size.width - edge || x - lastX < gap) return@forEach
                                val at = local[k]
                                val day = at.format(DateTimeFormatter.ofPattern("EEE", locale))
                                val text = if (hourly == null) day
                                else (if (lastDate != null && at.toLocalDate() != lastDate) "$day " else "") + at.format(DateTimeFormatter.ofPattern(pattern, locale))
                                label(text, x)
                                lastX = x
                                lastDate = at.toLocalDate()
                            }
                        } else {
                            val visible = (last - first).coerceAtLeast(1)
                            listOf(0, visible / 3, 2 * visible / 3, visible).distinct().forEach { k -> label(dateLabel(candles[first + k].time), xi(first + k)) }
                        }
                    }
                    // The price scale, with tags for the previous close and the latest price.
                    Canvas(modifier = Modifier.width(axisWidth).fillMaxHeight()) {
                        val top = 14.dp.toPx()
                        val plot = 176.dp.toPx()
                        fun y(v: Double) = top + (1 - ((v - low) / (high - low)).toFloat()) * plot
                        val tags = listOfNotNull(previousClose?.takeIf { it in low..high }?.let { y(it) }, latest.takeIf { it in low..high }?.let { y(it) })
                        for (k in 0..3) {
                            val v = low + (high - low) * (0.1 + 0.8 * k / 3)
                            val yy = y(v)
                            if (tags.all { kotlin.math.abs(it - yy) > 14.dp.toPx() }) {
                                val m = measurer.measure(format(v), small)
                                drawText(m, topLeft = Offset(6.dp.toPx(), yy - m.size.height / 2f))
                            }
                        }
                        fun tag(v: Double, colour: Color, bold: Boolean) {
                            val m = measurer.measure(format(v), small.copy(color = Color.White, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal))
                            val h = 16.dp.toPx()
                            drawRoundRect(colour, Offset(2.dp.toPx(), y(v) - h / 2), Size(size.width - 2.dp.toPx(), h), CornerRadius(5.dp.toPx()))
                            drawText(m, topLeft = Offset(6.dp.toPx(), y(v) - m.size.height / 2f))
                        }
                        if (previousClose != null && previousClose in low..high) tag(previousClose, TAG_GREY, bold = false)
                        if (latest in low..high) tag(latest, tagColour, bold = true)
                    }
                }
            }
        }
        ZoomHint(window)
    }
}

/** The chosen bar's figures, above the chart: when, its session, and open, high, low, close and VWAP. */
@Composable
private fun Readout(
    bar: Bar, vwap: Double?, grouped: Boolean, intraday: Boolean, format: (Double) -> String,
    zone: ZoneId, locale: Locale, context: Context,
) {
    val start = Instant.ofEpochMilli(bar.time)
    val time = when {
        !intraday -> dayAndDate(start.atZone(zone).toLocalDate())
        grouped -> "${dayAndDate(start.atZone(zone).toLocalDate())} ${clock(context, start)}–${clock(context, Instant.ofEpochMilli(bar.end))}"
        else -> "${dayAndDate(start.atZone(zone).toLocalDate())} ${clock(context, start)}"
    }
    Surface(color = READOUT_BG, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                if (intraday) {
                    val night = bar.session == com.ataglanceplus.live.FinanceSource.Session.OVERNIGHT
                    Text(
                        bar.session.label, fontSize = 10.5.sp,
                        color = if (night) Color(0xFFC9BFFF) else Color(0xFFCFD6E2),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (night) OVERNIGHT.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Row {
                listOfNotNull("Open" to bar.open, "High" to bar.high, "Low" to bar.low, "Close" to bar.close, vwap?.let { "VWAP" to it }).forEach { (name, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(format(value), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                    }
                }
            }
            // The shares traded in this candle (or bar of candles). Yahoo reports none for most
            // pre-market and after-hours candles; overnight it is eToro's own, not the exchange's.
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Volume", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                Text(
                    if (bar.volume > 0) java.text.NumberFormat.getIntegerInstance(locale).format(bar.volume) + " shares" else "None reported",
                    fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    color = if (bar.volume > 0) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bar.volume > 0) {
                    Text(
                        if (bar.session == com.ataglanceplus.live.FinanceSource.Session.OVERNIGHT) " · ${compactShares(bar.volume, locale)} on eToro" else " · ${compactShares(bar.volume, locale)}",
                        fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A share count in short form: 950, 12.4K, 1.24M, 3.1B. */
private fun compactShares(shares: Long, locale: Locale): String {
    fun short(value: Double, suffix: String): String =
        String.format(locale, if (value < 10) "%.2f" else if (value < 100) "%.1f" else "%.0f", value) + suffix
    return when {
        shares >= 999_500_000L -> short(shares / 1e9, "B")
        shares >= 999_500L -> short(shares / 1e6, "M")
        shares >= 10_000L -> short(shares / 1e3, "K")
        else -> java.text.NumberFormat.getIntegerInstance(locale).format(shares)
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
    ScreenTitle("eToro")
    when (val state = load) {
        Load.Loading -> Text("Loading…", color = SportSub)
        is Load.Missing -> Text(state.why, color = SportSub)
        is Load.Ready -> {
            val (p, history) = state.data
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            fun money(v: Double) = (if (v < 0) "−" else "") + "$" + String.format(locale, "%,.2f", kotlin.math.abs(v))
            fun change(amount: Double, percent: Double) =
                (if (amount >= 0) "▲ +" else "▼ −") + "$" + String.format(locale, "%,.2f", kotlin.math.abs(amount)) + String.format(locale, " (%+.2f%%)", percent)
            FinanceHero(name = "Portfolio", detail = "Updated ${clock(context, Instant.ofEpochMilli(p.at))}", value = money(p.equity), rising = p.pnl >= 0) {
                Text("${change(p.pnl, p.pnlPercent)} on open positions", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (p.pnl >= 0) UP_TEXT else DOWN_TEXT)
            }
            FinancePanel("Summary") {
                Stat("Invested", money(p.invested))
                Stat("Profit/loss", (if (p.pnl >= 0) "+" else "") + money(p.pnl))
                Stat("Available cash", money(p.cash))
                Stat("Updated", clock(context, Instant.ofEpochMilli(p.at)))
            }

            FinancePanel("Value over time") {
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
                        zoomable = true,
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
            FinancePanel("Allocation") {
                val cash = EtoroRow("Cash", null, p.cash.coerceAtLeast(0.0), 0.0, isCash = true)
                AllocationBars((rows + cash).filter { it.value > 0 }.sortedByDescending { it.value }, locale)
            }

            FinancePanel("Positions") {
                SegmentedPills(EtoroSort.entries.map { it.label }, sort.ordinal) { sort = EtoroSort.entries[it] }
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
    AxisLabelsAt(axis.map { (i, label) -> (i + 0.5f) / count to label })
}

/**
 * The part of a chart on screen, for pinch-to-zoom: [start] and [count] in the chart's own units
 * (candles, or a 0–1 share of the time span), never fewer than [minVisible] or more than [total].
 */
private class ChartWindow(initialTotal: Float, private val minVisible: Float) {
    var total by androidx.compose.runtime.mutableFloatStateOf(initialTotal)
        private set
    var start by androidx.compose.runtime.mutableFloatStateOf(0f)
    var count by androidx.compose.runtime.mutableFloatStateOf(initialTotal)
    val zoomed: Boolean get() = count < total * 0.999f

    /**
     * New data has [newTotal] items: the whole range stays whole; zoomed in, the view stays where
     * it is, or moves along with the newest when it was showing them.
     */
    fun resize(newTotal: Float) {
        if (newTotal == total) return
        if (!zoomed) {
            total = newTotal
            start = 0f
            count = newTotal
            return
        }
        val atEnd = start + count >= total - 0.5f
        val grown = newTotal - total
        total = newTotal
        count = count.coerceAtMost(newTotal)
        start = (if (atEnd) start + grown else start).coerceIn(0f, (newTotal - count).coerceAtLeast(0f))
    }

    /** Zooms by [factor] around [at] (a 0–1 share of the width), then moves by [pan] widths. */
    fun transform(factor: Float, at: Float, pan: Float) {
        val anchor = start + at * count
        val next = (count / factor).coerceIn(minOf(minVisible, total), total)
        count = next
        start = (anchor - at * next - pan * next).coerceIn(0f, (total - next).coerceAtLeast(0f))
    }

    fun reset() {
        start = 0f
        count = total
    }

    /** Where [fraction] of the width falls, in the chart's units. */
    fun at(fraction: Float): Float = start + fraction * count
}

/**
 * A chart's touch (restarting when [data] changes): one finger picks (a tap, or a drag along the chart, which keeps the page from
 * scrolling once it is sideways); two fingers pinch to zoom and slide to pan when [window] is given.
 * A mostly up-and-down drag is left to the page, so it still scrolls.
 */
private fun Modifier.chartGestures(data: Any, window: ChartWindow?, onPick: (Float) -> Unit): Modifier = pointerInput(data, window) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPick((down.position.x / size.width).coerceIn(0f, 1f))
        var dx = 0f
        var dy = 0f
        var scrubbing = false
        var scrolling = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (window != null && pressed.size >= 2) {
                window.transform(event.calculateZoom(), event.calculateCentroid(useCurrent = true).x / size.width, event.calculatePan().x / size.width)
                event.changes.forEach { it.consume() }
                // After a pinch, the finger left behind goes on picking.
                scrubbing = true
                scrolling = false
                continue
            }
            if (scrolling) continue
            val change = pressed.first()
            if (!scrubbing) {
                val d = change.positionChange()
                dx += d.x
                dy += d.y
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) scrubbing = true
                else if (kotlin.math.abs(dy) > slop) scrolling = true
            }
            if (scrubbing) {
                onPick((change.position.x / size.width).coerceIn(0f, 1f))
                change.consume()
            }
        }
    }
}

/** "Pinch to zoom" until zoomed, then a way back to the whole range. */
@Composable
private fun ZoomHint(window: ChartWindow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (window.zoomed) "Zoomed in · two fingers to move along" else "Drag to read prices · pinch to zoom",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (window.zoomed) TextButton(onClick = { window.reset() }) { Text("Reset zoom") }
    }
}

/** Axis labels at shares of the width (0 to 1), kept inside the chart at the ends. */
@Composable
private fun AxisLabelsAt(axis: List<Pair<Float, String>>) {
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
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { k, placeable ->
                val centre = axis[k].first * width
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
                if (hidden) RevealBar("Results are hidden for this team.") { revealed = true }
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
    // Results hidden for F1 (Sports settings) until revealed here, for this visit.
    var revealed by rememberSaveable { mutableStateOf(false) }
    val hidden = !revealed && com.ataglanceplus.live.SportsSource.scoresHidden(context, eventId)
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
                    F1SessionScreen(context, session, raceDay, hidden = hidden, onReveal = { revealed = true }) { opened = null }
                    return@Column
                }
                ScreenTitle(first.eventName)
                F1Hero(first)
                if (hidden) RevealBar("Results are hidden for Formula 1.") { revealed = true }
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
                            "Standings" -> if (hidden) Text("The standings are hidden with the results. Reveal them above.", color = SportSub) else F1Standings(standings)
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

/** Results are hidden (Sports settings): says so, with a button to show them for this visit. */
@Composable
private fun RevealBar(text: String, onReveal: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportHigh).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = SportSub, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onReveal) { Text("Reveal") }
    }
}

/** One session of the weekend: live timing while it is on, the results after, a preview before. */
@Composable
private fun F1SessionScreen(context: Context, session: com.ataglanceplus.live.RaceSession, raceDay: java.time.LocalDate, hidden: Boolean, onReveal: () -> Unit, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ${session.eventName.replace("Grand Prix", "GP").trim()}", color = F1Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp))
    }
    ScreenTitle(f1SessionName(session.session))
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            hidden && session.state != "pre" -> RevealBar("Results are hidden for Formula 1.", onReveal)
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
    // Connected only while the screen is in front: leaving the app (it stays in memory) used to
    // keep the live feed streaming in the background for the rest of the session.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            com.ataglanceplus.live.LiveTiming.want(scope, true)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                com.ataglanceplus.live.LiveTiming.want(scope, false)
            }
        }
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
