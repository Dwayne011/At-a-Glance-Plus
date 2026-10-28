package com.ataglanceplus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.service.ServiceState
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
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val lastEvent by ServiceState.lastEvent.collectAsStateWithLifecycle()
    var cardStyle by remember { mutableStateOf(Prefs.cardStyle(context)) }

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
                "Milestone 1: take over the native At a Glance slot and show one test card.",
                style = MaterialTheme.typography.bodyMedium
            )

            Section("Device checks") {
                CheckRow("Android 16 or newer", Build.VERSION.SDK_INT >= 36, "API ${Build.VERSION.SDK_INT}")
                CheckRow("Pixel Launcher installed", Commands.isPixelLauncherInstalled(context), Commands.PIXEL_LAUNCHER)
                val default = Commands.defaultProvider(context)
                CheckRow("Google At a Glance provider found", default != null, default?.flattenToShortString() ?: "config_defaultSmartspaceService is empty")
            }

            Section("Connection") {
                if (sessions.isEmpty()) {
                    Text("Not connected. No client has opened a session with this service yet.")
                } else {
                    Text("Connected. Active sessions:")
                    sessions.values.forEach { s ->
                        val pushed = if (s.lastPushAt == 0L) "no push yet" else "pushed " + DateFormat.getTimeInstance().format(Date(s.lastPushAt))
                        Text("• ${s.surface} from ${s.clientPackage} (wants ${s.targetCount}) · $pushed", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (lastEvent.isNotEmpty()) {
                    Text("Last event: $lastEvent", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Test card style") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Prefs.CardStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = cardStyle == style,
                            onClick = {
                                cardStyle = style
                                Prefs.setCardStyle(context, style)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = Prefs.CardStyle.entries.size)
                        ) {
                            Text(if (style == Prefs.CardStyle.TEMPLATE) "Google template" else "Own layout")
                        }
                    }
                }
                Text(
                    when (cardStyle) {
                        Prefs.CardStyle.TEMPLATE -> "The launcher draws the card from our text and icon. Always works, limited look."
                        Prefs.CardStyle.LAYOUT -> "Our RemoteViews layout: progress bar, colours and fonts are ours. Android 16+."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Changes are pushed to connected sessions immediately.", style = MaterialTheme.typography.bodySmall)
            }

            Section("1. Activate (from a computer)") {
                Text("Enable USB debugging, connect the phone, then run this once. SystemUI and the launcher restart so they open sessions with this app while the override is in force.", style = MaterialTheme.typography.bodySmall)
                CommandBlock(Commands.activate(context))
                Text("After a reboot the phone goes back to Google's provider; run it again. A later milestone does this automatically over wireless debugging.", style = MaterialTheme.typography.bodySmall)
            }

            Section("2. Back to Google") {
                val reset = Commands.reset(context)
                if (reset != null) {
                    CommandBlock(reset)
                } else {
                    Text("No default provider found on this device, so there is nothing to reset to.")
                }
            }

            Section("Service component") {
                Text(Commands.serviceComponent(context), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("User id ${Commands.userId()}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
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
