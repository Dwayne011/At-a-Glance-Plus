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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import com.ataglanceplus.complications.HealthSource
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.complications.SimpleSources
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.Wadb
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
    else -> spec.kind.label
}

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

    Section("Complication chips") {
        Text(
            "Pick as many chips as you like, in order of importance. Chips with something to report right now fill the weather card first, then other cards on show, then an extra chips card. When one has nothing to report (no unread mail, say), the next one takes its place.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Weather card layout", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherLayout.entries.forEach { option ->
                FilterChip(selected = layout == option, onClick = { Prefs.setWeatherLayout(context, option) }, label = { Text(option.label) })
            }
        }
        Text(
            when (layout) {
                WeatherLayout.COLUMNS -> "Next 3 hours as columns; 2 chips under the temperature and 2 stacked on the right."
                WeatherLayout.SPLIT -> "Next 3 hours in a row; 2 chips under the temperature and 2 wide chips under the forecast."
                WeatherLayout.GRID -> "Next 2 hours in a box; 2 chips under the temperature and a 2 x 2 grid of chips."
                WeatherLayout.NONE -> "Next 3 hours, and no chips anywhere."
            },
            style = MaterialTheme.typography.bodySmall
        )
        if (grantMessage.isNotEmpty()) {
            Text(grantMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (chips.isEmpty()) {
            Text("No chips chosen; the weather card uses the design's examples (Steps, Battery, Alarm and so on).", style = MaterialTheme.typography.bodySmall)
        }
        chips.forEach { spec ->
            HorizontalDivider()
            val missing = needs(context, spec, healthGranted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chipTitle(context, spec), style = MaterialTheme.typography.labelLarge)
                val opens = appLabel(context, Prefs.chipApp(context, spec.key)) ?: spec.defaultAppName(context) ?: "its usual app"
                val status = (if (!spec.configured) "Choose the Gmail account to count" else null)
                    ?: values[spec.key]?.text?.let { "Now: $it" }
                    ?: missing.firstOrNull()?.text
                    ?: "Nothing to report right now"
                // The app in the accent colour, so it stands out from the status.
                val accent = MaterialTheme.colorScheme.primary
                Text(
                    buildAnnotatedString {
                        append("$status · Opens ")
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(opens) }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (spec.kind == Complication.BUDS) {
                    Text("Experimental: shows each connected Bluetooth device that reports its battery (earbuds, headphones, watch), taking turns.", style = MaterialTheme.typography.bodySmall)
                }
                if (spec.kind == Complication.STEPS || spec.kind == Complication.SLEEP) {
                    Text("Reads Health Connect. Turn on Health Connect sync in Google Health or Fitbit.", style = MaterialTheme.typography.bodySmall)
                }
                // Wraps onto a second line rather than squeezing buttons when space runs out.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    missing.firstOrNull()?.let { need ->
                        Button(enabled = !busy || need !in setOf(Need.NOTIFICATIONS, Need.USAGE, Need.CALL_LOG), onClick = {
                            when (need) {
                                Need.CALL_LOG -> permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
                                Need.GMAIL -> permissions.launch(arrayOf(GmailSource.PERMISSION))
                                Need.BLUETOOTH -> permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                Need.HEALTH -> health.launch(HealthSource.ALL)
                                Need.NOTIFICATIONS, Need.USAGE -> scope.launch {
                                    grantOverAdb(notifications = need == Need.NOTIFICATIONS, usage = need == Need.USAGE, callLog = false)
                                }
                                Need.LOCATION -> Unit
                                Need.DATE -> settingsFor = spec
                            }
                        }) { Text(if (need == Need.DATE) "Choose date" else "Grant") }
                    }
                    if (spec.kind == Complication.GMAIL && !spec.configured) {
                        Button(onClick = {
                            replacing = spec
                            accountChooser.launch(chooseGoogleAccount(spec.arg))
                        }) { Text("Choose account") }
                    }
                    OutlinedButton(onClick = { settingsFor = spec }) { Text("Settings") }
                    val index = chips.indexOfFirst { it.key == spec.key }
                    if (index > 0) {
                        TextButton(onClick = {
                            Prefs.setChips(context, chips.toMutableList().apply { add(index - 1, removeAt(index)) })
                        }) { Text("Move up") }
                    }
                    TextButton(onClick = { Prefs.setChips(context, chips.filter { it.key != spec.key }) }) { Text("Remove") }
                }
                // Android blocks notification and usage access for apps installed outside the Play
                // Store until "Allow restricted settings"; wireless debugging grants them directly.
                if (missing.firstOrNull() == Need.NOTIFICATIONS || missing.firstOrNull() == Need.USAGE) {
                    TextButton(onClick = {
                        val intent = if (missing.first() == Need.NOTIFICATIONS) {
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationCounter.component(context).flattenToString())
                        } else {
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        }
                        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Or open the setting") }
                    Text("Grant uses wireless debugging (Wi-Fi needed).", style = MaterialTheme.typography.bodySmall)
                }
                if (missing.firstOrNull() == Need.CALL_LOG) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch { grantOverAdb(notifications = false, usage = false, callLog = true) }
                    }) { Text("If the prompt does not appear: grant with wireless debugging") }
                }
            }
        }

        HorizontalDivider()
        Text(
            if (chips.size < Chips.MAX_CHOSEN) "Add a chip" else "Remove a chip to add another (${Chips.MAX_CHOSEN} at most)",
            style = MaterialTheme.typography.labelLarge
        )
        if (chips.size < Chips.MAX_CHOSEN) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Complication.entries.filter { it.pickable }.forEach { kind ->
                    val already = !kind.needsArg && chips.any { it.kind == kind }
                    if (!already) {
                        AssistChip(onClick = {
                            when (kind) {
                                Complication.GMAIL -> {
                                    replacing = null
                                    accountChooser.launch(chooseGoogleAccount(null))
                                }
                                Complication.APP_NOTIFICATIONS -> picking = { pkg -> add(ChipSpec(Complication.APP_NOTIFICATIONS, pkg)) }
                                else -> add(ChipSpec(kind))
                            }
                        }, label = { Text(kind.label) })
                    }
                }
            }
        }
    }

    Text(
        com.ataglanceplus.cards.WeatherCard.fitReport(context),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    settingsFor?.let { spec ->
        ChipSettingsDialog(
            spec,
            onPickApp = { onPicked -> picking = onPicked },
            onChooseAccount = {
                settingsFor = null
                replacing = spec
                accountChooser.launch(chooseGoogleAccount(spec.arg))
            },
            onDismiss = { settingsFor = null }
        )
    }
    picking?.let { onPicked ->
        AppPickerDialog(onPick = { pkg -> onPicked(pkg); picking = null }, onDismiss = { picking = null })
    }
}

