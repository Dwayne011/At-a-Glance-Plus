package com.ataglanceplus.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.cards.CardType
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.live.CalendarSource
import com.ataglanceplus.live.FinanceSource
import com.ataglanceplus.weather.WeatherLook
import com.ataglanceplus.weather.WeatherRepository
import com.ataglanceplus.cards.CardRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * Each card's own settings page: "Show the … card" first, then its settings in groups. Values
 * open a dialog; lists (stocks, teams, calendars) are rows with add and remove.
 */

/** "Show the … card": the same switch as on Home. */
@Composable
private fun ShowCardRow(type: CardType) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Subheader("${type.label} card")
    val on = Prefs.cardEnabled(context, type)
    SwitchSettingRow("Show the ${type.label.lowercase(locale)} card", if (on) "On — ${type.detail.replaceFirstChar { it.lowercase(locale) }}" else "Off", checked = on) {
        Prefs.setCardEnabled(context, type, it)
    }
}

// --- Weather ----------------------------------------------------------------------------------

@Composable
internal fun WeatherPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") version
    val weather by WeatherRepository.weather.collectAsStateWithLifecycle()
    val status by WeatherRepository.status.collectAsStateWithLifecycle()
    val hasLocation = remember(version, status) { WeatherRepository.hasLocationPermission(context) }
    val hasBackground = remember(version, status) { WeatherRepository.hasBackgroundLocationPermission(context) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { WeatherRepository.refresh(context, force = true) }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val look = Prefs.weatherLook(context)

    SettingsPage("Weather", onBack) {
        Subheader("Look")
        WeatherLook.entries.forEach { option ->
            FrostChoiceRow(
                option.label,
                when (option) {
                    WeatherLook.STANDARD -> "Your wallpaper's colours (Material You), light or dark with the phone's theme"
                    WeatherLook.SCENES -> "Tinted for the weather and time of day, with the sun, stars, clouds, fog, rain or snow drawn in"
                    WeatherLook.ANIMATED -> "The scenes, moving gently. Uses more battery while the card is on screen"
                },
                look == option,
            ) { Prefs.setWeatherLook(context, option) }
        }
        SettingNote("Always shown: the forecast, your chips and a summary line.")

        ShowCardRow(CardType.RAIN)
        SettingNote("Checked every 10 minutes while the screen is on, from Open-Meteo's 15-minute forecast. Tap the card for the weather screen and its radar.")

        Subheader("Tapping the card")
        SwitchSettingRow(
            "Open weather in this app",
            if (Prefs.weatherOpensDashboard(context)) "On — hourly rain, radar, UV, air quality, sun and the week" else "Off — Pixel Weather",
            glyph = FrostIcons.Weather, checked = Prefs.weatherOpensDashboard(context),
        ) { Prefs.setWeatherOpensDashboard(context, it) }

        Subheader("Location")
        when {
            !hasLocation -> SettingRow("Allow location", "Only your approximate location is sent, to Open-Meteo", icon = Icons.Default.LocationOn, error = true,
                onClick = { location.launch(Manifest.permission.ACCESS_COARSE_LOCATION) })
            !hasBackground -> SettingRow("Allow location in the background", "Otherwise the weather uses where you were when the app was last open. Choose \"Allow all the time\".",
                icon = Icons.Default.LocationOn, accent = true, onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) })
            // Whatever the permission, the forecast is asked for at a rounded position (about 1 km).
            else -> SettingRow(
                "Location",
                (if (com.ataglanceplus.wallet.WalletGeofences.hasPrecise(context)) "Precise" else "Approximate") +
                    ", all the time · only a rounded position (about 1 km) is sent to Open-Meteo",
                icon = Icons.Default.LocationOn,
            )
        }

        Subheader("Now")
        val now = weather
        SettingRow(
            "Refresh weather",
            if (now != null) "${now.condition.label}, ${now.temperatureText}. ${now.detailText}. Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now.fetchedAt))}"
            else status.ifEmpty { "No weather yet" },
            icon = Icons.Default.Refresh, enabled = hasLocation,
            onClick = { scope.launch { WeatherRepository.refresh(context, force = true) } },
        )
    }
}

// --- Calendar ---------------------------------------------------------------------------------

