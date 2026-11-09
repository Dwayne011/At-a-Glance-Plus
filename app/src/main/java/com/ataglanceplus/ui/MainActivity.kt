package com.ataglanceplus.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.cards.CardType
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.service.ServiceState
import com.ataglanceplus.wadb.Activator
import com.ataglanceplus.wadb.PairingService
import com.ataglanceplus.wadb.Wadb
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Over the wallpaper (the window shows it), with the frost veil; colours follow the
            // phone's theme, light or dark.
            FrostTheme {
                Box(Modifier.fillMaxSize().background(LocalFrost.current.veil)) {
                    Box(Modifier.safeDrawingPadding()) { SettingsApp() }
                }
            }
        }
    }
}

/**
 * Settings as one list (Material's settings pattern) instead of tabs: Home, and a page for each
 * card, the chips, Backup and Advanced. [page] names the page open; back goes up a level.
 */
@Composable
private fun SettingsApp() {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
    var etoroFrom by rememberSaveable { mutableStateOf("home") }
    // Bumped whenever a setting changes or the app comes back, so every page reads afresh.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = Prefs.get(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) version++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun up() {
        page = when (page) {
            "finance/add" -> "card/finance"
            "finance/etoro" -> etoroFrom
            "sports/add" -> "card/sports"
            "advanced/debug" -> "advanced"
            "order", "quiet" -> "display"
            "commute/place/home", "commute/place/work" -> "card/commute"
            else -> "home"
        }
    }
    BackHandler(enabled = page != "home") { up() }
    val open: (String) -> Unit = {
        // The eToro page opens from home and from Finance; back returns to whichever.
        if (it == "finance/etoro") etoroFrom = page
        page = it
    }

    when (page) {
        "home" -> HomePage(version, open)
        "card/weather" -> WeatherPage(version, ::up)
        "card/calendar" -> CalendarPage(version, ::up)
        "card/finance" -> FinancePage(version, ::up, open)
        "finance/add" -> AddStockPage(::up)
        "finance/etoro" -> EtoroAccountPage(::up)
        "card/sports" -> SportsPage(version, ::up, open)
        "sports/add" -> AddTeamPage(::up)
        "card/media" -> MediaPage(version, ::up)
        "card/wallet" -> WalletSignInPage(version, ::up)
        "more" -> MoreCardsPage(version, ::up)
        "chips" -> ChipsSection(::up)
        "backup" -> BackupPage(::up)
        "advanced" -> AdvancedPage(version, ::up, open)
        "advanced/debug" -> DebugPage(version, ::up)
        "display" -> DisplayPage(version, ::up, open)
        "order" -> CardOrderPage(version, ::up)
        "card/commute" -> CommutePage(version, ::up, open)
        "card/parcels" -> ParcelsPage(version, ::up, open)
        "commute/place/home" -> CommutePlacePage(home = true, onBack = ::up)
        "commute/place/work" -> CommutePlacePage(home = false, onBack = ::up)
        "quiet" -> QuietHoursPage(version, ::up)
        "about" -> AboutPage(version, ::up)
        else -> HomePage(version, open)
    }
}

/** A page: its frost header, then its groups as frost cards, scrolling. */
@Composable
internal fun SettingsPage(title: String, onBack: (() -> Unit)?, overline: String = "Settings", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(title, onBack, overline)
        FrostColumn(Modifier.fillMaxWidth()) { content() }
        Spacer(Modifier.height(24.dp))
    }
}

// --- Home -------------------------------------------------------------------------------------

/**
 * Home: whether the app is showing (with the one button that matters now), anything that needs
 * fixing, then the cards, the chips, the display, and the system.
 */
