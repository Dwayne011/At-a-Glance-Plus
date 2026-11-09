package com.ataglanceplus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.Prefs
import com.ataglanceplus.wallet.WalletGeofences
import com.ataglanceplus.wallet.WalletRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app still needs from the user to keep working: precise location (and location all the
 * time) when a Wallet pass has places, and Unrestricted battery use, so Android is less likely to
 * stop the app and drop the cards back to Google's. Shown as a card on Home while anything is
 * missing, and asked for in turn, once each, after an Activate.
 */
object FinishSetup {

    enum class Item { PRECISE, BACKGROUND, BATTERY }

    private const val KEY_PRECISE_DENIALS = "setup_precise_denials"
    private const val KEY_ASKED = "setup_asked_after_activate"

    private val _askNow = MutableStateFlow(false)
    /** Set by a successful Activate; Home asks for what is missing next time it is in front. */
    val askNow = _askNow.asStateFlow()

    fun activated() { _askNow.value = true }
    internal fun consumed() { _askNow.value = false }

    fun placesUsed(context: Context): Boolean =
        runCatching { WalletRules.rules(context).any { it.enabled && it.places.isNotEmpty() } }.getOrDefault(false)

    fun unrestricted(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)

    private fun hasBackground(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<Item> = buildList {
        if (placesUsed(context)) {
            // All the time can only be asked for once precise (or any) location is allowed.
            if (!WalletGeofences.hasPrecise(context)) add(Item.PRECISE)
            else if (!hasBackground(context)) add(Item.BACKGROUND)
        }
        if (!unrestricted(context)) add(Item.BATTERY)
    }

    /** Android stops showing the dialog after it has been turned down twice; then only Settings can. */
    fun preciseBlocked(context: Context) = Prefs.get(context).getInt(KEY_PRECISE_DENIALS, 0) >= 2

    internal fun preciseDenied(context: Context) {
        val prefs = Prefs.get(context)
        prefs.edit { putInt(KEY_PRECISE_DENIALS, prefs.getInt(KEY_PRECISE_DENIALS, 0) + 1) }
    }

    internal fun askedAfterActivate(context: Context): Set<String> =
        Prefs.get(context).getStringSet(KEY_ASKED, emptySet()).orEmpty()

    internal fun noteAsked(context: Context, item: Item) =
        Prefs.get(context).edit { putStringSet(KEY_ASKED, askedAfterActivate(context) + item.name) }
}

@SuppressLint("BatteryLife")
private fun batteryIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

/** The Finish setup card on Home, and the asking in turn after an Activate. */
@Composable
internal fun FinishSetupCard(version: Int) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val missing = remember(version, tick) { FinishSetup.missing(context) }
    val askNow by FinishSetup.askNow.collectAsStateWithLifecycle()
    // Asking in turn after an Activate: the items asked so far in this round.
    var round by remember { mutableStateOf<Set<FinishSetup.Item>?>(null) }
    var next: () -> Unit = {}

    val precise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!WalletGeofences.hasPrecise(context)) FinishSetup.preciseDenied(context)
        WalletGeofences.refresh(context)
        tick++
        next()
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        WalletGeofences.refresh(context)
        tick++
        next()
    }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        WalletGeofences.refresh(context)
        tick++
        next()
    }

    fun ask(item: FinishSetup.Item) {
        val asked = runCatching {
            when (item) {
                // Android 12 and later ignore a request for precise location on its own.
                FinishSetup.Item.PRECISE ->
                    if (FinishSetup.preciseBlocked(context)) screen.launch(appSettingsIntent(context))
                    else precise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                FinishSetup.Item.BACKGROUND -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                FinishSetup.Item.BATTERY ->
                    runCatching { screen.launch(batteryIntent(context)) }
                        .getOrElse { screen.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }.isSuccess
        if (!asked) next()
    }

    next = {
        // The next item still missing that has not been asked after an Activate before.
        val done = round
        if (done != null) {
            val already = FinishSetup.askedAfterActivate(context)
            val item = FinishSetup.missing(context).firstOrNull { it !in done && it.name !in already }
            if (item == null) round = null
            else {
                round = done + item
                FinishSetup.noteAsked(context, item)
                ask(item)
            }
        }
    }

    LaunchedEffect(askNow) {
        if (askNow) {
            FinishSetup.consumed()
            round = emptySet()
            next()
        }
    }

    if (missing.isEmpty()) return
    Subheader("Finish setup")
    missing.forEach { item ->
        val (title, detail, button) = when (item) {
            FinishSetup.Item.PRECISE ->
                if (FinishSetup.preciseBlocked(context)) Triple(
                    "Precise location",
                    "Your Wallet places need it. Android won't ask again: in the app's settings, open Permissions › Location and turn on Use precise location.",
                    "Open settings",
                )
                else Triple("Precise location", "Your Wallet places need it. Approximate location is only good to a few kilometres.", "Allow")
            FinishSetup.Item.BACKGROUND -> Triple(
                "Location all the time",
                "So passes show at your places while the app is closed. Choose \"Allow all the time\".",
                "Allow",
            )
            FinishSetup.Item.BATTERY -> Triple(
                "Unrestricted battery use",
                "Makes Android less likely to close the app, which sends the cards back to Google's. It can still happen.",
                "Allow",
            )
        }
        SettingRow(
            title, detail,
            glyph = when (item) { FinishSetup.Item.BATTERY -> FrostIcons.Gauge; else -> FrostIcons.Pin },
            onClick = { ask(item) },
            trailing = { FilledTonalButton(onClick = { ask(item) }) { Text(button) } },
        )
    }
}