@Composable
internal fun CalendarPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Prefs.chipPermissionsChanged(context) }
    var choosingLead by remember { mutableStateOf(false) }
    val access = CalendarSource.hasPermission(context)

    SettingsPage("Calendar", onBack) {
        ShowCardRow(CardType.CALENDAR)
        if (!access) {
            SettingRow("Allow calendar access", "The card reads your events on the phone", icon = Icons.Default.DateRange, error = true,
                onClick = { permission.launch(Manifest.permission.READ_CALENDAR) })
        }
        Subheader("When it shows")
        LinkRow("Before an event", "From ${leadText(Prefs.calendarLeadMinutes(context))} before it starts, until it ends", icon = Icons.Default.DateRange) { choosingLead = true }
        SwitchSettingRow("All-day events", "Today's all-day event when nothing else is on", checked = Prefs.calendarAllDay(context)) { Prefs.setCalendarAllDay(context, it) }
        if (access) {
            Subheader("Calendars")
            val calendars = remember(version) { CalendarSource.calendars(context) }
            val hidden = Prefs.calendarHidden(context)
            calendars.forEach { cal ->
                SettingRow(
                    cal.name.ifEmpty { "(No name)" },
                    cal.account.takeIf { it.isNotEmpty() && it != cal.name },
                    lead = { Box(Modifier.size(14.dp).background(Color(cal.colour), CircleShape)) },
                    onClick = { Prefs.setCalendarHidden(context, cal.id, cal.id !in hidden) },
                    trailing = { FrostSwitch(checked = cal.id !in hidden, onCheckedChange = null) },
                )
            }
            SettingNote("A calendar added later shows until you turn it off.")
        }
    }
    if (choosingLead) {
        ChoiceDialog("Show before an event", listOf(15, 30, 60, 120), Prefs.calendarLeadMinutes(context), label = { leadText(it) }, onDismiss = { choosingLead = false }) {
            Prefs.setCalendarLeadMinutes(context, it); choosingLead = false
        }
    }
}

// --- Finance ----------------------------------------------------------------------------------

