package com.ataglanceplus.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.service.ServiceState
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.PairingService
import com.ataglanceplus.wadb.Wadb
import com.ataglanceplus.weather.WeatherRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                StatusScreen()
            }
        }
    }
}

@Composable
private fun StatusScreen() {
    val scope = rememberCoroutineScope()
    val tabs = listOf("App", "Cards", "Chips")
    // The App tab (pairing, activation, weather) comes first; the tabs also follow a swipe.
    val pager = rememberPagerState(pageCount = { tabs.size })

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "At a Glance Plus",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(title) },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Top) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (page) {
                        1 -> CardPreviewSection()
                        2 -> ChipsSection()
                        else -> AppTab()
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * The App tab: whether the app is running At a Glance, and the switch to do so; problems and
 * failed device checks only when there are any; weather location; backup. Everything technical
 * is in Advanced, closed until opened.
 */
@Composable
private fun AppTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val lastEvent by ServiceState.lastEvent.collectAsStateWithLifecycle()
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val wadbLog by Wadb.log.collectAsStateWithLifecycle()
    var activateOnBoot by remember { mutableStateOf(Prefs.activateOnBoot(context)) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    // Re-read settings and permissions whenever the screen comes back to the foreground.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val paired = remember(refresh, wadbStatus) { Prefs.isPaired(context) }
    val wirelessOn = remember(refresh, wadbStatus) { Wadb.wirelessDebuggingState(context) }
    val canSelfEnable = remember(refresh, wadbStatus) { Wadb.canWriteSecureSettings(context) }
    val wifi = remember(refresh, wadbStatus) { Wadb.isWifiConnected(context) }

    val weather by WeatherRepository.weather.collectAsStateWithLifecycle()
    val weatherStatus by WeatherRepository.status.collectAsStateWithLifecycle()
    val hasLocation = remember(refresh, weatherStatus) { WeatherRepository.hasLocationPermission(context) }
    val hasBackgroundLocation = remember(refresh, weatherStatus) { WeatherRepository.hasBackgroundLocationPermission(context) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) scope.launch { WeatherRepository.refresh(context, force = true) }
    }
    val backgroundLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPairing(context)
    }

    val androidOk = Build.VERSION.SDK_INT >= 36
    val launcherOk = Commands.isPixelLauncherInstalled(context)
    val provider = Commands.lookupDefaultProvider(context)
    val providerOk = provider is Commands.Provider.Found

    Section("At a Glance") {
        val surfaces = sessions.values.map { it.surface }.toSet()
        val home = com.ataglanceplus.service.AtAGlanceService.SURFACE_HOME in surfaces
        val lock = com.ataglanceplus.service.AtAGlanceService.SURFACE_LOCKSCREEN in surfaces
        Text(
            when {
                home && lock -> "Showing on the home screen and lock screen."
                home -> "Showing on the home screen, not the lock screen. Tap Activate to connect it."
                lock -> "Showing on the lock screen, not the home screen. Tap Activate to connect it."
                paired -> "Not showing yet. Tap Activate."
                else -> "Not set up yet. Pair once with this phone's wireless debugging; no computer needed."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!wifi) Text("Wireless debugging needs Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (!paired) {
            Text(
                "Tap the button, then in the Developer options page that opens choose \"Pair device with pairing code\". A notification from this app asks for the six-digit code. Keep the dialog open while you type it.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    startPairing(context)
                } else {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("Pair with this phone") }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Greyed out only while both the home and lock screens are connected to this app;
                // with one missing (after an update, say), Activate reconnects it.
                Button(enabled = !busy && !(home && lock), onClick = {
                    scope.launch { Activator.run(context, Activator.Mode.ACTIVATE) }
                }) { Text("Activate") }
                OutlinedButton(enabled = !busy && Commands.defaultProvider(context) != null, onClick = {
                    scope.launch { Activator.run(context, Activator.Mode.RESET) }
                }) { Text("Back to Google") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Re-activate after a restart")
                    Text("Waits for Wi-Fi, then activates. The launcher and System UI restart once.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = activateOnBoot, onCheckedChange = {
                    activateOnBoot = it
                    Prefs.setActivateOnBoot(context, it)
                })
            }
        }
        if (wadbStatus.isNotEmpty()) {
            Text(wadbStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }

    ProblemsSection()

    if (!androidOk || !launcherOk || !providerOk) {
        Section("This phone") {
            if (!androidOk) CheckRow("Android 16 or newer", false, "API ${Build.VERSION.SDK_INT}")
            if (!launcherOk) CheckRow("Pixel Launcher installed", false, Commands.PIXEL_LAUNCHER)
            if (provider is Commands.Provider.Missing) CheckRow("Google At a Glance provider found", false, provider.reason)
        }
    }

    Section("Weather") {
        if (!hasLocation) {
            Text("Weather needs your approximate location. Only that is sent, to Open-Meteo.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                Text("Allow location")
            }
        } else if (!hasBackgroundLocation) {
            Text(
                "Without background location, weather uses where you were when the app was last open. On the next screen choose \"Allow all the time\".",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                Text("Allow in the background")
            }
        }
        val current = weather
        if (current != null) {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.fetchedAt))
            Text("${current.condition.label}, ${current.temperatureText}. ${current.detailText}. Updated $time", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(weatherStatus.ifEmpty { "No weather yet." }, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = hasLocation, onClick = {
            scope.launch { WeatherRepository.refresh(context, force = true) }
        }) { Text("Refresh weather") }
    }

    BackupSection()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Advanced", style = MaterialTheme.typography.titleMedium)
                    Text("Connection, wireless debugging, logs and adb commands", style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (advanced) "Hide" else "Show",
                    modifier = Modifier.rotate(if (advanced) 180f else 0f),
                )
            }
            if (!advanced) return@Column

            Text("Connection", style = MaterialTheme.typography.labelLarge)
            if (sessions.isEmpty()) {
                Text("No client has opened a session with this service yet.", style = MaterialTheme.typography.bodySmall)
            }
            sessions.values.sortedBy { it.surface }.forEach { s ->
                val pushed = if (s.lastPushAt == 0L) "no push yet" else "pushed " + DateFormat.getTimeInstance().format(Date(s.lastPushAt))
                Text("• ${s.surface} from ${s.clientPackage} · $pushed", style = MaterialTheme.typography.bodySmall)
                // What each surface was last sent, so a card that should be there but is not can
                // be told apart from one the launcher did not draw.
                if (s.lastPushAt > 0) Text("   now: ${s.showing.joinToString(" · ").ifEmpty { "nothing" }}", style = MaterialTheme.typography.bodySmall)
            }
            if (lastEvent.isNotEmpty()) Text("Last event: $lastEvent", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Device", style = MaterialTheme.typography.labelLarge)
            CheckRow("Android 16 or newer", androidOk, "API ${Build.VERSION.SDK_INT}")
            CheckRow("Pixel Launcher installed", launcherOk, Commands.PIXEL_LAUNCHER)
            when (provider) {
                is Commands.Provider.Found -> CheckRow("Google At a Glance provider found", true, provider.component.flattenToShortString())
                is Commands.Provider.Missing -> CheckRow("Google At a Glance provider found", false, provider.reason)
            }

            HorizontalDivider()
            Text("Wireless debugging", style = MaterialTheme.typography.labelLarge)
            CheckRow("Paired with this phone", paired, if (paired) "The phone trusts this app's key" else "Pair once; no computer needed")
            when (wirelessOn) {
                true -> CheckRow("Wireless debugging on", true, "Developer options › Wireless debugging")
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("--", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Wireless debugging")
                        Text("Android hides this setting from apps. Activate checks it directly.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            CheckRow("Can switch it on after a restart", canSelfEnable, if (canSelfEnable) "Secure-settings permission held" else "Granted automatically during the first activation")
            CheckRow("Wi-Fi connected", wifi, "Wireless debugging only works on Wi-Fi")
            if (paired) OutlinedButton(onClick = { startPairing(context) }) { Text("Pair again") }
            OutlinedButton(enabled = !busy && paired, onClick = {
                scope.launch { Activator.readCrashLog(context) }
            }) { Text("Read the phone's crash log") }
            if (wadbLog.isNotEmpty()) {
                Text("Last command log (activation, permission grant or crash log):", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { copy(context, "Command log", wadbLog) }) { Text("Copy") }
                Text(wadbLog.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Chip fitting", style = MaterialTheme.typography.labelLarge)
            Text(com.ataglanceplus.cards.WeatherCard.fitReport(context), style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("From a computer", style = MaterialTheme.typography.labelLarge)
            Text("The same commands, for adb over USB if wireless debugging is not an option.", style = MaterialTheme.typography.bodySmall)
            CommandBlock(Commands.activateAdb(context))
            Commands.resetAdb(context)?.let { CommandBlock(it) }
            Text("Service component: ${Commands.serviceComponent(context)} · user ${Commands.userId()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun startPairing(context: Context) {
    context.startForegroundService(PairingService.startIntent(context))
    runCatching { context.startActivity(Wadb.wirelessDebuggingSettingsIntent()) }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "OK" else "NO", style = MaterialTheme.typography.labelLarge, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CommandBlock(command: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
        }) {
            Text("Copy command")
        }
    }
}

/** The app's own error log, ready to copy; shown only when there is something in it. The phone's crash log is in Advanced. */
@Composable
private fun ProblemsSection() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(com.ataglanceplus.CrashLog.read(context)) }

    if (log.isEmpty()) return
    Section("Problems") {
        Text("Errors this app recorded, newest first:", style = MaterialTheme.typography.bodySmall)
        Text(log.take(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { copy(context, "At a Glance Plus errors", log) }) { Text("Copy errors") }
            OutlinedButton(onClick = {
                com.ataglanceplus.CrashLog.clear(context)
                log = ""
            }) { Text("Clear") }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
