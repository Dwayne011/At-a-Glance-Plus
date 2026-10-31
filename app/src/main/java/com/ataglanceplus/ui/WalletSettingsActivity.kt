package com.ataglanceplus.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.CardType
import com.ataglanceplus.wallet.GoogleAccount
import com.ataglanceplus.wallet.PlaceSearch
import com.ataglanceplus.wallet.WalletGeofences
import com.ataglanceplus.wallet.WalletPass
import com.ataglanceplus.wallet.WalletRules
import com.ataglanceplus.wallet.WalletSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * Wallet passes, laid out as settings (Material's settings pattern): the master switch, the
 * account and sync, then the passes on the card and those not, each with its switch. A pass's
 * own page holds its places and times as lists (or, for boarding passes and tickets, says they
 * follow Google Wallet); "Add a place" searches as the user types and offers any branch of a chain.
 */
class WalletSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WalletSource.ensureLoaded(this)
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { WalletScreens { finish() } }
                }
            }
        }
    }
}

/** Which page is open: the list, a pass, or adding a place to a pass. */
private sealed interface WalletPage {
    data object List : WalletPage
    data class Pass(val id: String) : WalletPage
    data class AddPlace(val id: String) : WalletPage
}

@Composable
private fun WalletScreens(onExit: () -> Unit) {
    var pageKey by rememberSaveable { mutableStateOf("list") }
    val page: WalletPage = when {
        pageKey.startsWith("pass:") -> WalletPage.Pass(pageKey.removePrefix("pass:"))
        pageKey.startsWith("add:") -> WalletPage.AddPlace(pageKey.removePrefix("add:"))
        else -> WalletPage.List
    }
    // Bumped on every change, so the pages read the rules again.
    var version by remember { mutableIntStateOf(0) }
    BackHandler(enabled = page != WalletPage.List) {
        pageKey = if (page is WalletPage.AddPlace) "pass:${page.id}" else "list"
    }
    when (page) {
        WalletPage.List -> PassList(version, onBack = onExit, onOpen = { pageKey = "pass:$it" }, onChange = { version++ })
        is WalletPage.Pass -> PassPage(page.id, version, onBack = { pageKey = "list" }, onAddPlace = { pageKey = "add:${page.id}" }, onChange = { version++ })
        is WalletPage.AddPlace -> AddPlacePage(page.id, onBack = { pageKey = "pass:${page.id}" }, onAdded = { version++; pageKey = "pass:${page.id}" })
    }
}

// --- Shared rows ------------------------------------------------------------------------------

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun Subheader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}

/** A settings row: an optional leading icon or [lead], the title and summary, and a trailing part. */
@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    lead: (@Composable () -> Unit)? = null,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            lead != null -> { lead(); Box(Modifier.width(16.dp)) }
            icon != null -> {
                Icon(icon, contentDescription = null, tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Box(Modifier.width(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Box(Modifier.width(12.dp)); trailing() }
    }
}

/** A row whose text opens something and whose switch toggles it, split by a short line. */
@Composable
private fun SplitSwitchRow(title: String, summary: String, lead: @Composable () -> Unit, checked: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            lead()
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        VerticalDivider(Modifier.height(32.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { Switch(checked = checked, onCheckedChange = onToggle) }
    }
}

/** The pass's colour with its first letters, standing in for its logo. */
@Composable
private fun PassBadge(pass: WalletPass) {
    val initials = (pass.issuer ?: pass.title).split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase(Locale.getDefault()) }
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(pass.colour)), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun automatic(pass: WalletPass) = pass.kind in WalletRules.AUTOMATIC

private fun ruleOf(context: Context, pass: WalletPass): WalletRules.Rule =
    WalletRules.ruleFor(context, pass.id) ?: WalletRules.Rule(pass.id, auto = automatic(pass))

private fun hm(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

private val DAY_LETTERS = listOf(Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun")

private fun daysText(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> "Every day"
    days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "Weekdays"
    days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "Weekends"
    days.size == 1 -> DAY_LETTERS.first { it.first in days }.second.let { full -> mapOf("Mon" to "Mondays", "Tue" to "Tuesdays", "Wed" to "Wednesdays", "Thu" to "Thursdays", "Fri" to "Fridays", "Sat" to "Saturdays", "Sun" to "Sundays")[full] ?: full }
    else -> DAY_LETTERS.filter { it.first in days }.joinToString(", ") { it.second }
}

private fun windowText(w: WalletRules.Window) = "${daysText(w.days)} ${hm(w.startMin)}–${hm(w.endMin)}"

private fun placeTitle(p: WalletRules.Place) = if (p.chain != null) "Any ${p.chain}" else p.label

/** What the list says under a pass: where and when it shows. */
private fun summary(pass: WalletPass, rule: WalletRules.Rule): String = when {
    !rule.enabled -> "Off"
    automatic(pass) -> "Automatic, as in Google Wallet"
    rule.places.isEmpty() && rule.windows.isEmpty() -> "Always"
    else -> (rule.places.map { if (it.chain != null) "At any ${it.chain}" else "At ${it.label}" } + rule.windows.map { windowText(it) }).joinToString(" · ")
}

private fun hasBackgroundLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A fresh fix, or the last one known, or null without permission. */
private suspend fun here(context: Context): Location? {
    WalletGeofences.cachedLocation?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }?.let { return it }
    if (!hasLocation(context)) return WalletGeofences.cachedLocation
    return withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("MissingPermission")
            val task = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            com.google.android.gms.tasks.Tasks.await(task)
        }.getOrNull()?.also { WalletGeofences.note(it) } ?: WalletGeofences.cachedLocation
    }
}