@Composable
internal fun FinancePage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val status by FinanceSource.status.collectAsStateWithLifecycle()
    val quotes by FinanceSource.quotes.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val tickers = Prefs.financeTickers(context)
    var choosingRefresh by remember { mutableStateOf(false) }
    var choosingThreshold by remember { mutableStateOf(false) }
    var editingVwap by remember { mutableStateOf(false) }
    var vwap by remember(version) { mutableStateOf(Prefs.financeVwap(context)) }
    var choosingAlertMove by remember { mutableStateOf(false) }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    SettingsPage("Finance", onBack) {
        ShowCardRow(CardType.FINANCE)
        Subheader("Stocks")
        tickers.forEach { ticker ->
            val quote = quotes.firstOrNull { it.symbol.equals(ticker, ignoreCase = true) }
            SettingRow(
                ticker,
                quote?.let {
                    "${it.name} · " + String.format(locale, "%.2f %s · %+.2f%% today", it.price, it.currency, it.changePercent) +
                        (it.extended?.let { e -> String.format(locale, " · %s %+.2f%%", e.session.label.lowercase(locale), e.changePercent) } ?: "")
                },
                iconRes = R.drawable.ic_comp_trend,
                trailing = { IconButton(onClick = { Prefs.setFinanceTickers(context, tickers - ticker) }) { Icon(Icons.Default.Close, contentDescription = "Remove") } },
            )
        }
        // Up to four stocks: the card takes turns between them and the stock screen swipes through them.
        if (tickers.size < Prefs.MAX_FINANCE_TICKERS) {
            SettingRow("Add a stock", "${tickers.size} of ${Prefs.MAX_FINANCE_TICKERS}", icon = Icons.Default.Add, accent = true, onClick = { open("finance/add") })
        } else {
            SettingNote("Following ${Prefs.MAX_FINANCE_TICKERS} stocks, the most there is room for. Remove one to add another.")
        }
        if (status.isNotEmpty()) SettingNote("Prices: $status")

        Subheader("Updates")
        LinkRow("Refresh prices every", refreshText(Prefs.financeRefreshMinutes(context)), icon = Icons.Default.Refresh) { choosingRefresh = true }
        SwitchSettingRow("Open stocks in this app", "Off: their Google Finance page", checked = Prefs.financeOpensDashboard(context)) { Prefs.setFinanceOpensDashboard(context, it) }

        Subheader("Bring to the front")
        SwitchSettingRow("On big moves", "When a stock moves ${Prefs.financeThreshold(context)}% or more today, or outside regular hours", checked = Prefs.financeOnMoves(context)) { Prefs.setFinanceOnMoves(context, it) }
        if (Prefs.financeOnMoves(context)) LinkRow("Big move", "${Prefs.financeThreshold(context)}% or more") { choosingThreshold = true }
        SwitchSettingRow("During time windows", "Ahead of other cards at the times you set", checked = Prefs.financeInWindows(context)) { Prefs.setFinanceInWindows(context, it) }
        if (Prefs.financeInWindows(context)) {
            val windows = Prefs.financeWindows(context)
            fun hm(m: Int) = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
            windows.forEach { window ->
                SettingRow("${hm(window.first)}–${hm(window.second)}", icon = Icons.Default.DateRange, trailing = {
                    IconButton(onClick = { Prefs.setFinanceWindows(context, windows - window) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                })
            }
            SettingRow("Add a time window", icon = Icons.Default.Add, accent = true, onClick = {
                android.app.TimePickerDialog(context, { _, sh, sm ->
                    android.app.TimePickerDialog(context, { _, eh, em ->
                        Prefs.setFinanceWindows(context, windows + ((sh * 60 + sm) to (eh * 60 + em)))
                    }, (sh + 1) % 24, sm, true).apply { setTitle("Until") }.show()
                }, 8, 0, true).apply { setTitle("From") }.show()
            })
        }
        SettingNote(com.ataglanceplus.live.FinanceRules.explain(context, quotes), MaterialTheme.colorScheme.primary)

        Subheader("Price alerts")
        SwitchSettingRow("Price alerts", "A notification when a stock crosses a level you set (on its screen), or makes a big move", checked = com.ataglanceplus.live.FinanceAlerts.enabled(context)) {
            com.ataglanceplus.live.FinanceAlerts.setEnabled(context, it)
            if (it && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (com.ataglanceplus.live.FinanceAlerts.enabled(context)) {
            val move = com.ataglanceplus.live.FinanceAlerts.movePercent(context)
            LinkRow("Alert on a day's move of", if (move > 0) "$move% or more" else "Off") { choosingAlertMove = true }
            val levels = com.ataglanceplus.live.FinanceAlerts.levels(context)
            SettingNote(if (levels.isEmpty()) "No price levels set. Add them on a stock's screen." else levels.joinToString(" · ") { "${it.symbol} ${if (it.above) "above" else "below"} " + String.format(locale, "%,.2f", it.price) })
        }

        Subheader("Accounts")
        LinkRow(
            "eToro",
            if (com.ataglanceplus.live.EtoroSource.hasKeys(context)) "Connected · used for \"What if\" on a stock's screen" else "Not connected · add your read-only keys",
            iconRes = R.drawable.ic_comp_trend,
        ) { open("finance/etoro") }

        Subheader("Chart")
        LinkRow("VWAP", "${vwap.source.label} · ${vwap.anchor.label}" + if (vwap.extendedHours) " · extended hours" else "") { editingVwap = true }
    }
    if (choosingRefresh) {
        ChoiceDialog("Refresh prices every", listOf(5, 15, 30, 60), Prefs.financeRefreshMinutes(context), label = { refreshText(it) }, onDismiss = { choosingRefresh = false }) {
            Prefs.setFinanceRefreshMinutes(context, it); choosingRefresh = false
        }
    }
    if (choosingThreshold) {
        ChoiceDialog("Big move", listOf(1, 2, 3, 5, 10), Prefs.financeThreshold(context), label = { "$it% or more" }, onDismiss = { choosingThreshold = false }) {
            Prefs.setFinanceThreshold(context, it); choosingThreshold = false
        }
    }
    if (choosingAlertMove) {
        ChoiceDialog("Alert on a day's move of", listOf(0, 3, 5, 10, 15), com.ataglanceplus.live.FinanceAlerts.movePercent(context), label = { if (it == 0) "Off" else "$it% or more" }, onDismiss = { choosingAlertMove = false }) {
            com.ataglanceplus.live.FinanceAlerts.setMovePercent(context, it); choosingAlertMove = false
        }
    }
    if (editingVwap) {
        VwapDialog(vwap, onDone = { editingVwap = false }) {
            vwap = it
            Prefs.setFinanceVwap(context, it)
        }
    }
}

/** A search page: a field at the top, its results as rows below. */
@Composable
private fun SearchPage(title: String, hint: String, query: String, onQuery: (String) -> Unit, onBack: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        TextField(
            value = query, onValueChange = onQuery,
            placeholder = { Text(hint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = LocalFrost.current.fill, unfocusedContainerColor = LocalFrost.current.fill),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(focus),
        )
        FrostScrollPage { content() }
    }
}

@Composable
internal fun AddStockPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FinanceSource.Match>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { FinanceSource.search(query) }
        searching = false
    }
    SearchPage("Add a stock", "Company or ticker", query, { query = it }, onBack) {
        if (message.isNotEmpty()) SettingNote(message, MaterialTheme.colorScheme.primary)
        val tickers = Prefs.financeTickers(context)
        results.forEach { match ->
            val added = tickers.any { it.equals(match.symbol, ignoreCase = true) }
            SettingRow(
                "${match.symbol} · ${match.name}",
                listOf(match.exchange, match.type).filter { it.isNotEmpty() }.joinToString(" · ") + if (added) " · already added" else "",
                iconRes = R.drawable.ic_comp_trend,
                enabled = !added,
                onClick = {
                    message = "Checking ${match.symbol}…"
                    scope.launch {
                        val quote = withContext(Dispatchers.IO) { FinanceSource.fetch(match.symbol) }
                        if (quote == null) {
                            message = "${match.symbol} has no price from the feed right now, so it was not added."
                        } else if (Prefs.financeTickers(context).size >= Prefs.MAX_FINANCE_TICKERS) {
                            message = "Already following ${Prefs.MAX_FINANCE_TICKERS} stocks. Remove one to add ${quote.symbol}."
                        } else {
                            Prefs.setFinanceTickers(context, Prefs.financeTickers(context) + quote.symbol)
                            message = String.format(locale, "Added %s · %.2f %s", quote.symbol, quote.price, quote.currency)
                            onBack()
                        }
                    }
                },
            )
        }
        if (searching) SettingNote("Searching…")
        if (query.isNotBlank() && !searching && results.isEmpty()) SettingNote("No matches.")
    }
}

// --- Sports -----------------------------------------------------------------------------------

private val F1 = com.ataglanceplus.live.Followed("racing", "f1", null, "Formula 1", "Formula 1")

@Composable
internal fun SportsPage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val followed = Prefs.sportsFollowed(context)
    var editing by remember { mutableStateOf<com.ataglanceplus.live.Followed?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    // Calendar access, then the calendar to add games to.
    val askCalendar = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        if (com.ataglanceplus.live.SportsCalendar.allowed(context)) choosing = "calendar"
    }

    SettingsPage("Sports", onBack) {
        ShowCardRow(CardType.SPORTS)
        Subheader("Following")
        followed.forEach { f ->
            val hidden = f.key in Prefs.sportsHidden(context)
            LinkRow(f.name, f.leagueName + if (hidden) " · results hidden" else " · results shown", icon = Icons.Default.Star) { editing = f }
        }
        if (followed.none { it.key == F1.key }) SettingRow("Follow Formula 1", icon = Icons.Default.Add, accent = true, onClick = { Prefs.setSportsFollowed(context, followed + F1) })
        SettingRow("Follow a team", icon = Icons.Default.Add, accent = true, onClick = { open("sports/add") })

        Subheader("When a game's card shows")
        LinkRow("Before kick-off", "${Prefs.sportsBeforeHours(context)} h") { choosing = "before" }
        LinkRow("After full time", "${Prefs.sportsAfterHours(context)} h") { choosing = "after" }
        SwitchSettingRow("Next game when nothing's on", "Otherwise each team's card only shows around its games", checked = Prefs.sportsNextAlways(context)) { Prefs.setSportsNextAlways(context, it) }
        SettingNote("A dismissed card comes back 30 minutes before the team's next game, or for Formula 1, an hour before the next session.")

        Subheader("Tapping a game")
        LinkRow("Opens", when (Prefs.sportsOpen(context)) {
            Prefs.SportsOpen.DASHBOARD -> "This app's dashboard"
            Prefs.SportsOpen.GOOGLE -> "Google's results"
            Prefs.SportsOpen.ESPN -> "ESPN's page"
        }) { choosing = "open" }
        SettingNote("Scores refresh every 30 seconds during a game. Tap a team or Formula 1 to hide its results (no spoilers) or stop following it.")

        Subheader("Game alerts")
        val alerts = com.ataglanceplus.live.SportsAlerts
        SwitchSettingRow("Game alerts", "Notifications for your teams' games, even with the screen off", checked = alerts.enabled(context)) {
            alerts.setEnabled(context, it)
            if (it && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (alerts.enabled(context)) {
            com.ataglanceplus.live.SportsAlerts.Moment.entries.forEach { m ->
                SwitchSettingRow(m.label, m.detail, checked = alerts.wants(context, m)) { alerts.setWants(context, m, it) }
            }
            SettingNote("While one of your teams plays, scores are checked every minute with the screen off (every 30 seconds with it on). Teams with hidden results only alert at kick-off. Formula 1 has no alerts.")
        }

        Subheader("Calendar")
        val calendar = com.ataglanceplus.live.SportsCalendar
        val calendarName by produceState<String?>(null, version) {
            value = withContext(Dispatchers.IO) { calendar.calendarId(context)?.let { id -> calendar.calendars(context).firstOrNull { it.id == id } }?.let { "${it.name} · ${it.account}" } }
        }
        SwitchSettingRow("Add games to calendar", "Your teams' games in the next 60 days, kept up to date. Turning this off removes the upcoming ones it added", checked = calendar.enabled(context)) { on ->
            if (on) {
                if (calendar.allowed(context)) choosing = "calendar"
                else askCalendar.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
            } else scope.launch(Dispatchers.IO) { calendar.turnOff(context) }
        }
        if (calendar.enabled(context)) {
            LinkRow("Calendar", calendarName ?: "Not found") { choosing = "calendar" }
            SettingNote("Only games this app added are changed or removed. A game you delete from your calendar isn't added again.")
        }
    }

    when (choosing) {
        "before" -> ChoiceDialog("Before kick-off", listOf(1, 2, 3, 6), Prefs.sportsBeforeHours(context), label = { "$it h" }, onDismiss = { choosing = null }) {
            Prefs.setSportsBeforeHours(context, it); choosing = null
        }
        "after" -> ChoiceDialog("After full time", listOf(1, 2, 4), Prefs.sportsAfterHours(context), label = { "$it h" }, onDismiss = { choosing = null }) {
            Prefs.setSportsAfterHours(context, it); choosing = null
        }
        "open" -> ChoiceDialog(
            "Tapping a game opens", Prefs.SportsOpen.entries, Prefs.sportsOpen(context),
            label = { when (it) { Prefs.SportsOpen.DASHBOARD -> "This app's dashboard"; Prefs.SportsOpen.GOOGLE -> "Google's results"; Prefs.SportsOpen.ESPN -> "ESPN's page" } },
            onDismiss = { choosing = null },
        ) { Prefs.setSportsOpen(context, it); choosing = null }
    }
    if (choosing == "calendar") {
        val calendars by produceState<List<com.ataglanceplus.live.SportsCalendar.Calendar>?>(null) {
            value = withContext(Dispatchers.IO) { com.ataglanceplus.live.SportsCalendar.calendars(context) }
        }
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text("Add games to") },
            text = {
                Column {
                    when (val list = calendars) {
                        null -> Text("Loading…")
                        else -> if (list.isEmpty()) Text("No Google calendar on this phone can take new events.") else list.forEach { cal ->
                            SettingRow(cal.name, cal.account, icon = Icons.Default.DateRange, onClick = {
                                choosing = null
                                scope.launch(Dispatchers.IO) {
                                    if (com.ataglanceplus.live.SportsSource.games.value.isEmpty()) runCatching { com.ataglanceplus.live.SportsSource.refreshSchedules(context) }
                                    com.ataglanceplus.live.SportsCalendar.choose(context, cal.id)
                                }
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } },
        )
    }
    editing?.let { f ->
        val hidden = f.key in Prefs.sportsHidden(context)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.name) },
            text = {
                Column {
                    SwitchSettingRow(
                        "Hide results",
                        if (f.teamId == null) "Positions, podiums, standings and session results, on the cards and dashboards, until you reveal them"
                        else "Scores and results, on the cards and dashboards, until you reveal them",
                        checked = hidden,
                    ) { Prefs.setSportsHidden(context, f.key, it) }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = { Prefs.setSportsFollowed(context, followed.filter { it.key != f.key }); editing = null }) {
                    Text("Stop following", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
internal fun AddTeamPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.ataglanceplus.live.Followed>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { com.ataglanceplus.live.SportsSource.searchTeams(query) }.map { it.followed }
        searching = false
    }
    SearchPage("Follow a team", "Team name", query, { query = it }, onBack) {
        val followed = Prefs.sportsFollowed(context)
        results.forEach { team ->
            val already = followed.any { it.key == team.key }
            SettingRow(
                team.name,
                listOf(team.leagueName, team.sport.replaceFirstChar { it.uppercase() }).filter { it.isNotEmpty() }.joinToString(" · ") + if (already) " · following" else "",
                icon = Icons.Default.Star,
                enabled = !already,
                onClick = { Prefs.setSportsFollowed(context, followed + team); onBack() },
            )
        }
        if (searching) SettingNote("Searching…")
        if (query.isNotBlank() && !searching && results.isEmpty()) SettingNote("No teams found.")
    }
}

// --- Media, more cards, Wallet sign-in ------------------------------------------------------------

@Composable
internal fun MediaPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    var choosing by remember { mutableStateOf(false) }
    SettingsPage("Media", onBack) {
        ShowCardRow(CardType.MEDIA)
        if (!NotificationCounter.isEnabled(context)) NotificationAccessRow()
        Subheader("Look")
        LinkRow("While playing", if (Prefs.mediaWave(context)) "Equaliser" else "Progress bar") { choosing = true }
        SettingNote("The card stays 5 minutes after you pause.")
    }
    if (choosing) {
        ChoiceDialog("While playing", listOf(true, false), Prefs.mediaWave(context), label = { if (it) "Equaliser" else "Progress bar" }, onDismiss = { choosing = false }) {
            Prefs.setMediaWave(context, it); choosing = false
        }
    }
}

@Composable
internal fun MoreCardsPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    SettingsPage("More cards", onBack) {
        if (!NotificationCounter.isEnabled(context)) NotificationAccessRow()
        Subheader("Cards")
        MORE_CARDS.forEach { type ->
            SwitchSettingRow(
                type.label, type.detail,
                iconRes = when (type) {
                    CardType.NAV -> R.drawable.ic_comp_route
                    CardType.LIVE -> R.drawable.ic_comp_bell
                    CardType.TIMER -> R.drawable.ic_comp_stopwatch
                    CardType.MESSAGES -> R.drawable.ic_comp_chat
                    else -> R.drawable.ic_comp_flame
                },
                checked = Prefs.cardEnabled(context, type),
            ) { Prefs.setCardEnabled(context, type, it) }
        }
    }
}

/** Wallet passes before signing in: what it does, the warning, and the sign-in. */
@Composable
internal fun WalletSignInPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)) {
            onBack()
            context.startActivity(android.content.Intent(context, WalletSettingsActivity::class.java))
        }
    }
    SettingsPage("Wallet passes", onBack) {
        ShowCardRow(CardType.WALLET)
        Subheader("Google Wallet")
        SettingNote("Show your Google Wallet passes on the cards — loyalty cards, tickets, boarding passes — at the places and times you choose.")
        SettingRow(
            "Sign in to Google Wallet",
            "It signs in the way Smartspacer does, as Google Play services, which is against Google's terms. You'll see the full warning first.",
            icon = Icons.Default.Info, accent = true,
            onClick = { signIn.launch(android.content.Intent(context, com.ataglanceplus.wallet.WalletSignInActivity::class.java)) },
        )
    }
}