@Composable
internal fun HomePage(version: Int, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    val weatherStatus by com.ataglanceplus.weather.WeatherRepository.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { com.ataglanceplus.weather.WeatherRepository.refresh(context, force = true) }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Prefs.chipPermissionsChanged(context) }

    SettingsPage("Settings", null, overline = "At a Glance Plus") {
        StatusBanner(version)
        FinishSetupCard(version)

        // Only what needs doing: permissions a switched-on card can't work without, device problems, errors.
        val notifications = NotificationCounter.isEnabled(context)
        val needsNotifications = !notifications && listOf(CardType.MEDIA, CardType.NAV, CardType.LIVE, CardType.TIMER, CardType.MESSAGES).any { Prefs.cardEnabled(context, it) }
        val hasLocation = remember(version, weatherStatus) { com.ataglanceplus.weather.WeatherRepository.hasLocationPermission(context) }
        val calendarNeeded = Prefs.cardEnabled(context, CardType.CALENDAR) && !com.ataglanceplus.live.CalendarSource.hasPermission(context)
        val errors = remember(version) { com.ataglanceplus.CrashLog.read(context) }
        val provider = remember(version) { Commands.lookupDefaultProvider(context) }
        val deviceOk = Commands.isPixelLauncherInstalled(context) && provider is Commands.Provider.Found
        if (needsNotifications || !hasLocation || calendarNeeded || !deviceOk || errors.isNotEmpty()) {
            Subheader("Needs attention")
            if (needsNotifications) NotificationAccessRow()
            if (!hasLocation) SettingRow("Allow location for the weather", "Only your approximate location is sent, to Open-Meteo", icon = Icons.Default.LocationOn, error = true,
                onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) })
            if (calendarNeeded) SettingRow("Allow calendar access", "For the calendar card", icon = Icons.Default.DateRange, error = true,
                onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) })
            if (!deviceOk) LinkRow("This phone may not be supported", "See Advanced › Device", icon = Icons.Default.Warning, error = true) { open("advanced") }
            if (errors.isNotEmpty()) LinkRow("Errors recorded", "${errors.lines().count { it.startsWith("=== ") }.coerceAtLeast(1)} · see them in Advanced › Debug", icon = Icons.Default.Warning, error = true) { open("advanced/debug") }
        }

        // A menu of sections, as in Pixel Launcher Plus's settings; each card's page has its switch.
        fun onOff(type: CardType, summary: String) = if (Prefs.cardEnabled(context, type)) "On · $summary" else "Off"
        Subheader("Cards")
        MenuRow(FrostIcons.Weather, "Weather", weatherSummary(context), Tone.PRIMARY) { open("card/weather") }
        MenuRow(FrostIcons.Calendar, "Calendar", onOff(CardType.CALENDAR, calendarSummary(context)), Tone.PRIMARY) { open("card/calendar") }
        MenuRow(FrostIcons.Ball, "Sports", onOff(CardType.SPORTS, sportsSummary(context)), Tone.SECONDARY) { open("card/sports") }
        MenuRow(FrostIcons.Ticket, "Wallet", onOff(CardType.WALLET, walletSummary(context)), Tone.TERTIARY) {
            if (com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)) context.startActivity(Intent(context, WalletSettingsActivity::class.java))
            else open("card/wallet")
        }
        val commuteSet = com.ataglanceplus.live.CommuteSource.home(context) != null && com.ataglanceplus.live.CommuteSource.work(context) != null
        MenuRow(FrostIcons.Home, "Commute", onOff(CardType.COMMUTE, if (commuteSet) "to work and home at your times" else "set home and work"), Tone.SECONDARY) { open("card/commute") }
        MenuRow(FrostIcons.Parcel, "Parcels", onOff(CardType.PARCELS, if (com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)) "Gmail and Live Updates" else "Live Updates; sign in for Gmail"), Tone.TERTIARY) { open("card/parcels") }
        MenuRow(FrostIcons.Play, "Media", onOff(CardType.MEDIA, if (Prefs.mediaWave(context)) "equaliser · stays 5 min after pausing" else "progress bar · stays 5 min after pausing"), Tone.TERTIARY) { open("card/media") }
        val more = MORE_CARDS.filter { Prefs.cardEnabled(context, it) }
        MenuRow(FrostIcons.Apps, "More cards", if (more.isEmpty()) "All off" else more.joinToString(", ") { it.label }, Tone.NEUTRAL) { open("more") }

        // Finance on its own: the stocks card, and the accounts the stock screens and the eToro chip read.
        Subheader("Finance")
        MenuRow(FrostIcons.Trend, "Stocks", onOff(CardType.FINANCE, financeSummary(context)), Tone.SECONDARY) { open("card/finance") }
        MenuRow(FrostIcons.Sliders, "eToro", etoroSummary(context), Tone.SECONDARY) { open("finance/etoro") }

        Subheader("Chips")
        val chips = remember(version) { Prefs.chips(context).orEmpty() }
        MenuRow(
            FrostIcons.Gauge, "Chips",
            if (chips.isEmpty()) "None chosen: the cards show examples"
            else "${chips.size} chosen · " + chips.take(3).joinToString(", ") { it.kind.label } + if (chips.size > 3) "…" else "",
            Tone.TERTIARY,
        ) { open("chips") }

        Subheader("Display")
        MenuRow(
            FrostIcons.Height, "Display",
            "Card height ${Prefs.cardHeightDp(context)} dp" + (if (Prefs.showAllCards(context)) " · all cards" else "") +
                (if (com.ataglanceplus.cards.CardRules.orderOn(context)) " · smarter order" else "") +
                if (com.ataglanceplus.cards.CardRules.quietRules(context).isNotEmpty()) " · quiet hours" else "",
            Tone.PRIMARY,
        ) { open("display") }

        Subheader("About")
        MenuRow(FrostIcons.Share, "Backup", "Back up to Google now, export or import", Tone.NEUTRAL) { open("backup") }
        MenuRow(FrostIcons.Tool, "Advanced", "Connection, debug and history", Tone.NEUTRAL) { open("advanced") }
        val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
        MenuRow(FrostIcons.Info, "About", (if (Prefs.isPaired(context)) "Re-activate after a restart · " else "") + "version $versionName", Tone.NEUTRAL) { open("about") }
    }
}