// --- The list ---------------------------------------------------------------------------------

@Composable
private fun PassList(version: Int, onBack: () -> Unit, onOpen: (String) -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passes by WalletSource.passes.collectAsStateWithLifecycle()
    val status by WalletSource.status.collectAsStateWithLifecycle()
    var syncing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val rules = remember(version, passes) { passes.associate { it.id to ruleOf(context, it) } }
    var cardOn by remember { mutableStateOf(Prefs.cardEnabled(context, CardType.WALLET)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Wallet passes", onBack)
        SettingRow("Show Wallet passes", "On the home and lock screen", trailing = {
            Switch(checked = cardOn, onCheckedChange = { cardOn = it; Prefs.setCardEnabled(context, CardType.WALLET, it) })
        })
        HorizontalDivider()

        Subheader("Account")
        SettingRow("Google account", GoogleAccount.email(context).orEmpty(), icon = Icons.Default.Person, trailing = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; GoogleAccount.signOut(context); onBack() })
                }
            }
        })
        val synced = status?.let { s ->
            val at = java.time.Instant.ofEpochMilli(s.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).withSecond(0)
            "Last sync $at · ${s.message.removePrefix("Synced. ")}"
        }
        SettingRow(
            if (syncing) "Syncing…" else "Sync now",
            synced ?: "Passes sync from Google Wallet every few hours",
            icon = Icons.Default.Refresh,
            onClick = if (syncing) null else ({
                syncing = true
                scope.launch {
                    withContext(Dispatchers.IO) { WalletSource.sync(context) }
                    syncing = false
                    onChange()
                }
            }),
        )
        // Places need the location all the time, as passes show while the app is closed.
        @Suppress("UNUSED_EXPRESSION") permissionTick
        if (rules.values.any { it.enabled && it.places.isNotEmpty() } && !hasBackgroundLocation(context)) {
            SettingRow("Allow location all the time", "Needed to show passes at your places while the app is closed", icon = Icons.Default.LocationOn, accent = true, onClick = {
                if (!hasLocation(context)) fine.launch(Manifest.permission.ACCESS_FINE_LOCATION) else background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            })
        }

        val (on, off) = passes.partition { rules[it.id]?.enabled == true }
        fun toggle(pass: WalletPass, checked: Boolean) {
            WalletRules.upsert(context, ruleOf(context, pass).copy(enabled = checked))
            onChange()
        }
        if (passes.isEmpty()) {
            Text("No passes yet. Sync now to fetch them from Google Wallet.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            return@Column
        }
        if (on.isNotEmpty()) {
            Subheader("Passes on the card")
            on.forEach { pass -> SplitSwitchRow(pass.title, summary(pass, rules.getValue(pass.id)), { PassBadge(pass) }, true, { onOpen(pass.id) }) { toggle(pass, it) } }
        }
        if (off.isNotEmpty()) {
            Subheader("Not on the card")
            off.forEach { pass -> SplitSwitchRow(pass.title, "Off", { PassBadge(pass) }, false, { onOpen(pass.id) }) { toggle(pass, it) } }
        }
        Text("Switch a pass on to show it; tap its name to choose where and when.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    }
}

// --- A pass -----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassPage(id: String, version: Int, onBack: () -> Unit, onAddPlace: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val passes by WalletSource.passes.collectAsStateWithLifecycle()
    val pass = passes.firstOrNull { it.id == id }
    if (pass == null) {
        TopBar("Pass", onBack)
        return
    }
    val rule = remember(version, pass) { ruleOf(context, pass) }
    fun save(updated: WalletRules.Rule) {
        WalletRules.upsert(context, updated)
        onChange()
    }
    var editingRadius by remember { mutableStateOf<Int?>(null) }
    var addingTime by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(pass.title, onBack)
        // The pass as Wallet colours it.
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(pass.colour)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pass.issuer?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp) }
            Text(pass.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            pass.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
        SettingRow("Show on the card", trailing = { Switch(checked = rule.enabled, onCheckedChange = { save(rule.copy(enabled = it)) }) })
        HorizontalDivider()

        if (automatic(pass)) {
            Subheader("When")
            SettingRow(
                "Automatically, as in Google Wallet",
                when (pass.kind) {
                    WalletPass.Kind.FLIGHT -> "From when Wallet would notify you (about 3 hours before boarding) until the flight lands"
                    WalletPass.Kind.EVENT -> "From a few hours before the event until it ends"
                    else -> "From a few hours before the journey until it ends"
                },
                icon = Icons.Default.DateRange,
            )
            Text("Boarding passes, event tickets and transit tickets carry their own times, so they need no places or times from you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            return@Column
        }

        Subheader("Where")
        rule.places.forEachIndexed { index, place ->
            SettingRow(
                placeTitle(place),
                (if (place.chain != null) "Every ${place.chain} nearby" else place.detail ?: "") .let { if (it.isEmpty()) "Within ${place.radiusM} m" else "$it · within ${place.radiusM} m" },
                icon = if (place.chain != null) Icons.Default.ShoppingCart else Icons.Default.Place,
                onClick = { editingRadius = index },
                trailing = {
                    IconButton(onClick = { save(rule.copy(places = rule.places.filterIndexed { i, _ -> i != index })) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                },
            )
        }
        SettingRow("Add a place", icon = Icons.Default.Add, accent = true, onClick = onAddPlace)

        Subheader("When")
        rule.windows.forEachIndexed { index, window ->
            SettingRow(windowText(window), icon = Icons.Default.DateRange, trailing = {
                IconButton(onClick = { save(rule.copy(windows = rule.windows.filterIndexed { i, _ -> i != index })) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove")
                }
            })
        }
        SettingRow("Add a time", icon = Icons.Default.Add, accent = true, onClick = { addingTime = true })

        Text(
            if (rule.places.isEmpty() && rule.windows.isEmpty()) "With no places or times, it shows all the time while switched on."
            else "It shows at any of these places, or during any of these times. Tap a place to change how close you need to be.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }

    editingRadius?.let { index ->
        val place = rule.places.getOrNull(index)
        if (place == null) { editingRadius = null; return@let }
        RadiusDialog(placeTitle(place), place.radiusM, onDismiss = { editingRadius = null }) { metres ->
            save(rule.copy(places = rule.places.mapIndexed { i, p -> if (i == index) p.copy(radiusM = metres) else p }))
            editingRadius = null
        }
    }
    if (addingTime) {
        TimeWindowDialog(onDismiss = { addingTime = false }) { window ->
            save(rule.copy(windows = rule.windows + window))
            addingTime = false
        }
    }
}

private val RADII = listOf(100, 150, 300, 500)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RadiusDialog(title: String, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var chosen by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Show the pass within")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RADII.forEach { m -> FilterChip(selected = chosen == m, onClick = { chosen = m }, label = { Text("$m m") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeWindowDialog(onDismiss: () -> Unit, onAdd: (WalletRules.Window) -> Unit) {
    val context = LocalContext.current
    var start by remember { mutableIntStateOf(9 * 60) }
    var end by remember { mutableIntStateOf(10 * 60) }
    var days by remember { mutableStateOf(DAY_LETTERS.map { it.first }.toSet()) }
    fun pick(initial: Int, title: String, onSet: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> onSet(h * 60 + m) }, initial / 60, initial % 60, android.text.format.DateFormat.is24HourFormat(context))
            .apply { setTitle(title) }.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pick(start, "From") { start = it } }) { Text("From ${hm(start)}") }
                    OutlinedButton(onClick = { pick(end, "Until") { end = it } }) { Text("Until ${hm(end)}") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LETTERS.forEach { (day, label) ->
                        FilterChip(selected = day in days, onClick = { days = if (day in days) days - day else days + day }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = days.isNotEmpty(), onClick = { onAdd(WalletRules.Window(if (days.size == 7) emptySet() else days, start, end)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Adding a place -----------------------------------------------------------------------------

@Composable
private fun AddPlacePage(id: String, onBack: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var near by remember { mutableStateOf<Location?>(WalletGeofences.cachedLocation) }
    var results by remember { mutableStateOf<List<PlaceSearch.Suggestion>>(emptyList()) }
    var chain by remember { mutableStateOf<String?>(null) }
    var chainCount by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    // The place picked, waiting for its radius.
    var picked by remember { mutableStateOf<WalletRules.Place?>(null) }
    val focus = remember { FocusRequester() }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        near = here(context)
    }
    // Suggestions as the user types, a moment after they pause.
    LaunchedEffect(query, near) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); chain = null; return@LaunchedEffect }
        delay(250)
        searching = true
        val found = withContext(Dispatchers.IO) { PlaceSearch.suggest(q, near) }
        results = found.sortedWith(compareBy({ !it.business }, { it.distanceM ?: Float.MAX_VALUE })).take(8)
        searching = false
        chain = PlaceSearch.chainFor(q, found)
    }
    LaunchedEffect(chain, near) {
        chainCount = null
        val c = chain ?: return@LaunchedEffect
        val at = near ?: return@LaunchedEffect
        chainCount = withContext(Dispatchers.IO) { runCatching { PlaceSearch.findBranches(c, at).size }.getOrNull() }
    }

    fun add(place: WalletRules.Place) {
        val rule = WalletRules.ruleFor(context, id) ?: WalletRules.Rule(id, enabled = true)
        WalletRules.upsert(context, rule.copy(places = rule.places + place, enabled = true))
        // A chain's branches are looked up now, so the pass can show straight away.
        val location = near
        if (place.chain != null && location != null) {
            scope.launch(Dispatchers.IO) {
                PlaceSearch.refreshBranches(context, listOf(place.chain), location)
                WalletGeofences.refresh(context)
                WalletGeofences.note(location)
            }
        }
        onAdded()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Add a place", onBack)
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search a shop, place or address") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(focus),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingRow("Use where I am now", icon = Icons.Default.LocationOn, onClick = {
                scope.launch {
                    val location = here(context) ?: return@launch
                    picked = WalletRules.Place("Here", location.latitude, location.longitude, 150, detail = "Where you added it")
                }
            })
            HorizontalDivider()
            chain?.let { c ->
                Subheader("Every branch")
                SettingRow(
                    "Any $c",
                    when {
                        near == null -> "Every $c near you; follows you as you move"
                        chainCount == null -> "Finding the ones near you…"
                        chainCount == 0 -> "None found near you yet; follows you as you move"
                        else -> "$chainCount within about 10 km of you; follows you as you move"
                    },
                    icon = Icons.Default.ShoppingCart,
                    onClick = { picked = WalletRules.Place(c, 0.0, 0.0, 150, chain = c) },
                )
            }
            if (results.isNotEmpty()) Subheader("Places")
            results.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { picked = WalletRules.Place(s.name, s.lat, s.lng, 150, detail = s.detail.ifEmpty { null }) }
                    .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.padding(start = 20.dp).weight(1f)) {
                        Text(highlighted(s.name, query.trim()), style = MaterialTheme.typography.bodyLarge)
                        val distance = s.distanceM?.let { if (it < 1000) "${it.toInt()} m" else String.format(locale, "%.1f km", it / 1000) }
                        Text(listOfNotNull(s.detail.ifEmpty { null }, distance).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (query.trim().length >= 2 && !searching && results.isEmpty() && chain == null) {
                Text("Nothing found for \"${query.trim()}\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
    }

    picked?.let { place ->
        RadiusDialog(placeTitle(place), 150, onDismiss = { picked = null }) { metres ->
            picked = null
            add(place.copy(radiusM = metres))
        }
    }
}

/** [text] with the part matching what was typed in bold. */
private fun highlighted(text: String, typed: String) = buildAnnotatedString {
    val at = if (typed.isEmpty()) -1 else text.lowercase(Locale.getDefault()).indexOf(typed.lowercase(Locale.getDefault()))
    if (at < 0) { append(text); return@buildAnnotatedString }
    append(text.substring(0, at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(at, at + typed.length)) }
    append(text.substring(at + typed.length))
}