// --- Commute --------------------------------------------------------------------------------

/** The commute card: home and work, when it shows each way, and what tapping it starts. */
@Composable
internal fun CommutePage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val commute = com.ataglanceplus.live.CommuteSource
    var editing by remember { mutableStateOf<Boolean?>(null) }
    SettingsPage("Commute", onBack) {
        ShowCardRow(CardType.COMMUTE)
        Subheader("Places")
        val home = commute.home(context)
        val work = commute.work(context)
        LinkRow("Home", home?.label ?: "Not set", glyph = FrostIcons.Home, error = home == null) { open("commute/place/home") }
        LinkRow("Work", work?.label ?: "Not set", glyph = FrostIcons.Pin, error = work == null) { open("commute/place/work") }
        Subheader("When it shows")
        LinkRow("To work", CardRules.describe(commute.toWorkWindow(context)), glyph = FrostIcons.Calendar) { editing = true }
        LinkRow("To home", CardRules.describe(commute.toHomeWindow(context)), glyph = FrostIcons.Calendar) { editing = false }
        SettingNote("From where you are (or the other place), every 5 minutes in these windows while the screen is on. Not shown while navigating or once you're there.")
        Subheader("Tapping the card")
        FrostChoiceRow("Public transport directions", "Google Maps' journeys by train, Tube and bus", !commute.tapDrives(context)) { commute.setTapDrives(context, false) }
        FrostChoiceRow("Start driving navigation", "Google Maps' turn-by-turn, straight away", commute.tapDrives(context)) { commute.setTapDrives(context, true) }
        SettingNote("Journeys by public transport from TfL's journey planner (London and around); driving times from OSRM, without live traffic.")
    }
    editing?.let { toWork ->
        WindowDialog(
            if (toWork) "To work" else "To home",
            if (toWork) commute.toWorkWindow(context) else commute.toHomeWindow(context),
            onDismiss = { editing = null },
        ) {
            if (toWork) commute.setToWorkWindow(context, it) else commute.setToHomeWindow(context, it)
            editing = null
        }
    }
}