/** Display: the cards' height, how many show, and the always-on look. */
@Composable
private fun DisplayPage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    var editingHeight by remember { mutableStateOf(false) }
    SettingsPage("Display", onBack) {
        Subheader("Cards")
        LinkRow("Card height", "${Prefs.cardHeightDp(context)} dp", glyph = FrostIcons.Height) { editingHeight = true }
        SwitchSettingRow("Show all cards", if (Prefs.showAllCards(context)) "On — every card that has something to show" else "Off — only as many as the launcher asks for", glyph = FrostIcons.Apps, checked = Prefs.showAllCards(context)) { Prefs.setShowAllCards(context, it) }
        SwitchSettingRow("Always-on look on the lock screen", "The always-on display style with the screen on", glyph = FrostIcons.Lock, checked = Prefs.aodPreview(context)) { Prefs.setAodPreview(context, it) }
        // Dismissed cards, and sports cards snoozed until their next start.
        val hidden = Prefs.dismissed(context).size + Prefs.sportsSnoozes(context).size
        if (hidden > 0) SettingRow("Bring back dismissed cards", "$hidden hidden", glyph = FrostIcons.Refresh, onClick = { Prefs.restoreDismissed(context) })

        Subheader("Order and quiet hours")
        val windows = com.ataglanceplus.cards.CardRules.orderWindows(context).size
        LinkRow(
            "Card order",
            if (!com.ataglanceplus.cards.CardRules.orderOn(context)) "Off — the usual order"
            else "On · built-in rules" + if (windows > 0) " · $windows window${if (windows == 1) "" else "s"} of yours" else "",
            glyph = FrostIcons.Lines,
        ) { open("order") }
        val quiet = com.ataglanceplus.cards.CardRules.quietRules(context)
        LinkRow("Quiet hours", if (quiet.isEmpty()) "None" else quiet.joinToString("; ") { com.ataglanceplus.cards.CardRules.describe(it) }, glyph = FrostIcons.Lock) { open("quiet") }
    }
    if (editingHeight) CardHeightDialog { editingHeight = false }
}

/** About: re-activating after a restart, the connection's status line and the version. */
@Composable
private fun AboutPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    SettingsPage("About", onBack) {
        if (Prefs.isPaired(context)) {
            Subheader("After a restart")
            SwitchSettingRow("Re-activate after a restart", "Waits for Wi-Fi, then activates", glyph = FrostIcons.Refresh, checked = Prefs.activateOnBoot(context)) { Prefs.setActivateOnBoot(context, it) }
            if (wadbStatus.isNotEmpty()) SettingNote(wadbStatus, MaterialTheme.colorScheme.primary)
        }
        Subheader("About")
        SettingRow("At a Glance Plus", "Version ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()}", glyph = FrostIcons.Info)
    }
}

