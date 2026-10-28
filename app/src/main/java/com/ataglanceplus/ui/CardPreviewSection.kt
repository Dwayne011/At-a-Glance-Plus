package com.ataglanceplus.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.CardType
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.live.CalendarSource
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.Wadb
import kotlinx.coroutines.launch

/**
 * The Cards tab: which kinds of card may appear, and the access they need. Every change is
 * written to settings, and the service redraws the connected surfaces straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardPreviewSection(maxCards: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    // Re-read whenever any setting changes, including from the card buttons.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = Prefs.get(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (version < 0) return
    var grantMessage by remember { mutableStateOf("") }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Prefs.chipPermissionsChanged(context)
    }

    val notificationAccess = NotificationCounter.isEnabled(context)
    val calendarAccess = CalendarSource.hasPermission(context)
    val dismissed = Prefs.dismissed(context)

    Section("Cards") {
        Text(
            "The weather card is always there. Every other card appears only while it has something real to show, most time-sensitive first, and goes away by itself.",
            style = MaterialTheme.typography.bodySmall
        )
        if (maxCards > 0) {
            Text("At a Glance shows up to $maxCards cards at once.", style = MaterialTheme.typography.bodySmall)
        }

        if (!notificationAccess) {
            HorizontalDivider()
            Text("Notification access needed", style = MaterialTheme.typography.labelLarge)
            Text(
                "Media, live updates, navigation, timers and the message pop-up read what is on screen in your notifications. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        grantMessage = "Granting over wireless debugging…"
                        val outcome = Activator.grant(context, Commands.chipGrantSteps(context, notifications = true, usage = false, callLog = false))
                        grantMessage = if (outcome is Activator.Outcome.Failure) outcome.reason else "Done."
                        Prefs.chipPermissionsChanged(context)
                    }
                }) { Text("Grant") }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationCounter.component(context).flattenToString())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Or open the setting") }
            }
            Text("Grant uses wireless debugging (Wi-Fi needed).", style = MaterialTheme.typography.bodySmall)
        }
        if (grantMessage.isNotEmpty()) {
            Text(grantMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        CardType.entries.forEach { type ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(type.label, style = MaterialTheme.typography.labelLarge)
                    Text(type.detail, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = Prefs.cardEnabled(context, type), onCheckedChange = { Prefs.setCardEnabled(context, type, it) })
            }
            if (type == CardType.CALENDAR && Prefs.cardEnabled(context, type)) {
                if (!calendarAccess) {
                    OutlinedButton(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar access") }
                }
                Text("Show this long before an event starts", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lead = Prefs.calendarLeadMinutes(context)
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        FilterChip(
                            selected = lead == minutes,
                            onClick = { Prefs.setCalendarLeadMinutes(context, minutes) },
                            label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60} h") }
                        )
                    }
                }
                SwitchRow("All-day events", "Show today's all-day event when nothing else is on.", Prefs.calendarAllDay(context)) {
                    Prefs.setCalendarAllDay(context, it)
                }
            }
            if (type == CardType.MEDIA && Prefs.cardEnabled(context, type)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val wave = Prefs.mediaWave(context)
                    listOf(true, false).forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = wave == option,
                            onClick = { Prefs.setMediaWave(context, option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                        ) { Text(if (option) "Equaliser" else "Progress bar") }
                    }
                }
            }
        }

        HorizontalDivider()
        SwitchRow("Always-on look on the lock screen", "Shows the always-on display style with the screen on. It switches by itself when the screen turns off.", Prefs.aodPreview(context)) {
            Prefs.setAodPreview(context, it)
        }
        if (dismissed.isNotEmpty()) {
            OutlinedButton(onClick = { Prefs.restoreDismissed(context) }) { Text("Bring back dismissed cards (${dismissed.size})") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
