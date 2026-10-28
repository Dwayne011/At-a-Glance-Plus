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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val lastEvent by ServiceState.lastEvent.collectAsStateWithLifecycle()
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val wadbLog by Wadb.log.collectAsStateWithLifecycle()
    var activateOnBoot by remember { mutableStateOf(Prefs.activateOnBoot(context)) }
    // The App tab (pairing, activation, weather) comes first.
    var tab by rememberSaveable { mutableIntStateOf(0) }

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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("At a Glance Plus", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Takes over the native At a Glance slot over wireless debugging.",
                style = MaterialTheme.typography.bodyMedium
            )

            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("App", "Cards", "Chips").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }

            when (tab) {
                1 -> CardPreviewSection(maxCards = sessions.values.maxOfOrNull { it.targetCount } ?: 0)
                2 -> ChipsSection()
                else -> {
                    ProblemsSection()

                    Section("Device checks") {
                        CheckRow("Android 16 or newer", Build.VERSION.SDK_INT >= 36, "API ${Build.VERSION.SDK_INT}")
                        CheckRow("Pixel Launcher installed", Commands.isPixelLauncherInstalled(context), Commands.PIXEL_LAUNCHER)
                        when (val provider = Commands.lookupDefaultProvider(context)) {
                            is Commands.Provider.Found -> CheckRow("Google At a Glance provider found", true, provider.component.flattenToShortString())
                            is Commands.Provider.Missing -> CheckRow("Google At a Glance provider found", false, provider.reason)
                        }
                    }

                    Section("Connection") {
                        if (sessions.isEmpty()) {
                            Text("Not connected. No client has opened a session with this service yet.")
                        } else {
                            val surfaces = sessions.values.map { it.surface }.toSet()
                            Text(
                                "Home screen: " + (if ("home" in surfaces) "connected" else "not connected") +
                                    " · Lock screen: " + (if ("lockscreen" in surfaces) "connected" else "not connected")
                            )
                            Text("Active sessions:")
                            sessions.values.forEach { s ->
                                val pushed = if (s.lastPushAt == 0L) "no push yet" else "pushed " + DateFormat.getTimeInstance().format(Date(s.lastPushAt))
                                Text("• ${s.surface} from ${s.clientPackage} (wants ${s.targetCount}) · $pushed", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (lastEvent.isNotEmpty()) {
                            Text("Last event: $lastEvent", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Section("Wireless debugging") {
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

                        if (!paired) {
                            Text(
                                "Pairing: tap the button, then in the Developer options page that opens choose \"Pair device with pairing code\". A notification from this app asks for the six-digit code. Keep the dialog open while you type it.",
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
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(enabled = !busy, onClick = {
                                    scope.launch { Activator.run(context, Activator.Mode.ACTIVATE) }
                                }) { Text("Activate") }
                                OutlinedButton(enabled = !busy && Commands.defaultProvider(context) != null, onClick = {
                                    scope.launch { Activator.run(context, Activator.Mode.RESET) }
                                }) { Text("Back to Google") }
                            }
                            OutlinedButton(onClick = { startPairing(context) }) { Text("Pair again") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Re-activate after a restart")
                                    Text("Waits for Wi-Fi, then runs the activation. The launcher and SystemUI restart once.", style = MaterialTheme.typography.bodySmall)
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
                        if (wadbLog.isNotEmpty()) {
                            Text("Last activation, command by command:", style = MaterialTheme.typography.labelLarge)
                            Text(wadbLog, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Section("Weather") {
                        Text(
                            "Weather from Open-Meteo, worldwide, using each country's own forecast model where one exists. Only your approximate location is sent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        CheckRow("Approximate location", hasLocation, if (hasLocation) "Granted" else "Needed to know which forecast to show")
                        CheckRow(
                            "Location in the background",
                            hasBackgroundLocation,
                            if (hasBackgroundLocation) "Weather follows you while the app is closed" else "Without it, weather uses where you were when the app was last open"
                        )
                        if (!hasLocation) {
                            Button(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                                Text("Allow location")
                            }
                        } else if (!hasBackgroundLocation) {
                            Text("On the next screen choose \"Allow all the time\".", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                                Text("Allow in the background")
                            }
                        }
                        val current = weather
                        if (current != null) {
                            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.fetchedAt))
                            Text("Now: ${current.condition.label}, ${current.temperatureText}. ${current.detailText}. Updated $time")
                        } else {
                            Text("No weather yet.")
                        }
                        if (weatherStatus.isNotEmpty()) {
                            Text(weatherStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(enabled = hasLocation, onClick = {
                            scope.launch { WeatherRepository.refresh(context, force = true) }
                        }) { Text("Refresh weather") }
                    }

                    Section("Fallback: from a computer") {
                        Text("The same commands, for adb over USB if wireless debugging is not an option.", style = MaterialTheme.typography.bodySmall)
                        CommandBlock(Commands.activateAdb(context))
                        Commands.resetAdb(context)?.let { CommandBlock(it) }
                    }

                    Section("Service component") {
                        Text(Commands.serviceComponent(context), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("User id ${Commands.userId()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
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

/** The app's own error log, and the phone's crash log read over wireless debugging, ready to copy. */
@Composable
private fun ProblemsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val wadbLog by Wadb.log.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf(com.ataglanceplus.CrashLog.read(context)) }
    var reading by remember { mutableStateOf(false) }

    Section("Problems") {
        if (log.isEmpty()) {
            Text("No errors recorded by this app.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Errors this app recorded, newest first:", style = MaterialTheme.typography.bodySmall)
            Text(log.take(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { copy(context, "At a Glance Plus errors", log) }) { Text("Copy errors") }
                OutlinedButton(onClick = {
                    com.ataglanceplus.CrashLog.clear(context)
                    log = ""
                }) { Text("Clear") }
            }
        }
        Text(
            "If the launcher or System UI crashed rather than this app, read the phone's crash log (uses wireless debugging).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(enabled = !busy && Prefs.isPaired(context), onClick = {
            reading = true
            scope.launch { com.ataglanceplus.wadb.Activator.readCrashLog(context) }
        }) { Text("Read the phone's crash log") }
        if (reading && wadbLog.isNotEmpty()) {
            Button(onClick = { copy(context, "Crash log", wadbLog) }) { Text("Copy the phone's crash log") }
            Text(wadbLog.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
