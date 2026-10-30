package com.ataglanceplus.ui

import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import com.ataglanceplus.weather.WeatherLook
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
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Cards tab: which kinds of card may appear, and the access they need. Every change is
 * written to settings, and the service redraws the connected surfaces straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardPreviewSection() {
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

    // The card whose options are open, one at a time ("weather" for the weather card).
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Section("Cards") {
        Text(
            "Weather is always there; the others appear while they have something to show. Tap a card for its options.",
            style = MaterialTheme.typography.bodySmall
        )
        SwitchRow(
            "Show all cards",
            "Off: only as many as the launcher asks for.",
            Prefs.showAllCards(context),
        ) { Prefs.setShowAllCards(context, it) }
        // Card height: saved when the slider is let go, so the cards redraw once, not on every step.
        var height by remember(version) { androidx.compose.runtime.mutableFloatStateOf(Prefs.cardHeightDp(context).toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Card height · ${height.toInt()} dp")
                Text(
                    "${com.ataglanceplus.cards.Style.CARD_HEIGHT_DEFAULT_DP} dp by default. Taller cards are cut off where the launcher has no room.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = {
                height = com.ataglanceplus.cards.Style.CARD_HEIGHT_DEFAULT_DP.toFloat()
                Prefs.setCardHeightDp(context, com.ataglanceplus.cards.Style.CARD_HEIGHT_DEFAULT_DP)
            }) { Text("Reset") }
        }
        androidx.compose.material3.Slider(
            value = height,
            onValueChange = { height = it },
            onValueChangeFinished = { Prefs.setCardHeightDp(context, height.toInt()) },
            valueRange = 76f..140f,
            steps = 15,
        )
        if (!notificationAccess) {
            HorizontalDivider()
            Text("Notification access needed", style = MaterialTheme.typography.labelLarge)
            Text(
                "Media, live updates, navigation, timers and the message pop-up read what is on screen in your notifications. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

        HorizontalDivider()
        CardRow("Weather", "Always shown: the forecast, your chips and a summary line", expanded = open == "weather", onToggle = {
            open = if (open == "weather") null else "weather"
        })
        if (open == "weather") WeatherLookSettings(version)

        CardType.entries.forEach { type ->
            HorizontalDivider()
            val enabled = Prefs.cardEnabled(context, type)
            val options = enabled && type in WITH_OPTIONS
            CardRow(
                type.label,
                type.detail,
                expanded = if (options) open == type.id else null,
                onToggle = { open = if (open == type.id) null else type.id },
                checked = enabled,
                onCheckedChange = { Prefs.setCardEnabled(context, type, it) },
            )
            // Needed for the card to work at all, so shown without opening it.
            if (type == CardType.CALENDAR && enabled && !calendarAccess) {
                OutlinedButton(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar access") }
            }
            if (!options || open != type.id) return@forEach
            when (type) {
                CardType.CALENDAR -> {
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
                CardType.SPORTS -> SportsSettings(version)
                // Passing the settings version makes it redraw when any option changes.
                CardType.FINANCE -> FinanceSettings(version)
                CardType.WALLET -> WalletSettings(version)
                CardType.MEDIA -> SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val wave = Prefs.mediaWave(context)
                    listOf(true, false).forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = wave == option,
                            onClick = { Prefs.setMediaWave(context, option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                        ) { Text(if (option) "Equaliser" else "Progress bar") }
                    }
                }
                else -> Unit
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

/** The cards with options, opened by tapping their row. */
private val WITH_OPTIONS = setOf(CardType.CALENDAR, CardType.SPORTS, CardType.FINANCE, CardType.WALLET, CardType.MEDIA)

/**
 * A card in the list: its name and what it shows, a switch when it can be turned off, and an
 * arrow when it has options ([expanded] null when it has none); tapping the row opens them.
 */
@Composable
private fun CardRow(
    title: String,
    detail: String,
    expanded: Boolean?,
    onToggle: () -> Unit,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    val arrow by animateFloatAsState(if (expanded == true) 180f else 0f, label = "arrow")
    Row(
        modifier = Modifier.fillMaxWidth().then(if (expanded != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (expanded != null) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide options" else "Show options",
                modifier = Modifier.rotate(arrow),
            )
        }
        if (checked != null) Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The weather card's look: Standard, Scenes or Animated scenes. */
@Composable
private fun WeatherLookSettings(settingsVersion: Int) {
    val context = LocalContext.current
    val look = remember(settingsVersion) { Prefs.weatherLook(context) }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        WeatherLook.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = look == option,
                onClick = { Prefs.setWeatherLook(context, option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = WeatherLook.entries.size),
                // No tick: with three options it squeezes the labels; the fill shows the choice.
                icon = {}
            ) { Text(if (option == WeatherLook.ANIMATED) "Animated" else option.label, maxLines = 1) }
        }
    }
    Text(
        when (look) {
            WeatherLook.STANDARD -> "A slate-navy gradient with a soft blue wave."
            WeatherLook.SCENES -> "Tinted for the weather and time of day, with the sun, stars, clouds, fog, rain or snow drawn in."
            WeatherLook.ANIMATED -> "The scenes, moving. Uses more battery while the card is on screen."
        },
        style = MaterialTheme.typography.bodySmall
    )
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
private fun FinanceSettings(settingsVersion: Int) {
    // Read so this redraws when a setting changes: Compose ignores an unused parameter when deciding to skip.
    if (settingsVersion < 0) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by FinanceSource.status.collectAsStateWithLifecycle()
    val quotes by FinanceSource.quotes.collectAsStateWithLifecycle()
    // The screen's locale, read so the prices redraw if the phone's language changes.
    val locale = LocalConfiguration.current.locales[0]
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
                        String.format(locale, "%.2f %s · %+.2f%% today", it.price, it.currency, it.changePercent) +
                            (it.extended?.let { e -> String.format(locale, " · %s %.2f (%+.2f%%)", e.session.label.lowercase(locale), e.price, e.changePercent) } ?: ""),
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
                                locale, "Added %s · %s · %.2f %s", quote.symbol, quote.name, quote.price, quote.currency
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

    Text("The card is always up, updating as prices refresh. These bring it to the front:", style = MaterialTheme.typography.labelLarge)
    SwitchRow("Open stocks in this app", "Tapping a stock shows its dashboard here. Off: its Google Finance page.", Prefs.financeOpensDashboard(context)) {
        Prefs.setFinanceOpensDashboard(context, it)
    }
    SwitchRow("On big moves", "When a stock has moved this much today, or outside regular hours since the close.", Prefs.financeOnMoves(context)) { Prefs.setFinanceOnMoves(context, it) }
    if (Prefs.financeOnMoves(context)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val threshold = Prefs.financeThreshold(context)
            listOf(1, 2, 3, 5, 10).forEach { percent ->
                FilterChip(selected = threshold == percent, onClick = { Prefs.setFinanceThreshold(context, percent) }, label = { Text("$percent%") })
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
    // Whether the card is up now and why, from the same rules the cards use.
    Text(
        com.ataglanceplus.live.FinanceRules.explain(context, quotes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Teams and series to follow, and when their cards show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SportsSettings(settingsVersion: Int) {
    // Read so this redraws when a setting changes: Compose ignores an unused parameter when deciding to skip.
    if (settingsVersion < 0) return
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.ataglanceplus.live.Followed>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val followed = Prefs.sportsFollowed(context)

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { com.ataglanceplus.live.SportsSource.searchTeams(query) }.map { it.followed }
        searching = false
    }

    Text("Following", style = MaterialTheme.typography.bodySmall)
    if (followed.isEmpty()) Text("Nobody yet: search for a team below.", style = MaterialTheme.typography.bodySmall)
    followed.forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.leagueName, style = MaterialTheme.typography.bodySmall)
            }
            // Hides scores and results for this team or series, on the cards and dashboards.
            val hidden = f.key in Prefs.sportsHidden(context)
            FilterChip(selected = hidden, onClick = { Prefs.setSportsHidden(context, f.key, !hidden) }, label = { Text("Hide scores") })
            TextButton(onClick = { Prefs.setSportsFollowed(context, followed.filter { it.key != f.key }) }) { Text("Remove") }
        }
    }
    val f1 = com.ataglanceplus.live.Followed("racing", "f1", null, "Formula 1", "Formula 1")
    if (followed.none { it.key == f1.key }) {
        OutlinedButton(onClick = { Prefs.setSportsFollowed(context, followed + f1) }) { Text("Follow Formula 1") }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Search for a team") }, modifier = Modifier.fillMaxWidth()
    )
    if (searching) Text("Searching…", style = MaterialTheme.typography.bodySmall)
    if (query.isNotBlank() && !searching && results.isEmpty()) Text("No teams found.", style = MaterialTheme.typography.bodySmall)
    results.forEach { team ->
        val already = followed.any { it.key == team.key }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !already) {
                    Prefs.setSportsFollowed(context, followed + team)
                    query = ""
                }
                .padding(vertical = 6.dp)
        ) {
            Text(team.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(team.leagueName, team.sport.replaceFirstChar { it.uppercase() }).filter { it.isNotEmpty() }.joinToString(" · ") +
                    if (already) " · following" else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Text("Show a game's card", style = MaterialTheme.typography.labelLarge)
    Text("Before kick-off", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val before = Prefs.sportsBeforeHours(context)
        listOf(1, 2, 3, 6).forEach { h ->
            FilterChip(selected = before == h, onClick = { Prefs.setSportsBeforeHours(context, h) }, label = { Text("$h h") })
        }
    }
    Text("After full time", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val after = Prefs.sportsAfterHours(context)
        listOf(1, 2, 4).forEach { h ->
            FilterChip(selected = after == h, onClick = { Prefs.setSportsAfterHours(context, h) }, label = { Text("$h h") })
        }
    }
    SwitchRow("Next game when nothing's on", "Otherwise each team's card only shows around its games.", Prefs.sportsNextAlways(context)) {
        Prefs.setSportsNextAlways(context, it)
    }
    Text("Tapping a game opens", style = MaterialTheme.typography.bodySmall)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val open = Prefs.sportsOpen(context)
        Prefs.SportsOpen.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = open == option,
                onClick = { Prefs.setSportsOpen(context, option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Prefs.SportsOpen.entries.size),
                // No tick: with three options it squeezes the labels; the fill shows the choice.
                icon = {}
            ) { Text(option.label) }
        }
    }
    Text("Scores refresh every 30 seconds during a game. From ESPN's free public feed, which is unofficial and could change.", style = MaterialTheme.typography.bodySmall)
}

/** Google Wallet: sign in, and give each synced pass its places and times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletSettings(settingsVersion: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") settingsVersion

    val signedIn = remember(tick) { com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context) }
    val email = remember(tick) { com.ataglanceplus.wallet.GoogleAccount.email(context) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    if (!signedIn) {
        Text(
            "Show your Google Wallet passes on the deck — loyalty cards, tickets, boarding passes — near the places and at the times you choose.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Signing in uses the same workaround Smartspacer does: it signs in as Google Play services, which is against Google's terms. You'll see the full warning before anything happens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = { signIn.launch(android.content.Intent(context, com.ataglanceplus.wallet.WalletSignInActivity::class.java)) }) {
            Text("Sign in to Google Wallet")
        }
        return
    }

    val passes by com.ataglanceplus.wallet.WalletSource.passes.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Signed in", style = MaterialTheme.typography.labelLarge)
            if (!email.isNullOrEmpty()) Text(email, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { com.ataglanceplus.wallet.GoogleAccount.signOut(context); tick++ }) { Text("Sign out") }
    }

    val background = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    if (!background) {
        Text(
            "To show passes by place while the app is closed, allow location all the time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                fine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }) { Text("Allow location access") }
    }

    if (passes.isEmpty()) {
        Text("No passes yet — they sync from Google Wallet in the background. Pull down in a moment, or sync now.", style = MaterialTheme.typography.bodySmall)
    } else {
        passes.forEach { pass -> WalletPassRules(pass) { tick++ } }
    }
    OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { com.ataglanceplus.wallet.WalletSource.sync(context) } } }) {
        Text("Sync now")
    }
}

/** The places and times one Wallet pass shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletPassRules(pass: com.ataglanceplus.wallet.WalletPass, onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val timed = pass.kind in setOf(
        com.ataglanceplus.wallet.WalletPass.Kind.EVENT,
        com.ataglanceplus.wallet.WalletPass.Kind.FLIGHT,
        com.ataglanceplus.wallet.WalletPass.Kind.TRANSIT,
    )
    val rule = remember(tick) {
        com.ataglanceplus.wallet.WalletRules.ruleFor(context, pass.id)
            ?: com.ataglanceplus.wallet.WalletRules.Rule(pass.id, auto = timed)
    }
    fun save(updated: com.ataglanceplus.wallet.WalletRules.Rule) {
        com.ataglanceplus.wallet.WalletRules.upsert(context, updated); tick++; onChange()
    }

    HorizontalDivider()
    Text(pass.title, style = MaterialTheme.typography.labelLarge)
    pass.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (timed) {
        SwitchRow("Show automatically at its time", "Appears around the flight, event or journey time, like Google Wallet.", rule.auto) {
            save(rule.copy(auto = it))
        }
    }

    Text("Places", style = MaterialTheme.typography.labelMedium)
    rule.places.forEachIndexed { index, place ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.label.ifEmpty { "Place" }, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(rule.copy(places = rule.places.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 150, 250, 500).forEach { metres ->
                    FilterChip(
                        selected = place.radiusM == metres,
                        onClick = { save(rule.copy(places = rule.places.mapIndexed { i, p -> if (i == index) p.copy(radiusM = metres) else p })) },
                        label = { Text("$metres m") },
                    )
                }
            }
        }
    }
    OutlinedButton(onClick = {
        scope.launch {
            val location = currentLocation(context)
            if (location != null) {
                save(rule.copy(places = rule.places + com.ataglanceplus.wallet.WalletRules.Place("Here", location.latitude, location.longitude, 150)))
            }
        }
    }) { Text("Add current location") }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, android.location.Address>>>(emptyList()) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search a place or address") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    LaunchedEffect(query) {
        if (query.length < 3) {
            results = emptyList()
        } else {
            delay(400)
            results = geocode(context, query)
        }
    }
    results.forEach { (label, address) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                save(rule.copy(places = rule.places + com.ataglanceplus.wallet.WalletRules.Place(label.take(30), address.latitude, address.longitude, 150)))
                query = ""; results = emptyList()
            }) { Text("Add") }
        }
    }

    Text("Times", style = MaterialTheme.typography.labelMedium)
    fun hm(m: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    rule.windows.forEachIndexed { index, window ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${hm(window.startMin)} – ${hm(window.endMin)}", modifier = Modifier.weight(1f))
            TextButton(onClick = { save(rule.copy(windows = rule.windows.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = {
        android.app.TimePickerDialog(context, { _, sh, sm ->
            android.app.TimePickerDialog(context, { _, eh, em ->
                save(rule.copy(windows = rule.windows + com.ataglanceplus.wallet.WalletRules.Window(emptySet(), sh * 60 + sm, eh * 60 + em)))
            }, (sh + 1) % 24, sm, true).apply { setTitle("Until") }.show()
        }, 8, 0, true).apply { setTitle("From") }.show()
    }) { Text("Add a time window") }

    if (rule.places.isEmpty() && rule.windows.isEmpty() && !rule.auto) {
        Text("This pass won't show until you add a place, a time, or turn on its own time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** A current fix, or null if location isn't permitted or available. */
private suspend fun currentLocation(context: android.content.Context): android.location.Location? {
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("MissingPermission")
            val task = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            com.google.android.gms.tasks.Tasks.await(task)
        }.getOrNull()
    }
}

/** Addresses matching [query], as "label to address", on a background thread. */
private suspend fun geocode(context: android.content.Context, query: String): List<Pair<String, android.location.Address>> =
    withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(context, java.util.Locale.getDefault()).getFromLocationName(query, 5).orEmpty().map { address ->
                val label = (0..address.maxAddressLineIndex).joinToString(", ") { address.getAddressLine(it) }
                    .ifEmpty { listOfNotNull(address.featureName, address.locality).joinToString(", ") }
                label to address
            }
        }.getOrDefault(emptyList())
    }