/** Home or work: search for a place or address, or use where the phone is now. */
@Composable
internal fun CommutePlacePage(home: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.ataglanceplus.wallet.PlaceSearch.Suggestion>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = withContext(Dispatchers.IO) { runCatching { com.ataglanceplus.wallet.PlaceSearch.suggest(context, query, com.ataglanceplus.wallet.WalletGeofences.cachedLocation) }.getOrDefault(emptyList()) }
    }
    fun save(p: com.ataglanceplus.live.CommuteSource.Place) {
        if (home) com.ataglanceplus.live.CommuteSource.setHome(context, p) else com.ataglanceplus.live.CommuteSource.setWork(context, p)
        onBack()
    }
    SearchPage(if (home) "Home" else "Work", "Address or place", query, { query = it }, onBack) {
        SettingRow("Use where I am now", glyph = FrostIcons.Pin, onClick = {
            scope.launch {
                val here = com.ataglanceplus.wallet.WalletGeofences.cachedLocation ?: com.ataglanceplus.weather.WeatherRepository.lastLocation(context)?.let { (a, o) ->
                    android.location.Location("saved").apply { latitude = a; longitude = o }
                }
                if (here != null) save(com.ataglanceplus.live.CommuteSource.Place("Set from your location (${String.format(Locale.ROOT, "%.4f, %.4f", here.latitude, here.longitude)})", here.latitude, here.longitude))
            }
        })
        results.forEach { r ->
            SettingRow(r.name, r.detail.ifEmpty { null }, glyph = FrostIcons.Pin, onClick = {
                save(com.ataglanceplus.live.CommuteSource.Place(listOf(r.name, r.detail).filter { it.isNotEmpty() }.joinToString(", "), r.lat, r.lng))
            })
        }
        val current = if (home) com.ataglanceplus.live.CommuteSource.home(context) else com.ataglanceplus.live.CommuteSource.work(context)
        if (current != null) SettingRow("Clear", current.label, glyph = FrostIcons.Close, error = true, onClick = {
            if (home) com.ataglanceplus.live.CommuteSource.setHome(context, null) else com.ataglanceplus.live.CommuteSource.setWork(context, null)
            onBack()
        })
    }
}