/** The cards with nothing to set but on or off. */
internal val MORE_CARDS = listOf(CardType.NAV, CardType.LIVE, CardType.TIMER, CardType.MESSAGES, CardType.TORCH)

private fun weatherSummary(context: Context): String {
    val look = Prefs.weatherLook(context).label
    val now = com.ataglanceplus.weather.WeatherRepository.weather.value
    return listOfNotNull("Material You · $look", now?.let { "${it.condition.label}, ${it.temperatureText}" }).joinToString(" · ")
}

private fun calendarSummary(context: Context): String {
    if (!com.ataglanceplus.live.CalendarSource.hasPermission(context)) return "Needs calendar access"
    val lead = Prefs.calendarLeadMinutes(context)
    val shown = runCatching { com.ataglanceplus.live.CalendarSource.calendars(context).count { it.id !in Prefs.calendarHidden(context) } }.getOrDefault(0)
    return "$shown calendar${if (shown == 1) "" else "s"} · from ${leadText(lead)} before"
}

internal fun leadText(minutes: Int) = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

/** The eToro account in a line: connected (with its positions) or how to connect it. */
private fun etoroSummary(context: Context): String {
    if (!com.ataglanceplus.live.EtoroSource.hasKeys(context)) return "Not connected · add your read-only keys"
    val p = com.ataglanceplus.live.EtoroSource.portfolio.value ?: return "Connected"
    val positions = p.holdings.sumOf { it.open.size.takeIf { n -> n > 0 } ?: it.positions }
    return "Connected · $positions position${if (positions == 1) "" else "s"} in ${p.holdings.size} holding${if (p.holdings.size == 1) "" else "s"}"
}

/**
 * Finance › eToro: connect the user's eToro account with a read-only key. The stock screens'
 * "What if", the eToro chip and the eToro dashboard read it.
 */
@Composable
internal fun EtoroAccountPage(onBack: () -> Unit) {
    SettingsPage("eToro", onBack) {
        SettingNote("Your real eToro account, read only: your portfolio for the eToro chip and dashboard, and your positions for \"What if\" on a stock's screen. The app can't trade.")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { EtoroKeysSection() }
    }
}

private fun financeSummary(context: Context): String {
    val tickers = Prefs.financeTickers(context)
    if (tickers.isEmpty()) return "No stocks yet"
    return tickers.take(3).joinToString(", ") + (if (tickers.size > 3) "…" else "") + " · every ${refreshText(Prefs.financeRefreshMinutes(context))}"
}

internal fun refreshText(minutes: Int) = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun sportsSummary(context: Context): String =
    Prefs.sportsFollowed(context).map { it.name }.ifEmpty { listOf("Nobody followed yet") }.let { names ->
        names.take(3).joinToString(", ") + if (names.size > 3) "…" else ""
    }

private fun walletSummary(context: Context): String {
    if (!com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)) return "Sign in to show your passes"
    com.ataglanceplus.wallet.WalletSource.ensureLoaded(context)
    val passes = com.ataglanceplus.wallet.WalletSource.passes.value
    val on = com.ataglanceplus.wallet.WalletRules.rules(context).filter { it.enabled }.map { it.passId }.toSet()
    return "${passes.count { it.id in on }} of ${passes.size} on the card"
}

/**
 * The status at the top: whether the cards are showing, and the button that moves it on
 * (pair, activate, or back to Google), plus the way to wireless debugging when Android blocks
 * switching it on.
 */
