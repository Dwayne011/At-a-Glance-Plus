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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.Chips
import com.ataglanceplus.cards.Complication
import com.ataglanceplus.cards.ComplicationLayout
import com.ataglanceplus.complications.ChipValues
import com.ataglanceplus.complications.GmailSource
import com.ataglanceplus.complications.HealthSource
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.complications.SimpleSources
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.Wadb
import com.ataglanceplus.weather.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    val layout = remember(version) { Prefs.complicationLayout(context) }

    fun add(spec: ChipSpec) {
        if (chips.none { it.key == spec.key } && chips.size < Chips.MAX_BOTTOM) Prefs.setChips(context, chips + spec)
    }

    val accountChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { account ->
            add(ChipSpec(Complication.GMAIL, account))
            if (!GmailSource.hasPermission(context)) permissions.launch(arrayOf(GmailSource.PERMISSION))
        }
    }

    Section("Complication chips") {
        Text(
            "Chips on the weather card, in the order added. The right column shows the first 2 with something to report, the bottom strip the first 4. A chip with nothing to report is hidden.",
            style = MaterialTheme.typography.bodySmall
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ComplicationLayout.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = layout == option,
                    onClick = { Prefs.setComplicationLayout(context, option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ComplicationLayout.entries.size)
                ) {
                    Text(
                        when (option) {
                            ComplicationLayout.RIGHT -> "Right"
                            ComplicationLayout.BOTTOM -> "Bottom"
                            ComplicationLayout.NONE -> "None"
                        }
                    )
                }
            }
        }

        if (grantMessage.isNotEmpty()) {
            Text(grantMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (chips.isEmpty()) {
            Text("No chips chosen; the weather card uses the design's defaults (Alarm, Battery and so on).", style = MaterialTheme.typography.bodySmall)
        }
        chips.forEach { spec ->
            HorizontalDivider()
            val missing = needs(context, spec, healthGranted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chipTitle(context, spec), style = MaterialTheme.typography.labelLarge)
                val opens = appLabel(context, Prefs.chipApp(context, spec.key)) ?: "its usual app"
                val status = (if (!spec.configured) "Not set up: remove it and add it again" else null)
                    ?: values[spec.key]?.text?.let { "Now: $it" }
                    ?: missing.firstOrNull()?.text
                    ?: "Nothing to report right now"
                Text("$status · Opens $opens", style = MaterialTheme.typography.bodySmall)
                if (spec.kind == Complication.BUDS) {
                    Text("Experimental: shows each connected Bluetooth device that reports its battery (earbuds, headphones, watch), taking turns.", style = MaterialTheme.typography.bodySmall)
                }
                if (spec.kind == Complication.STEPS || spec.kind == Complication.SLEEP) {
                    Text("Reads Health Connect. Turn on Health Connect sync in Google Health or Fitbit.", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    OutlinedButton(onClick = { settingsFor = spec }) { Text("Settings") }
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
        Text(if (chips.size < Chips.MAX_BOTTOM) "Add a chip" else "Remove a chip to add another (4 at most)", style = MaterialTheme.typography.labelLarge)
        if (chips.size < Chips.MAX_BOTTOM) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Complication.entries.filter { it.pickable }.forEach { kind ->
                    val already = !kind.needsArg && chips.any { it.kind == kind }
                    if (!already) {
                        AssistChip(onClick = {
                            when (kind) {
                                Complication.GMAIL -> accountChooser.launch(
                                    AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
                                )
                                Complication.APP_NOTIFICATIONS -> picking = { pkg -> add(ChipSpec(Complication.APP_NOTIFICATIONS, pkg)) }
                                else -> add(ChipSpec(kind))
                            }
                        }, label = { Text(kind.label) })
                    }
                }
            }
        }
    }

    settingsFor?.let { spec ->
        ChipSettingsDialog(spec, onPickApp = { onPicked -> picking = onPicked }, onDismiss = { settingsFor = null })
    }
    picking?.let { onPicked ->
        AppPickerDialog(onPick = { pkg -> onPicked(pkg); picking = null }, onDismiss = { picking = null })
    }
}

@Composable
private fun ChipSettingsDialog(spec: ChipSpec, onPickApp: (((String) -> Unit)) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var opens by remember { mutableStateOf(Prefs.chipApp(context, spec.key)) }
    var labels by remember { mutableStateOf<List<GmailSource.Label>?>(null) }
    var chosenLabels by remember { mutableStateOf(spec.arg?.let { Prefs.gmailLabels(context, it) } ?: emptySet()) }
    var countdown by remember { mutableStateOf(Prefs.countdownDay(context)) }
    if (spec.kind == Complication.GMAIL && spec.arg != null) {
        LaunchedEffect(spec.key) { labels = withContext(Dispatchers.IO) { GmailSource.labels(context, spec.arg) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text(chipTitle(context, spec)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("When tapped, opens", style = MaterialTheme.typography.labelLarge)
                    Text(appLabel(context, opens) ?: "Its usual app or screen")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            onPickApp { pkg ->
                                Prefs.setChipApp(context, spec.key, pkg)
                                opens = pkg
                            }
                        }) { Text("Choose app") }
                        if (opens != null) {
                            TextButton(onClick = {
                                Prefs.setChipApp(context, spec.key, null)
                                opens = null
                            }) { Text("Use default") }
                        }
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