@Composable
private fun ChipSettingsDialog(spec: ChipSpec, onPickApp: (((String) -> Unit)) -> Unit, onChooseAccount: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var opens by remember { mutableStateOf(Prefs.chipApp(context, spec.key)) }
    var labels by remember { mutableStateOf<List<GmailSource.Label>?>(null) }
    var chosenLabels by remember { mutableStateOf(spec.arg?.let { Prefs.gmailLabels(context, it) } ?: emptySet()) }
    var countdown by remember { mutableStateOf(Prefs.countdownDay(context)) }
    var countdownName by remember { mutableStateOf(Prefs.countdownName(context)) }
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
                                ChipSize.NORMAL -> "One slot: ${bigExample(spec.kind).first}."
                                ChipSize.WIDE -> "Two slots across: ${bigExample(spec.kind).second}. Where chips are stacked (the right of Columns, and of other cards) it shows normal size."
                                ChipSize.TALL -> "Two stacked slots, with a second line: ${bigExample(spec.kind).third}. Where chips sit side by side (under the temperature, Split, Grid, the strip) it shows normal size."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
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
                            supportingText = { Text("Shown on a wide or tall steps chip.") }
                        )
                    }
                }
                if (spec.kind == Complication.COUNTDOWN) {
                    item {
                        Text("Count down to", style = MaterialTheme.typography.labelLarge)
                        Text(countdown?.let { LocalDate.ofEpochDay(it).toString() } ?: "No date chosen")
                        OutlinedButton(onClick = {
                            val start = countdown?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().plusDays(1)
                            DatePickerDialog(context, { _, y, m, d ->
                                val day = LocalDate.of(y, m + 1, d).toEpochDay()
                                Prefs.setCountdownDay(context, day)
                                countdown = day
                            }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                        }) { Text("Choose date") }
                        OutlinedTextField(
                            value = countdownName,
                            onValueChange = {
                                countdownName = it.take(30)
                                Prefs.setCountdownName(context, countdownName)
                            },
                            singleLine = true,
                            label = { Text("What it's for (optional)") },
                            supportingText = { Text("Shown on a wide or tall countdown chip, with the date.") }
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

/** What a chip shows at each size, for the size setting: normal, wide, tall. */
private fun bigExample(kind: Complication): Triple<String, String, String> = when (kind) {
    Complication.COUNTDOWN -> Triple("\"4d\"", "\"4d · Holiday · Mon 2 Nov\"", "\"4d\" over \"Holiday · Mon 2 Nov\"")
    Complication.SLEEP -> Triple("\"5h 13m\"", "\"5h 13m · 23:40–05:10\"", "\"5h 13m\" over \"23:40–05:10\"")
    Complication.STEPS -> Triple("\"2,047\"", "\"2,047 of 8,000\"", "\"2,047\" over \"26% of 8,000\"")
    Complication.SCREEN_TIME -> Triple("\"1h 20m\"", "\"1h 20m · YouTube 35m\"", "\"1h 20m\" over \"YouTube 35m\"")
    Complication.SUNSET -> Triple("\"16:38\"", "\"Sunset 16:38 · Sunrise 07:05\"", "\"16:38\" over \"Sunrise 07:05\"")
    Complication.BUDS -> Triple("each device in turn", "every device side by side", "the first device over the others")
    else -> Triple("", "", "")
}