// --- Parcels --------------------------------------------------------------------------------

/** The parcels card, and the Google sign-in it (and the Gmail chip's mini inbox) reads Gmail with. */
@Composable
internal fun ParcelsPage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val signedIn = com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)
    SettingsPage("Parcels", onBack) {
        ShowCardRow(CardType.PARCELS)
        Subheader("Gmail")
        if (signedIn) {
            SettingRow("Reading Gmail as", com.ataglanceplus.wallet.GoogleAccount.email(context).orEmpty() + " · read-only", glyph = FrostIcons.Person)
        } else {
            LinkRow("Sign in to Google", "The same sign-in as Wallet passes. Until then, parcels come from Live Updates only", glyph = FrostIcons.Person, error = true) { open("card/wallet") }
        }
        SettingRow("Show all parcels", "The list, with each one's stage", glyph = FrostIcons.Lines, onClick = {
            context.startActivity(DashboardActivity.intent(context, DashboardActivity.KIND_PARCELS))
        })
        SettingNote(
            "From shipping emails in the last 14 days (checked every 15 minutes while the screen is on) and from Live Updates that " +
                "read like a parcel. The card shows the one most nearly here, and stays until it's delivered. Mail is read, never changed, " +
                "and stays on the phone. The Gmail chip opens a mini inbox with this sign-in too.",
        )
    }
}
