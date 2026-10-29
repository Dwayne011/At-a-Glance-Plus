package com.ataglanceplus.ui

import android.Manifest
import android.accounts.AccountManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.ChipSize
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.Chips
import com.ataglanceplus.cards.Complication
import com.ataglanceplus.cards.WeatherLayout
import com.ataglanceplus.complications.ChipValues
import com.ataglanceplus.complications.GmailSource
import com.ataglanceplus.complications.HealthMetric
import com.ataglanceplus.complications.HealthSource
import com.ataglanceplus.complications.MetricGroup
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.complications.SimpleSources
import com.ataglanceplus.live.CalendarSource
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.Wadb
import com.ataglanceplus.weather.WeatherLook
import com.ataglanceplus.weather.WeatherRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something a chip needs before it can show a value. */
private enum class Need(val text: String) {
    CALL_LOG("Needs access to the call log"),
    GMAIL("Needs permission to read Gmail's unread counts"),
    NOTIFICATIONS("Needs notification access"),
    USAGE("Needs usage access"),
    HEALTH("Needs Health Connect access"),
    BLUETOOTH("Needs Nearby devices permission"),
    LOCATION("Uses the weather location: allow location in the Weather section"),
    DATE("Choose a date in its settings"),
}

private fun needs(context: Context, spec: ChipSpec, healthGranted: Set<String>): List<Need> = buildList {
    fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    when (spec.kind) {
        Complication.MISSED_CALLS -> if (!SimpleSources.hasCallLog(context)) add(Need.CALL_LOG)
        Complication.GMAIL -> if (!GmailSource.hasPermission(context)) add(Need.GMAIL)
        Complication.TEXTS, Complication.APP_NOTIFICATIONS -> if (!NotificationCounter.isEnabled(context)) add(Need.NOTIFICATIONS)
        Complication.SCREEN_TIME -> if (!SimpleSources.hasUsageAccess(context)) add(Need.USAGE)
        Complication.STEPS -> if (HealthSource.STEPS !in healthGranted || HealthSource.BACKGROUND !in healthGranted) add(Need.HEALTH)
        Complication.SLEEP -> if (HealthSource.SLEEP !in healthGranted || HealthSource.BACKGROUND !in healthGranted) add(Need.HEALTH)
        Complication.HEALTH -> if (!healthGranted.containsAll(healthPermissions(spec))) add(Need.HEALTH)
        Complication.BUDS -> if (!has(Manifest.permission.BLUETOOTH_CONNECT)) add(Need.BLUETOOTH)
        Complication.SUNSET -> if (!WeatherRepository.hasLocationPermission(context)) add(Need.LOCATION)
        Complication.COUNTDOWN -> if (Prefs.countdownDay(context) == null) add(Need.DATE)
        else -> Unit
    }
}

/** A launchable app: its package and name, for the "Opens" picker. */
private data class AppEntry(val pkg: String, val label: String)

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun appLabel(context: Context, pkg: String?): String? = pkg?.let {
    runCatching { context.packageManager.getApplicationInfo(it, 0).loadLabel(context.packageManager).toString() }.getOrNull()
}

private fun chipTitle(context: Context, spec: ChipSpec): String = when (spec.kind) {
    Complication.GMAIL -> "Gmail · ${spec.arg ?: "choose an account"}"
    Complication.APP_NOTIFICATIONS -> "Notifications · ${appLabel(context, spec.arg) ?: spec.arg ?: "choose an app"}"
    Complication.HEALTH -> HealthMetric.fromId(spec.arg)?.label ?: "Health metric"
    else -> spec.kind.label
}

/** The permissions a Health Connect chip asks for: its own, and reading while the app is in the background. */
private fun healthPermissions(spec: ChipSpec): Set<String> = setOfNotNull(
    when (spec.kind) {
        Complication.STEPS -> HealthSource.STEPS
        Complication.SLEEP -> HealthSource.SLEEP
        Complication.HEALTH -> HealthMetric.fromId(spec.arg)?.permission
        else -> null
    },
    HealthSource.BACKGROUND,
)

/** The chip's icon: a health metric's own, else its kind's. */
private fun chipIcon(spec: ChipSpec): Int =
    if (spec.kind == Complication.HEALTH) HealthMetric.fromId(spec.arg)?.icon ?: spec.kind.icon else spec.kind.icon

