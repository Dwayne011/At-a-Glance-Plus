package com.ataglanceplus.ui

import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
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
import com.ataglanceplus.live.FinanceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            if (type == CardType.FINANCE && Prefs.cardEnabled(context, type)) {
                FinanceSettings()
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

/** Tickers, how often prices refresh, and when the finance card shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinanceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by FinanceSource.status.collectAsStateWithLifecycle()
    val quotes by FinanceSource.quotes.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FinanceSource.Match>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val tickers = Prefs.financeTickers(context)

    // Search as the user types, once they pause.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { FinanceSource.search(query) }
        searching = false
    }

    Text("Stocks", style = MaterialTheme.typography.bodySmall)
    tickers.forEach { ticker ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val quote = quotes.firstOrNull { it.symbol.equals(ticker, ignoreCase = true) }
            Column(modifier = Modifier.weight(1f)) {
                Text(ticker + (quote?.let { " · ${it.name}" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                quote?.let {
                    Text(
                        String.format(java.util.Locale.getDefault(), "%.2f %s · %+.2f%% today", it.price, it.currency, it.changePercent),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            TextButton(onClick = { Prefs.setFinanceTickers(context, tickers - ticker) }) { Text("Remove") }
        }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Search by company or ticker") }, modifier = Modifier.fillMaxWidth()
    )
    if (searching) Text("Searching…", style = MaterialTheme.typography.bodySmall)
    if (query.isNotBlank() && !searching && results.isEmpty()) Text("No matches.", style = MaterialTheme.typography.bodySmall)
    results.forEach { match ->
        val added = tickers.any { it.equals(match.symbol, ignoreCase = true) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !added) {
                    message = "Checking ${match.symbol}…"
                    scope.launch {
                        val quote = withContext(Dispatchers.IO) { FinanceSource.fetch(match.symbol) }
                        if (quote == null) {
                            message = "${match.symbol} has no price from the feed right now, so it was not added."
                        } else {
                            Prefs.setFinanceTickers(context, Prefs.financeTickers(context) + quote.symbol)
                            message = String.format(
                                java.util.Locale.getDefault(), "Added %s · %s · %.2f %s", quote.symbol, quote.name, quote.price, quote.currency
                            )
                            query = ""
                        }
                    }
                }
                .padding(vertical = 6.dp)
        ) {
            Text("${match.symbol} · ${match.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(match.exchange, match.type).filter { it.isNotEmpty() }.joinToString(" · ") + if (added) " · already added" else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (status.isNotEmpty()) Text("Prices: $status", style = MaterialTheme.typography.bodySmall)

    Text("Refresh prices every", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val refresh = Prefs.financeRefreshMinutes(context)
        listOf(5, 15, 30, 60).forEach { minutes ->
            FilterChip(selected = refresh == minutes, onClick = { Prefs.setFinanceRefreshMinutes(context, minutes) },
                label = { Text(if (minutes < 60) "$minutes min" else "1 h") })
        }
    }

    Text("Show the card", style = MaterialTheme.typography.labelLarge)
    SwitchRow("On big moves", "When a stock has moved this much today.", Prefs.financeOnMoves(context)) { Prefs.setFinanceOnMoves(context, it) }
    if (Prefs.financeOnMoves(context)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val threshold = Prefs.financeThreshold(context)
            listOf(1, 2, 3, 5, 10).forEach { percent ->
                FilterChip(selected = threshold == percent, onClick = { Prefs.setFinanceThreshold(context, percent) }, label = { Text("$percent%") })
            }
        }
    }
    SwitchRow("Every few hours", "For 10 minutes at the start of each period.", Prefs.financePeriodic(context)) { Prefs.setFinancePeriodic(context, it) }
    if (Prefs.financePeriodic(context)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val hours = Prefs.financePeriodHours(context)
            listOf(1, 2, 3, 4, 6).forEach { h ->
                FilterChip(selected = hours == h, onClick = { Prefs.setFinancePeriodHours(context, h) }, label = { Text("Every $h h") })
            }
        }
    }
    SwitchRow("During time windows", "For example 08:00 to 09:00, and around the market close.", Prefs.financeInWindows(context)) { Prefs.setFinanceInWindows(context, it) }
    if (Prefs.financeInWindows(context)) {
        val windows = Prefs.financeWindows(context)
        fun hm(m: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
        windows.forEach { window ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hm(window.first)} – ${hm(window.second)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { Prefs.setFinanceWindows(context, windows - window) }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = {
            // Start time, then end time.
            android.app.TimePickerDialog(context, { _, sh, sm ->
                android.app.TimePickerDialog(context, { _, eh, em ->
                    Prefs.setFinanceWindows(context, windows + ((sh * 60 + sm) to (eh * 60 + em)))
                }, (sh + 1) % 24, sm, true).apply { setTitle("Until") }.show()
            }, 8, 0, true).apply { setTitle("From") }.show()
        }) { Text("Add a time window") }
    }
    if (!Prefs.financeOnMoves(context) && !Prefs.financePeriodic(context) && !Prefs.financeInWindows(context)) {
        Text("Turn on at least one of these, or the card never shows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