@Composable
private fun StatusBanner(version: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val paired = remember(version, wadbStatus) { Prefs.isPaired(context) }
    val wifi = remember(version, wadbStatus) { Wadb.isWifiConnected(context) }
    val switchBlocked = remember(version, wadbStatus) { Prefs.debugSwitchBlocked(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPairing(context)
    }
    val surfaces = sessions.values.map { it.surface }.toSet()
    val home = com.ataglanceplus.service.AtAGlanceService.SURFACE_HOME in surfaces
    val lock = com.ataglanceplus.service.AtAGlanceService.SURFACE_LOCKSCREEN in surfaces
    val (headline, detail) = when {
        home && lock -> "Showing on your home and lock screen" to "At a Glance Plus has replaced Google's At a Glance."
        home -> "Showing on the home screen only" to "Activate to connect the lock screen too."
        lock -> "Showing on the lock screen only" to "Activate to connect the home screen too."
        paired -> "Not showing yet" to "Activate to replace Google's At a Glance."
        else -> "Not set up yet" to "Pair once with this phone's wireless debugging; no computer needed. In the page that opens, choose \"Pair device with pairing code\" and type the code into this app's notification."
    }
    Subheader("At a Glance Plus")
    SettingRow(headline, detail + if (!wifi) " Wireless debugging needs Wi-Fi." else "", glyph = if (home && lock) FrostIcons.Check else FrostIcons.Glance, error = !wifi && !(home && lock))
    // Pair, Activate, or Back to Google: there is always one.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.layoutId(FROST_JOINED).padding(start = 60.dp, end = 10.dp, bottom = 6.dp),
    ) {
        when {
            !paired -> Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startPairing(context)
                else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Pair with this phone") }
            !(home && lock) -> Button(enabled = !busy, onClick = { scope.launch { if (Activator.run(context, Activator.Mode.ACTIVATE) is Activator.Outcome.Success) FinishSetup.activated() } }) { Text("Activate") }
        }
        if (paired) OutlinedButton(enabled = !busy && Commands.defaultProvider(context) != null, onClick = {
            scope.launch { Activator.run(context, Activator.Mode.RESET) }
        }) { Text("Back to Google") }
        // Android no longer lets the app switch wireless debugging on: a way straight to it.
        if (paired && switchBlocked && !(home && lock)) {
            TextButton(onClick = { runCatching { context.startActivity(Wadb.wirelessDebuggingSettingsIntent()) } }) { Text("Open wireless debugging") }
        }
    }
}

/** Notification access, needed by media, navigation, live updates, timers and the message pop-up. */
@Composable
internal fun NotificationAccessRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    SettingRow(
        "Allow notification access",
        message.ifEmpty { "Media, navigation, live updates, timers and messages read your notifications. Nothing leaves the phone." },
        icon = Icons.Default.Notifications, error = true, onClick = { asking = true },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Notification access") },
            text = { Text("Grant it over wireless debugging (Wi-Fi needed), or open Android's setting and switch it on there.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    asking = false
                    scope.launch {
                        message = "Granting over wireless debugging…"
                        val outcome = Activator.grant(context, Commands.chipGrantSteps(context, notifications = true, usage = false, callLog = false))
                        message = if (outcome is Activator.Outcome.Failure) outcome.reason else "Done."
                        Prefs.chipPermissionsChanged(context)
                    }
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationCounter.component(context).flattenToString())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Open the setting") }
            },
        )
    }
}

/** Card height, on a slider, saved when it is let go. */
@Composable
private fun CardHeightDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var height by remember { mutableFloatStateOf(Prefs.cardHeightDp(context).toFloat()) }
    val default = com.ataglanceplus.cards.Style.CARD_HEIGHT_DEFAULT_DP
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card height") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${height.toInt()} dp", style = MaterialTheme.typography.titleMedium)
                Slider(value = height, onValueChange = { height = it }, valueRange = 76f..140f, steps = 15)
                Text("$default dp by default. Taller cards are cut off where the launcher has no room.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.setCardHeightDp(context, height.toInt()); onDismiss() }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { Prefs.setCardHeightDp(context, default); onDismiss() }) { Text("Reset") } },
    )
}

internal fun startPairing(context: Context) {
    context.startForegroundService(PairingService.startIntent(context))
    runCatching { context.startActivity(Wadb.wirelessDebuggingSettingsIntent()) }
}

// --- Advanced ---------------------------------------------------------------------------------

