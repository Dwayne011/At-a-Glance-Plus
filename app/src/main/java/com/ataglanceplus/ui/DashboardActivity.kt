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
import com.ataglanceplus.complications.HealthSource
import com.ataglanceplus.complications.SimpleSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
                            else -> Text("Nothing to show.")
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
        // Axis labels centred under their bars (kept inside the chart at the ends).
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
            val slot = width.toFloat() / values.size
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { k, placeable ->
                    val centre = (axis[k].first + 0.5f) * slot
                    placeable.place((centre - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0)), 0)
                }
            }
        }
    }
}