/** The kinds offered under "Add a chip", by group. */
private val ADD_GROUPS = listOf(
    "Messages" to listOf(Complication.GMAIL, Complication.MISSED_CALLS, Complication.TEXTS, Complication.APP_NOTIFICATIONS),
    "Health" to listOf(Complication.STEPS, Complication.SLEEP, Complication.HEALTH),
    "Phone" to listOf(Complication.BATTERY, Complication.BUDS, Complication.SCREEN_TIME),
    "Time" to listOf(Complication.ALARM, Complication.COUNTDOWN, Complication.SUNSET),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by ChipValues.values.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()

    // Re-read settings and permissions when settings change or the screen returns.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = Prefs.get(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var healthGranted by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(version) { healthGranted = HealthSource.granted(context) }

    fun changed() {
        Prefs.chipPermissionsChanged(context)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { changed() }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { changed() }
    var picking by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var settingsFor by remember { mutableStateOf<ChipSpec?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pickingMetric by remember { mutableStateOf(false) }
    var showFitting by remember { mutableStateOf(false) }
    var grantMessage by remember { mutableStateOf("") }

    suspend fun grantOverAdb(notifications: Boolean, usage: Boolean, callLog: Boolean) {
        grantMessage = "Granting over wireless debugging…"
        val outcome = Activator.grant(context, Commands.chipGrantSteps(context, notifications, usage, callLog))
        grantMessage = when (outcome) {
            is Activator.Outcome.Failure -> outcome.reason
            Activator.Outcome.Success -> "Done. If the chip still says it needs access, the command log is on the App tab."
        }
        changed()
    }

    val chips = remember(version) { Prefs.chips(context) ?: emptyList() }
    val layout = remember(version) { Prefs.weatherLayout(context) }

    fun add(spec: ChipSpec) {
        if (chips.none { it.key == spec.key } && chips.size < Chips.MAX_CHOSEN) Prefs.setChips(context, chips + spec)
        if (spec.kind == Complication.HEALTH || spec.kind == Complication.STEPS || spec.kind == Complication.SLEEP) {
            if (!healthGranted.containsAll(healthPermissions(spec))) health.launch(healthPermissions(spec))
        }
    }

    // The Gmail chip whose account is being changed; null when adding a new Gmail chip.
    var replacing by remember { mutableStateOf<ChipSpec?>(null) }
    val accountChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val old = replacing
        replacing = null
        result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { account ->
            val spec = ChipSpec(Complication.GMAIL, account)
            if (old != null) {
                Prefs.setChips(context, chips.map { if (it.key == old.key) spec else it }.distinctBy { it.key })
            } else {
                add(spec)
            }
            if (!GmailSource.hasPermission(context)) permissions.launch(arrayOf(GmailSource.PERMISSION))
        }
    }

    fun grant(spec: ChipSpec, need: Need) {
        when (need) {
            Need.CALL_LOG -> permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
            Need.GMAIL -> permissions.launch(arrayOf(GmailSource.PERMISSION))
            Need.BLUETOOTH -> permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            Need.HEALTH -> health.launch(healthPermissions(spec))
            Need.NOTIFICATIONS, Need.USAGE -> scope.launch {
                grantOverAdb(notifications = need == Need.NOTIFICATIONS, usage = need == Need.USAGE, callLog = false)
            }
            Need.LOCATION -> Unit
            Need.DATE -> settingsFor = spec
        }
    }

    Section("Weather card") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherLayout.entries.forEach { option ->
                FilterChip(selected = layout == option, onClick = { Prefs.setWeatherLayout(context, option) }, label = { Text(option.label) })
            }
        }
        Text(
            when (layout) {
                WeatherLayout.COLUMNS -> "Next 3 hours as columns; chips under the temperature and 2 stacked on the right."
                WeatherLayout.SPLIT -> "Next 3 hours in a row; chips under the temperature and 2 wide chips under the forecast."
                WeatherLayout.GRID -> "Next 2 hours in a box; chips under the temperature and a 2 x 2 grid of chips."
                WeatherLayout.NONE -> "Next 3 hours, and no chips anywhere."
            },
            style = MaterialTheme.typography.bodySmall
        )
        Text("Look", style = MaterialTheme.typography.labelLarge)
        val look = remember(version) { Prefs.weatherLook(context) }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            WeatherLook.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = look == option,
                    onClick = { Prefs.setWeatherLook(context, option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = WeatherLook.entries.size)
                ) { Text(if (option == WeatherLook.ANIMATED) "Animated" else option.label, maxLines = 1) }
            }
        }
        Text(
            when (look) {
                WeatherLook.STANDARD -> "A plain background, coloured for the weather."
                WeatherLook.SCENES -> "A picture of the weather and time of day behind the card: sun, stars, clouds, fog, rain or snow."
                WeatherLook.ANIMATED -> "The same scenes, moving: clouds drift, rain and snow fall, stars twinkle, lightning flashes. Uses more battery while the card is on screen."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }

    Section("Your chips") {
        Text(
            "Most important first: drag ≡ to reorder. Chips with something to report fill the weather card, then other cards, then an extra chips card. Tap a chip for its settings.",
            style = MaterialTheme.typography.bodySmall
        )
        if (grantMessage.isNotEmpty()) {
            Text(grantMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (chips.isEmpty()) {
            Text("No chips chosen; the weather card uses the design's examples (Steps, Battery, Alarm and so on).", style = MaterialTheme.typography.bodySmall)
        }
        ReorderableChips(
            chips = chips,
            onReorder = { Prefs.setChips(context, it) },
        ) { spec ->
            val missing = needs(context, spec, healthGranted).firstOrNull()
            val status = when {
                !spec.configured -> "Choose the account to count"
                missing != null -> missing.text
                else -> null
            }
            ChipRow(
                icon = chipIcon(spec),
                title = chipTitle(context, spec),
                value = values[spec.key]?.text,
                status = status,
                onClick = { settingsFor = spec },
            )
        }
        if (chips.size < Chips.MAX_CHOSEN) {
            Button(onClick = { adding = true }) { Text("+ Add a chip") }
        } else {
            Text("Remove a chip to add another (${Chips.MAX_CHOSEN} at most).", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showFitting = !showFitting }) { Text(if (showFitting) "Hide fitting details" else "Fitting details") }
        if (showFitting) {
            Text(
                com.ataglanceplus.cards.WeatherCard.fitReport(context),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (adding) {
        AddChipDialog(
            chosen = chips,
            onPick = { kind ->
                adding = false
                when (kind) {
                    Complication.GMAIL -> {
                        replacing = null
                        accountChooser.launch(chooseGoogleAccount(null))
                    }
                    Complication.APP_NOTIFICATIONS -> picking = { pkg -> add(ChipSpec(Complication.APP_NOTIFICATIONS, pkg)) }
                    Complication.HEALTH -> pickingMetric = true
                    else -> add(ChipSpec(kind))
                }
            },
            onDismiss = { adding = false }
        )
    }
    if (pickingMetric) {
        MetricPickerDialog(
            chosen = chips,
            onPick = { metric ->
                pickingMetric = false
                add(ChipSpec(Complication.HEALTH, metric.id))
            },
            onDismiss = { pickingMetric = false }
        )
    }
    settingsFor?.let { spec ->
        val missing = needs(context, spec, healthGranted).firstOrNull()
        ChipSettingsDialog(
            spec,
            onPickApp = { onPicked -> picking = onPicked },
            onChooseAccount = {
                settingsFor = null
                replacing = spec
                accountChooser.launch(chooseGoogleAccount(spec.arg))
            },
            onDismiss = { settingsFor = null },
            access = {
                val now = values[spec.key]?.text
                Text(
                    when {
                        !spec.configured -> "Choose the Gmail account to count."
                        missing != null -> missing.text + "."
                        now != null -> "Now: $now"
                        else -> "Nothing to report right now."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (spec.kind == Complication.BUDS) {
                    Text("Experimental: shows each connected Bluetooth device that reports its battery (earbuds, headphones, watch).", style = MaterialTheme.typography.bodySmall)
                }
                if (spec.kind == Complication.STEPS || spec.kind == Complication.SLEEP || spec.kind == Complication.HEALTH) {
                    Text("Reads Health Connect. Turn on Health Connect sync in Google Health or Fitbit.", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (missing != null && missing != Need.LOCATION && missing != Need.DATE) {
                        Button(enabled = !busy || missing !in setOf(Need.NOTIFICATIONS, Need.USAGE, Need.CALL_LOG), onClick = { grant(spec, missing) }) { Text("Grant") }
                    }
                    if (spec.kind == Complication.GMAIL && !spec.configured) {
                        Button(onClick = {
                            settingsFor = null
                            replacing = spec
                            accountChooser.launch(chooseGoogleAccount(spec.arg))
                        }) { Text("Choose account") }
                    }
                    // Android blocks notification and usage access for apps installed outside the Play
                    // Store until "Allow restricted settings"; wireless debugging grants them directly.
                    if (missing == Need.NOTIFICATIONS || missing == Need.USAGE) {
                        TextButton(onClick = {
                            val intent = if (missing == Need.NOTIFICATIONS) {
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationCounter.component(context).flattenToString())
                            } else {
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            }
                            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Or open the setting") }
                    }
                    if (missing == Need.CALL_LOG) {
                        TextButton(enabled = !busy, onClick = {
                            scope.launch { grantOverAdb(notifications = false, usage = false, callLog = true) }
                        }) { Text("Grant with wireless debugging") }
                    }
                }
                if (missing == Need.NOTIFICATIONS || missing == Need.USAGE) {
                    Text("Grant uses wireless debugging (Wi-Fi needed).", style = MaterialTheme.typography.bodySmall)
                }
            },
            onRemove = {
                settingsFor = null
                Prefs.setChips(context, chips.filter { it.key != spec.key })
            },
        )
    }
    picking?.let { onPicked ->
        AppPickerDialog(onPick = { pkg -> onPicked(pkg); picking = null }, onDismiss = { picking = null })
    }
}

/** One chip in the list: icon, name, its value now (or what it needs), and a way into its settings. */
@Composable
private fun ChipRow(icon: Int, title: String, value: String?, status: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * The chips in order, each with a ≡ handle: drag it up or down to move the chip. Rows swap as the
 * dragged one passes the middle of its neighbour, and the order is saved when it is let go.
 */
@Composable
private fun ReorderableChips(chips: List<ChipSpec>, onReorder: (List<ChipSpec>) -> Unit, row: @Composable (ChipSpec) -> Unit) {
    var order by remember(chips) { mutableStateOf(chips) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    Column {
        order.forEach { spec ->
            key(spec.key) {
                val lifted = dragging == spec.key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heights[spec.key] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else 0f }
                        .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(12.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .pointerInput(spec.key) {
                                detectDragGestures(
                                    onDragStart = { dragging = spec.key; offset = 0f },
                                    onDragEnd = { dragging = null; offset = 0f; onReorder(order) },
                                    onDragCancel = { dragging = null; offset = 0f; order = chips },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val index = order.indexOfFirst { it.key == spec.key }
                                    val next = order.getOrNull(index + 1)
                                    val previous = order.getOrNull(index - 1)
                                    if (next != null && offset > (heights[next.key] ?: 0) / 2f) {
                                        offset -= heights[next.key] ?: 0
                                        order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    } else if (previous != null && offset < -(heights[previous.key] ?: 0) / 2f) {
                                        offset += heights[previous.key] ?: 0
                                        order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("≡", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(modifier = Modifier.weight(1f)) { row(spec) }
                }
            }
        }
    }
}

/** Every kind of chip, by group; those that can be added once and already are, are left out. */
@Composable
private fun AddChipDialog(chosen: List<ChipSpec>, onPick: (Complication) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a chip") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                ADD_GROUPS.forEach { (group, kinds) ->
                    val offered = kinds.filter { kind -> kind.needsArg || chosen.none { it.kind == kind } }
                    if (offered.isNotEmpty()) {
                        item { Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        items(offered) { kind ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onPick(kind) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(kind.icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (kind == Complication.HEALTH) "Health metric…" else kind.label, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    )
}

/** The health metrics by group, those already added left out. */
@Composable
private fun MetricPickerDialog(chosen: List<ChipSpec>, onPick: (HealthMetric) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Health metric") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item {
                    Text("Only metrics your watch or apps record in Health Connect will show anything.", style = MaterialTheme.typography.bodySmall)
                }
                MetricGroup.entries.forEach { group ->
                    val offered = HealthMetric.entries.filter { m -> m.group == group && chosen.none { it.kind == Complication.HEALTH && it.arg == m.id } }
                    if (offered.isNotEmpty()) {
                        item { Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        items(offered) { metric ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onPick(metric) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(metric.icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(metric.label, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ChipSettingsDialog(
    spec: ChipSpec,
    onPickApp: (((String) -> Unit)) -> Unit,
    onChooseAccount: () -> Unit,
    onDismiss: () -> Unit,
    access: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var opens by remember { mutableStateOf(Prefs.chipApp(context, spec.key)) }
    var labels by remember { mutableStateOf<List<GmailSource.Label>?>(null) }
    var chosenLabels by remember { mutableStateOf(spec.arg?.let { Prefs.gmailLabels(context, it) } ?: emptySet()) }
    var countdown by remember { mutableStateOf(Prefs.countdownDay(context)) }
    var countdownName by remember { mutableStateOf(Prefs.countdownName(context)) }
    var countdownEvent by remember { mutableStateOf(Prefs.countdownEvent(context)) }
    var pickingEvent by remember { mutableStateOf(false) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickingEvent = true
    }
    var size by remember { mutableStateOf(Prefs.chipSize(context, spec.key)) }
    var goal by remember { mutableStateOf(Prefs.stepGoal(context).toString()) }
    if (spec.kind == Complication.GMAIL && spec.arg != null) {
        LaunchedEffect(spec.key) { labels = withContext(Dispatchers.IO) { GmailSource.labels(context, spec.arg) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text(chipTitle(context, spec)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { access() } }
                if (spec.kind == Complication.GMAIL) {
                    item {
                        Text("Gmail account", style = MaterialTheme.typography.labelLarge)
                        Text(spec.arg ?: "None chosen yet")
                        OutlinedButton(onClick = onChooseAccount) { Text("Choose account") }
                    }
                }
                item {
                    Text("When tapped, opens", style = MaterialTheme.typography.labelLarge)
                    Text(
                        appLabel(context, opens) ?: spec.defaultAppName(context)?.let { "$it (default)" } ?: "Its usual app or screen",
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = {
                            onPickApp { pkg ->
                                Prefs.setChipApp(context, spec.key, pkg)
                                opens = pkg
                            }
                        }) { Text("Choose app to open") }
                        if (opens != null) {
                            TextButton(onClick = {
                                Prefs.setChipApp(context, spec.key, null)
                                opens = null
                            }) { Text("Use default") }
                        }
                    }
                }
                if (spec.kind.canGrow) {
                    item {
                        Text("Size", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ChipSize.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = size == option,
                                    onClick = {
                                        Prefs.setChipSize(context, spec.key, option)
                                        size = option
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ChipSize.entries.size)
                                ) { Text(option.label) }
                            }
                        }
                        Text(
                            when (size) {
                                ChipSize.STANDARD -> "One slot: ${bigExample(spec.kind).first}."
                                ChipSize.EXTENDED -> "Two slots when there is room, showing ${bigExample(spec.kind).second}. " +
                                    "Under the temperature (and in Split, Grid and strips) it spreads across; in a stacked column it takes two rows, with ${bigExample(spec.kind).third}."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("On the extra chips card, chips are always extended.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (spec.kind == Complication.STEPS) {
                    item {
                        Text("Daily goal", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = goal,
                            onValueChange = { typed ->
                                goal = typed.filter { it.isDigit() }.take(6)
                                goal.toIntOrNull()?.takeIf { it > 0 }?.let { Prefs.setStepGoal(context, it) }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("Shown when the steps chip is extended.") }
                        )
                    }
                }
                if (spec.kind == Complication.COUNTDOWN) {
                    item {
                        Text("Count down to", style = MaterialTheme.typography.labelLarge)
                        Text(
                            countdown?.let { LocalDate.ofEpochDay(it).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)) }
                                ?.let { if (countdownEvent != null) "$it · from your calendar (follows the event if it moves)" else it }
                                ?: "No date chosen"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = {
                                val start = countdown?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().plusDays(1)
                                DatePickerDialog(context, { _, y, m, d ->
                                    val day = LocalDate.of(y, m + 1, d).toEpochDay()
                                    Prefs.setCountdownDay(context, day)
                                    countdown = day
                                    countdownEvent = null
                                }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                            }) { Text("Choose date") }
                            OutlinedButton(onClick = {
                                if (CalendarSource.hasPermission(context)) pickingEvent = true
                                else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            }) { Text("Pick from calendar") }
                        }
                        if (pickingEvent) {
                            EventPickerDialog(
                                onPick = { event ->
                                    Prefs.setCountdownEvent(context, event.eventId, event.day.toEpochDay(), event.title)
                                    countdown = event.day.toEpochDay()
                                    countdownEvent = event.eventId
                                    countdownName = Prefs.countdownName(context)
                                    pickingEvent = false
                                },
                                onDismiss = { pickingEvent = false }
                            )
                        }
                        OutlinedTextField(
                            value = countdownName,
                            onValueChange = {
                                countdownName = it.take(30)
                                Prefs.setCountdownName(context, countdownName)
                            },
                            singleLine = true,
                            label = { Text("What it's for (optional)") },
                            supportingText = { Text("Shown when the countdown chip is extended, with the date.") }
                        )
                    }
                }
                if (spec.kind == Complication.GMAIL && spec.arg != null) {
                    item {
                        Text("Count unread in", style = MaterialTheme.typography.labelLarge)
                        when {
                            !GmailSource.hasPermission(context) -> Text("Grant the Gmail permission first.")
                            labels == null -> Text("Gmail did not return any labels for this account. Check that it is signed in to the Gmail app.")
                        }
                    }
                    items(labels.orEmpty()) { label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                chosenLabels = if (label.canonicalName in chosenLabels) chosenLabels - label.canonicalName else chosenLabels + label.canonicalName
                                Prefs.setGmailLabels(context, spec.arg, chosenLabels)
                            }
                        ) {
                            Checkbox(checked = label.canonicalName in chosenLabels, onCheckedChange = null)
                            Text("${GmailSource.displayName(label)} (${label.unread} unread)", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                item {
                    TextButton(onClick = onRemove) { Text("Remove this chip", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    )
}

/** Upcoming calendar events to count down to, searchable, soonest first. */
@Composable
private fun EventPickerDialog(onPick: (CalendarSource.Upcoming) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<CalendarSource.Upcoming>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { events = withContext(Dispatchers.IO) { CalendarSource.upcoming(context) } }
    val dateFormat = remember { java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMMyyyy")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Count down to an event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true)
                val shown = events.orEmpty().filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                when {
                    events == null -> Text("Loading events…")
                    shown.isEmpty() -> Text(if (query.isBlank()) "No events in the next year." else "No events match.")
                }
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(shown, key = { it.eventId }) { event ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { onPick(event) }.padding(vertical = 10.dp)) {
                            Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(event.day.format(dateFormat), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun AppPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true)
                val shown = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                if (apps == null) Text("Loading apps…")
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        Text(
                            app.label,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(app.pkg) }.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    )
}

/** Android's own account picker, listing only Google accounts (the ones Gmail uses). */
private fun chooseGoogleAccount(current: String?): Intent =
    AccountManager.newChooseAccountIntent(
        current?.let { android.accounts.Account(it, "com.google") }, null, arrayOf("com.google"),
        "Count unread mail in this account", null, null, null
    )

/** What a chip shows: standard, extended across, and the second line when extended in a column. */
private fun bigExample(kind: Complication): Triple<String, String, String> = when (kind) {
    Complication.COUNTDOWN -> Triple("\"4d\"", "\"4d · Holiday · Mon 2 Nov\"", "\"Holiday · Mon 2 Nov\" under \"4d\"")
    Complication.SLEEP -> Triple("\"5h 13m\"", "\"5h 13m · 23:40–05:10\"", "\"23:40–05:10\" under \"5h 13m\"")
    Complication.STEPS -> Triple("\"2,047\"", "\"2,047 of 8,000\"", "\"26% of 8,000\" under \"2,047\"")
    Complication.SCREEN_TIME -> Triple("\"1h 20m\"", "\"1h 20m · YouTube 35m\"", "\"YouTube 35m\" under \"1h 20m\"")
    Complication.SUNSET -> Triple("\"16:38\"", "\"Sunset 16:38 · Sunrise 07:05\"", "\"Sunrise 07:05\" under \"16:38\"")
    Complication.BUDS -> Triple("each device in turn", "every device side by side", "the devices on two rows")
    else -> Triple("", "", "")
}