/** The technical parts: the connection, the device checks, wireless debugging, logs and adb commands. */
@Composable
private fun AdvancedPage(version: Int, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by ServiceState.sessions.collectAsStateWithLifecycle()
    val wadbStatus by Wadb.status.collectAsStateWithLifecycle()
    val busy by Wadb.busy.collectAsStateWithLifecycle()
    val paired = remember(version, wadbStatus) { Prefs.isPaired(context) }
    val wirelessOn = remember(version, wadbStatus) { Wadb.wirelessDebuggingState(context) }
    val canSelfEnable = remember(version, wadbStatus) { Wadb.canSwitchDebuggingOn(context) }
    val wifi = remember(version, wadbStatus) { Wadb.isWifiConnected(context) }
    val errors = remember(version) { com.ataglanceplus.CrashLog.read(context) }
    val provider = remember(version) { Commands.lookupDefaultProvider(context) }

    SettingsPage("Advanced", onBack) {
        // Only what is used day to day: whether the cards are showing, turning them on, and the
        // way to Debug. Anything wrong is a row of its own, in red; the details live in Debug.
        Subheader("At a Glance")
        if (sessions.isEmpty()) {
            CheckRow("Not showing", false, "The home and lock screen are on Google's At a Glance")
        } else {
            val surfaces = sessions.values.map { it.surface }.toSet()
            val where = when {
                "home" in surfaces && "lockscreen" in surfaces -> "the home and lock screen"
                "home" in surfaces -> "the home screen"
                else -> "the lock screen"
            }
            val since = sessions.values.minOf { it.connectedAt }
            CheckRow("Showing on $where", true, "Connected since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)))
        }
        SettingRow(
            if (busy) "Activating…" else "Activate now",
            when {
                !paired -> "Pair with this phone first (below)"
                wadbStatus.isNotEmpty() -> wadbStatus
                else -> "Switch the home and lock screen to this app again"
            },
            icon = Icons.Default.PlayArrow, accent = true, enabled = paired && !busy,
            onClick = { scope.launch { if (Activator.run(context, Activator.Mode.ACTIVATE) is Activator.Outcome.Success) FinishSetup.activated() } },
        )
        if (!Commands.isPixelLauncherInstalled(context)) CheckRow("Pixel Launcher not found", false, "The cards need the Pixel Launcher")
        (provider as? Commands.Provider.Missing)?.let { CheckRow("Google's At a Glance not found", false, it.reason) }

        Subheader("Wireless debugging")
        when {
            !paired -> CheckRow("Not paired yet", false, "Pair once; no computer needed")
            !wifi -> CheckRow("Not on Wi-Fi", false, "Wireless debugging only works on Wi-Fi")
            wirelessOn == false && !canSelfEnable -> CheckRow("Wireless debugging is off", false, "Turn it on in Developer options, then Activate")
            else -> CheckRow(
                "Ready", true,
                "Paired" + (if (wirelessOn == true) ", on" else "") + ", and on Wi-Fi" + if (canSelfEnable) "; switches itself on after a restart" else "",
            )
        }
        SettingRow(if (paired) "Pair again" else "Pair with this phone", icon = Icons.Default.Refresh, onClick = { startPairing(context) })
        SettingRow("Open wireless debugging", "Android's own setting", icon = Icons.Default.Settings, onClick = { runCatching { context.startActivity(Wadb.wirelessDebuggingSettingsIntent()) } })

        Subheader("Help")
        val errorCount = errors.lines().count { it.startsWith("=== ") }
        if (errorCount > 0) {
            LinkRow("$errorCount error${if (errorCount == 1) "" else "s"} recorded", if (errorCount == 1) "See it in Debug" else "See them in Debug", icon = Icons.Default.Warning, error = true) { open("advanced/debug") }
        }
        LinkRow("Debug", "History, why it stopped, and a report to share", icon = Icons.Default.Build) { open("advanced/debug") }
    }
}

@Composable
internal fun CheckRow(label: String, ok: Boolean, detail: String) {
    SettingRow(label, detail, icon = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, error = !ok)
}

@Composable
internal fun LogText(text: String, limit: Int = 4000) {
    Text(
        text.take(limit), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        color = LocalFrost.current.textSecondary,
        modifier = Modifier.layoutId(FROST_JOINED).padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth()
            .background(LocalFrost.current.tile, RoundedCornerShape(12.dp)).padding(12.dp),
    )
}

@Composable
internal fun CommandRow(title: String, command: String) {
    val context = LocalContext.current
    SettingRow(title, "Tap to copy", icon = Icons.Default.Build, onClick = { copy(context, "command", command) })
    LogText(command)
}

internal fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
