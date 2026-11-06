package com.ataglanceplus.ui

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.CrashLog
import com.ataglanceplus.DebugLog
import com.ataglanceplus.Prefs
import com.ataglanceplus.service.ServiceState
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.Wadb
import com.ataglanceplus.wallet.WalletGeofences
import com.ataglanceplus.wallet.WalletRules
import com.ataglanceplus.wallet.WalletSource
import kotlinx.coroutines.launch

/**
 * Advanced › Debug: everything needed to tell why the cards stopped showing or a place did not
 * trigger, in one shareable report. On show: errors, why Android last stopped the app, how it
 * treats the app in the background, the Wallet places' location state, System check and the
 * app's history. Folded under More details: the connections, the device, wireless debugging, the
 * last command log and the commands for a computer.
 *
 * The report holds no keys, tokens, account names or coordinates.
 */
@Composable
internal fun DebugPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val lastEvent by ServiceState.lastEvent.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val wadbLog by Wadb.log.collectAsStateWithLifecycle()
    val geofences by WalletGeofences.status.collectAsStateWithLifecycle()
    val inside by WalletGeofences.inside.collectAsStateWithLifecycle()
    val changed by WalletGeofences.changed.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") changed
    val history = remember(version, tick, sessions, geofences, inside) { DebugLog.read(context) }
    val paired = remember(version) { Prefs.isPaired(context) }
    // Whether System check or the crash log ran while this page was open.
    var ranHere by remember { androidx.compose.runtime.mutableStateOf(false) }

    SettingsPage("Debug", onBack) {
        SettingRow("Share debug report", "Everything here, details included, as text, without keys, accounts or locations", icon = Icons.Default.Share, accent = true,
            onClick = { share(context, report(context)) })
        SettingRow("Copy debug report", icon = Icons.AutoMirrored.Filled.List, onClick = { copy(context, "At a Glance Plus debug report", report(context)) })

        // The one thing worth seeing at once from the rest: the cards are not showing.
        if (sessions.isEmpty()) {
            CheckRow("Not connected", false, "Neither the launcher nor the lock screen is connected: they show Google's At a Glance until you activate again")
        }

        Subheader("Errors")
        var errors by remember(version, tick) { androidx.compose.runtime.mutableStateOf(CrashLog.read(context)) }
        if (errors.isEmpty()) {
            SettingRow("No errors recorded", icon = Icons.Default.CheckCircle)
        } else {
            // Each error as when, where and what; the full details (stack traces) go with Copy.
            val entries = errors.split("=== ").filter { it.isNotBlank() }
            SettingRow(
                "${entries.size} error${if (entries.size == 1) "" else "s"} recorded", "Newest first · tap to copy " + if (entries.size == 1) "it in full" else "them in full",
                icon = Icons.Default.Warning, error = true, onClick = { copy(context, "At a Glance Plus errors", errors) },
            )
            entries.take(10).forEach { entry ->
                val lines = entry.lines().map { it.trim() }.filter { it.isNotEmpty() }
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(lines.getOrElse(0) { "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(lines.getOrElse(1) { "" }, style = MaterialTheme.typography.bodySmall)
                    lines.getOrNull(2)?.let { Text(it.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (entries.size > 10) SettingNote("And ${entries.size - 10} older; Copy has them all.")
            SettingRow("Clear the errors", icon = Icons.Default.Delete, onClick = { CrashLog.clear(context); errors = "" })
        }

        Subheader("Why Android last stopped the app")
        val exits = remember(version, tick) { DebugLog.exitReasons(context, 5) }
        if (exits.isEmpty()) SettingNote("Android has no record of it stopping.")
        exits.forEach { SettingRow(DebugLog.time(it.timestamp), DebugLog.describe(it), icon = Icons.Default.Info) }
        SettingNote("Each stop drops the connection: the launcher and lock screen go back to Google's until the next activation.")

        Subheader("Battery and background")
        power(context).forEach { (label, ok, detail) -> CheckRow(label, ok, detail) }
        SettingRow("Open the app's battery settings", icon = Icons.Default.Build, onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
            }
        })

        Subheader("Location for Wallet places")
        location(context, geofences, inside).forEach { (label, ok, detail) ->
            SettingRow(label, detail, icon = if (ok == null) Icons.Default.LocationOn else if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, error = ok == false)
        }
        SettingNote("Play services watches the places and tells the app on arrival and departure, so Android's location indicator names Play services, not this app.")

        Subheader("System check")
        SettingRow(
            if (busy) "Checking…" else "Check the launcher, SystemUI and At a Glance",
            if (paired) "Over wireless debugging: why they last restarted, which provider At a Glance uses, background limits" else "Pair with this phone first (Advanced)",
            icon = Icons.Default.Build, enabled = paired && !busy,
            onClick = { scope.launch { Activator.systemCheck(context); ranHere = true; tick++ } },
        )
        SettingRow(
            "Read the phone's crash log", "This app's, the launcher's and SystemUI's crashes, into the log below",
            icon = Icons.Default.Build, enabled = paired && !busy,
            onClick = { scope.launch { Activator.readCrashLog(context); ranHere = true; tick++ } },
        )
        // The result of a check run just now; an older one is under More details.
        if (ranHere && wadbLog.isNotEmpty()) CommandLog(wadbLog)

        // Everything else, folded away: still in the shared report.
        var more by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        Subheader("More details")
        SettingRow(
            if (more) "Hide the details" else "Show the details",
            "Version and connections, the device, wireless debugging, the last command log and the commands for a computer",
            icon = Icons.Default.Info, onClick = { more = !more },
        )
        if (more) {
            Subheader("Now")
            now(context).forEach { (label, value) -> SettingRow(label, value, icon = Icons.Default.Info) }
            sessions.values.sortedBy { it.surface }.forEach { s ->
                CheckRow(
                    "${s.surface.replaceFirstChar { it.uppercase() }} connected", true,
                    "${s.clientPackage} · last drawn ${if (s.lastPushAt == 0L) "never" else DebugLog.time(s.lastPushAt)}",
                )
            }
            if (lastEvent.isNotEmpty()) SettingNote("Last event: $lastEvent")

            Subheader("Device")
            CheckRow("Pixel Launcher installed", com.ataglanceplus.Commands.isPixelLauncherInstalled(context), com.ataglanceplus.Commands.PIXEL_LAUNCHER)
            when (val provider = remember(version) { com.ataglanceplus.Commands.lookupDefaultProvider(context) }) {
                is com.ataglanceplus.Commands.Provider.Found -> CheckRow("Google At a Glance provider found", true, provider.component.flattenToShortString())
                is com.ataglanceplus.Commands.Provider.Missing -> CheckRow("Google At a Glance provider found", false, provider.reason)
            }

            Subheader("Wireless debugging")
            val wirelessOn = remember(version, tick) { Wadb.wirelessDebuggingState(context) }
            val canSelfEnable = remember(version, tick) { Wadb.canSwitchDebuggingOn(context) }
            CheckRow("Paired with this phone", paired, if (paired) "The phone trusts this app's key" else "Pair once; no computer needed")
            CheckRow("Wireless debugging on", wirelessOn == true, if (wirelessOn == true) "Developer options › Wireless debugging" else "Android hides this setting from apps; Activate checks it directly")
            CheckRow(
                "Can switch it on after a restart", canSelfEnable,
                when {
                    canSelfEnable -> "Secure-settings permission held"
                    Prefs.debugSwitchBlocked(context) -> "Android no longer lets apps switch it on; turn it on yourself after a restart (a notification reminds you)"
                    else -> "Granted automatically during the first activation"
                },
            )
            CheckRow("Wi-Fi connected", Wadb.isWifiConnected(context), "Wireless debugging only works on Wi-Fi")

            if (wadbLog.isNotEmpty()) {
                Subheader("Last command log")
                CommandLog(wadbLog)
            }

            Subheader("Commands for a computer")
            SettingNote("For adb over USB, if wireless debugging is not an option.")
            CommandRow("Activate", com.ataglanceplus.Commands.activateAdb(context))
            com.ataglanceplus.Commands.resetAdb(context)?.let { CommandRow("Back to Google", it) }
            SettingNote("Service component: ${com.ataglanceplus.Commands.serviceComponent(context)} · user ${com.ataglanceplus.Commands.userId()}")
        }

        Subheader("History")
        SettingNote("Newest first. Kept on this phone only (${history.size} entries).")
        SettingRow("Copy the history", icon = Icons.AutoMirrored.Filled.List, onClick = { copy(context, "At a Glance Plus history", history.joinToString("\n")) })
        SettingRow("Clear the history", icon = Icons.Default.Delete, onClick = { DebugLog.clear(context); tick++ })
        if (history.isEmpty()) SettingNote("Nothing yet.")
        history.take(200).forEach { HistoryEntry(it) }
    }
}

/** A command's output, with a row to copy it. */
@Composable
private fun CommandLog(log: String) {
    val context = LocalContext.current
    SettingRow("Command log", "The last activation, check or crash log · tap to copy", icon = Icons.AutoMirrored.Filled.List, onClick = { copy(context, "Command log", log) })
    LogText(log, limit = 12_000)
}

/** One history line, as the time and kind over the message. */
@Composable
private fun HistoryEntry(line: String) {
    val stamp = line.take(19)
    val rest = line.drop(19).trim()
    val kind = rest.substringBefore(' ')
    val message = rest.substringAfter(' ').trim()
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text("$stamp · $kind", style = MaterialTheme.typography.labelSmall,
            color = if (kind == "Error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

/** Version, phone, and how long the app has been running. */
private fun now(context: Context): List<Pair<String, String>> {
    val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    return listOf(
        "App" to "Version ${DebugLog.version(context)} · running since ${DebugLog.time(started)}",
        "Phone" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.ID}",
        // Android stops the largest background processes first when memory runs short.
        "Memory" to runCatching {
            val pss = context.getSystemService(android.app.ActivityManager::class.java)
                .getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
            "using ${pss / 1024} MB (PSS)"
        }.getOrDefault("unknown"),
    )
}

/** Whether Android may stop or hold back the app in the background: (label, fine, detail). */
private fun power(context: Context): List<Triple<String, Boolean, String>> {
    val pkg = context.packageName
    val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
    val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    val bucket = runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(-1)
    val bucketName = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
        5 -> "Exempted"
        else -> "Unknown ($bucket)"
    }
    return listOf(
        Triple("Battery use: ${if (unrestricted) "Unrestricted" else "Optimised"}", true,
            if (unrestricted) "Android does not hold the app back to save battery"
            else "The usual setting. The connection keeps the app running; if it keeps dropping, try Unrestricted"),
        Triple("Background use ${if (restricted) "restricted" else "allowed"}", !restricted,
            if (restricted) "Battery is set to Restricted: Android may stop the app at any time. Set it to Optimised or Unrestricted"
            else "Not restricted"),
        Triple("Standby bucket: $bucketName", bucket != UsageStatsManager.STANDBY_BUCKET_RESTRICTED, "How often Android lets the app run background work"),
    )
}

/** The Wallet places' location state: (label, fine or not or neutral, detail). No coordinates. */
private fun location(context: Context, status: WalletGeofences.Status?, inside: Set<String>): List<Triple<String, Boolean?, String>> {
    val out = mutableListOf<Triple<String, Boolean?, String>>()
    val permission = when {
        WalletGeofences.hasPrecise(context) -> "Precise"
        WalletGeofences.hasLocation(context) -> "Approximate only"
        else -> "Not allowed"
    }
    out += Triple("Location permission: $permission", WalletGeofences.hasPrecise(context), if (WalletGeofences.hasPrecise(context)) "What places need" else "Places need precise location; approximate is only good to a few kilometres")
    out += Triple("Allowed all the time: ${if (WalletGeofences.hasBackground(context)) "yes" else "no"}", WalletGeofences.hasBackground(context), "Needed to notice arriving while the app is closed")
    out += Triple("Location on: ${if (WalletGeofences.locationOn(context)) "yes" else "no"}", WalletGeofences.locationOn(context), "The phone's location switch")
    out += Triple(
        when { status == null -> "Places: not registered since the app started"; status.ok -> "Places: ${status.message}"; else -> "Places not watched" },
        status?.ok,
        status?.let { (if (it.ok) "" else it.message + " · ") + "at " + DebugLog.time(it.at) } ?: "Registered when the cards connect, the rules change or the screen comes on",
    )
    val fix = WalletGeofences.cachedLocation
    out += Triple(
        "Last location fix",
        null,
        fix?.let { "${DebugLog.time(it.time)} · accurate to ${it.accuracy.toInt()} m" } ?: "None since the app started",
    )
    WalletSource.ensureLoaded(context)
    val passes = WalletSource.passes.value.associateBy { it.id }
    val showing = runCatching { WalletRules.activePassIds(context, System.currentTimeMillis(), fix) }.getOrDefault(emptySet())
    val insidePasses = inside.map(WalletGeofences::passOf).toSet()
    WalletRules.rules(context).filter { it.enabled && it.places.isNotEmpty() }.forEach { rule ->
        val title = passes[rule.passId]?.title ?: "A pass no longer in Wallet"
        val places = rule.places.joinToString("; ") { place ->
            val name = place.chain?.let { "any $it" } ?: place.label
            val distance = fix?.let { f -> WalletRules.distanceTo(context, f, place) }?.let { " · ${(it / 10).toInt() * 10} m away at the last fix" }.orEmpty()
            "$name within ${place.radiusM} m$distance"
        }
        out += Triple(
            "$title: ${if (rule.passId in showing) "showing" else "not showing"}",
            rule.passId in showing,
            places + if (rule.passId in insidePasses) " · inside, says Play services" else " · not inside, says Play services",
        )
    }
    return out
}

/** The whole page as text, plus the errors and the command log. */
internal fun report(context: Context): String = buildString {
    appendLine("At a Glance Plus debug report · ${DebugLog.time(System.currentTimeMillis())}")
    appendLine()
    appendLine("== Now")
    now(context).forEach { (l, v) -> appendLine("$l: $v") }
    val sessions = ServiceState.sessions.value.values
    if (sessions.isEmpty()) appendLine("Connected: no (launcher and lock screen on Google's)")
    sessions.sortedBy { it.surface }.forEach { appendLine("Connected: ${it.surface} · ${it.clientPackage} · last drawn ${if (it.lastPushAt == 0L) "never" else DebugLog.time(it.lastPushAt)} · ${it.showing.joinToString(", ")}") }
    ServiceState.lastEvent.value.takeIf { it.isNotEmpty() }?.let { appendLine("Last event: $it") }
    appendLine()
    appendLine("== Why Android last stopped the app")
    DebugLog.exitReasons(context, 10).forEach { appendLine("${DebugLog.time(it.timestamp)}: ${DebugLog.describe(it)}") }
    appendLine()
    appendLine("== Battery and background")
    power(context).forEach { (l, ok, d) -> appendLine("${if (ok) "OK " else "!! "}$l · $d") }
    appendLine()
    appendLine("== Location for Wallet places")
    location(context, WalletGeofences.status.value, WalletGeofences.inside.value).forEach { (l, ok, d) ->
        appendLine("${when (ok) { true -> "OK "; false -> "!! "; null -> "-- " }}$l · $d")
    }
    appendLine()
    appendLine("== History (newest first)")
    DebugLog.read(context).forEach { appendLine(it) }
    appendLine()
    appendLine("== Chip fitting")
    appendLine(runCatching { com.ataglanceplus.cards.WeatherCard.fitReport(context) }.getOrDefault("?"))
    appendLine()
    appendLine("== Errors")
    appendLine(CrashLog.read(context).ifEmpty { "None" })
    Wadb.log.value.takeIf { it.isNotEmpty() }?.let {
        appendLine()
        appendLine("== Last command log")
        appendLine(it)
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "At a Glance Plus debug report")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share debug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
