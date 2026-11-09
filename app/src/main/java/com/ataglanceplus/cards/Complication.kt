package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.complications.ChipValues
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity

/**
 * The complication catalogue (spec 7.4). [sample] is the spec's sample value, used only by the
 * rail-ticket chip on the navigation preview; every other chip shows live data.
 */
enum class Complication(
    val id: String,
    val label: String,
    val sample: String,
    @DrawableRes val icon: Int,
    @DrawableRes val aodIcon: Int,
    /** Offered in the picker; false for chips that only appear through a rule (the rail ticket). */
    val pickable: Boolean = true,
    /** Needs an account or an app chosen when it is added (Gmail, app notifications). */
    val needsArg: Boolean = false,
    /** Has more to say than fits one slot, so can be made wide or tall. */
    val canGrow: Boolean = false,
) {
    GMAIL("gmail", "Gmail unread", "4", R.drawable.ic_comp_mail, R.drawable.ic_comp_mail_aod, needsArg = true),
    MISSED_CALLS("calls", "Missed calls", "2", R.drawable.ic_comp_phone, R.drawable.ic_comp_phone_aod),
    TEXTS("texts", "Unread texts", "3", R.drawable.ic_comp_chat, R.drawable.ic_comp_chat_aod),
    ALARM("alarm", "Next alarm", "07:30", R.drawable.ic_comp_alarm, R.drawable.ic_comp_alarm_aod),
    SCREEN_TIME("screen", "Screen time", "3h 12m", R.drawable.ic_comp_screen, R.drawable.ic_comp_screen_aod, canGrow = true),
    STEPS("steps", "Steps today", "6,214", R.drawable.ic_comp_steps, R.drawable.ic_comp_steps_aod, canGrow = true),
    SLEEP("sleep", "Sleep last night", "7h 40m", R.drawable.ic_comp_sleep, R.drawable.ic_comp_sleep_aod, canGrow = true),
    SUNSET("sunset", "Next sunrise or sunset", "16:42", R.drawable.ic_comp_sunset, R.drawable.ic_comp_sunset_aod, canGrow = true),
    BATTERY("battery", "Phone battery", "67%", R.drawable.ic_comp_battery, R.drawable.ic_comp_battery_aod),
    BUDS("buds", "Bluetooth battery", "100%", R.drawable.ic_comp_bluetooth, R.drawable.ic_comp_bluetooth_aod, canGrow = true),
    COUNTDOWN("count", "Date countdown", "12d", R.drawable.ic_comp_countdown, R.drawable.ic_comp_countdown_aod, canGrow = true),
    HEALTH("health", "Health metric", "72", R.drawable.ic_comp_heart, R.drawable.ic_comp_heart_aod, needsArg = true, canGrow = true),
    ETORO("etoro", "eToro portfolio", "+$412 · +6.3%", R.drawable.ic_comp_trend, R.drawable.ic_comp_trend_aod, canGrow = true),
    APP_NOTIFICATIONS("notif", "App notifications", "5", R.drawable.ic_comp_bell, R.drawable.ic_comp_bell_aod, needsArg = true),
    RAIL_TICKET("ticket", "Rail ticket", "Ticket", R.drawable.ic_comp_ticket, R.drawable.ic_comp_ticket_aod, pickable = false);

    companion object {
        fun fromId(id: String): Complication? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How much a chip shows: one slot, or extended over two, which is two across where chips sit
 * side by side and two stacked where they are stacked.
 */
enum class ChipSize(val label: String) { STANDARD("Standard"), EXTENDED("Extended") }

/**
 * One chip as the user added it: a kind plus, for Gmail, the account, or for app notifications,
 * the app's package. Stored as "kind" or "kind:arg".
 */
data class ChipSpec(val kind: Complication, val arg: String? = null) {

    val key: String get() = if (arg == null) kind.id else "${kind.id}:$arg"

    /** Gmail and app-notification chips added before they took an account or app show nothing. */
    val configured: Boolean get() = !kind.needsArg || !arg.isNullOrEmpty()

    /** The app a tap opens when the user has not chosen one. */
    fun defaultPackage(): String? = when (kind) {
        Complication.GMAIL -> GMAIL_PKG
        Complication.MISSED_CALLS -> "com.google.android.dialer"
        Complication.TEXTS -> "com.google.android.apps.messaging"
        Complication.SCREEN_TIME -> "com.google.android.apps.wellbeing"
        Complication.SUNSET -> "com.google.android.apps.weather"
        Complication.APP_NOTIFICATIONS -> arg
        Complication.RAIL_TICKET -> "com.google.android.apps.walletnfcrel"
        Complication.ETORO -> ETORO_PKG
        else -> null
    }

    /** What a tap opens: the app the user chose, else this chip's usual screen. */
    private fun targetIntent(context: Context): Intent {
        val pm = context.packageManager
        val chosen = Prefs.chipApp(context, key)?.let { pm.getLaunchIntentForPackage(it) }
        val intent = chosen ?: defaultIntent(context) ?: Intent(context, MainActivity::class.java)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** This chip's usual screen, when the user has not chosen an app. */
    private fun defaultIntent(context: Context): Intent? = when (kind) {
        Complication.ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        // This app's own dashboards, which link on to Health Connect and Digital Wellbeing.
        Complication.STEPS -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_STEPS)
        Complication.SLEEP -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_SLEEP)
        Complication.SCREEN_TIME -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_SCREEN)
        // Signed in to Google: the mini inbox first (it links on to Gmail); else Gmail itself.
        Complication.GMAIL -> if (com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)) com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_INBOX)
            else arg?.let { gmailAccount(context, it) } ?: defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
        Complication.HEALTH -> arg?.let { com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_HEALTH_PREFIX + it) }
        Complication.ETORO -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_ETORO)
        Complication.SUNSET -> weatherSunScreen(context) ?: defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
        Complication.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        Complication.BUDS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        else -> defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
    }

    /**
     * The name of the app a tap opens when none is chosen, for settings: "Clock", "Health
     * Connect". Null when Android would ask which app to use, or nothing handles it.
     */
    fun defaultAppName(context: Context): String? {
        val pm = context.packageManager
        val intent = defaultIntent(context) ?: return null
        val info = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // The "open with" chooser belongs to Android itself.
        if (info.packageName == "android") return null
        if (info.packageName == context.packageName) return "its dashboard in this app"
        val activity = info.loadLabel(pm).toString()
        val app = info.applicationInfo.loadLabel(pm).toString()
        // Settings screens name the screen ("Bluetooth"); apps are better named by the app.
        return if (info.packageName == "com.android.settings" && activity.isNotBlank()) "Settings › $activity" else app
    }

    fun tapIntent(context: Context): PendingIntent {
        // Request codes must differ per chip so the PendingIntents do not replace each other.
        val code = REQUEST_BASE + (key.hashCode() and 0xFFFF)
        val target = PendingIntent.getActivity(
            context, code, targetIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, code)
    }

    companion object {
        private const val REQUEST_BASE = 0x10000
        const val GMAIL_PKG = "com.google.android.gm"
        const val ETORO_PKG = "com.etoro.openbook"

        fun parse(key: String): ChipSpec? {
            val kind = Complication.fromId(key.substringBefore(':')) ?: return null
            return ChipSpec(kind, key.substringAfter(':', "").ifEmpty { null })
        }
    }
}

/**
 * Digital Wellbeing's activity details (the screen-time chart), when this version has that
 * screen and lets other apps open it; otherwise null.
 */
fun wellbeingDashboard(context: Context): Intent? = openableScreen(context, "com.google.android.apps.wellbeing", "dashboard")

/** Pixel Weather's sunrise and sunset screen, when it has one other apps may open; otherwise null. */
fun weatherSunScreen(context: Context): Intent? = openableScreen(context, "com.google.android.apps.weather", "sun")

/**
 * A screen of [pkg] whose name contains [word], if the app lets other apps open it. These apps
 * have no documented way in to one screen, so it is looked up by name; null when there is none.
 */
private fun openableScreen(context: Context, pkg: String, word: String): Intent? {
    val activities = runCatching {
        context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_ACTIVITIES.toLong())).activities
    }.getOrNull() ?: return null
    val screen = activities.firstOrNull { it.exported && it.enabled && word in it.name.substringAfterLast('.').lowercase() } ?: return null
    // A fresh task, so back leaves the app rather than going through its earlier pages.
    return Intent().setClassName(pkg, screen.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/**
 * The Gmail app at [account]'s inbox, through the account's Gmail web link, which the app may
 * take; null when Gmail does not handle it (it then opens as usual). Gmail has no documented way
 * in to one account, so this is a best try.
 */
fun gmailAccount(context: Context, account: String): Intent? {
    val link = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mail.google.com/mail/u/?authuser=" + android.net.Uri.encode(account)))
        .setPackage(ChipSpec.GMAIL_PKG)
    return link.takeIf { it.resolveActivity(context.packageManager) != null }
}

/**
 * Which chips a card shows (the tiles themselves are drawn by [PanelCards]): those with something
 * to show, each taking the room its size takes.
 */
object Chips {

    /** Slots in the right-hand column of the alarm, timer, calendar and navigation cards: two Normal chips or one Extended. */
    const val MAX_RIGHT = 2
    /** Chips the user can pick (no practical limit). Only those with something to report are shown, up to the layout's room. */
    const val MAX_CHOSEN = 50

    /** A chip with its value, and whether it takes 2 slots (Extended). */
    data class Placed(val spec: ChipSpec, val value: ChipValues.Value, val big: Boolean)

    /**
     * Whether [spec] takes 2 slots: an extended chip (or any chip that can be, when [always]).
     */
    fun grows(context: Context, spec: ChipSpec, always: Boolean = false): Boolean =
        spec.kind.canGrow && (always || Prefs.chipSize(context, spec.key) == ChipSize.EXTENDED)

    /**
     * The chips of [specs] with something to show that a spot of [slots] slots takes, in order.
     * An extended chip is never shrunk: with only 1 slot left it waits for the next spot.
     */
    fun plan(context: Context, specs: List<ChipSpec>, slots: Int, always: Boolean = false): List<Placed> {
        val placed = mutableListOf<Placed>()
        var left = slots
        for ((spec, value) in withValues(specs)) {
            if (left <= 0) break
            val big = grows(context, spec, always)
            if (big && left < 2) break
            left -= if (big) 2 else 1
            placed += Placed(spec, value, big)
        }
        return placed
    }

    /** How many of [specs] the right-hand column takes (see [plan]); an Extended chip counts once. */
    fun taken(context: Context, specs: List<ChipSpec>): Int = plan(context, specs, MAX_RIGHT).size

    /** The chips that currently have something to show, with their values. */
    fun withValues(specs: List<ChipSpec>): List<Pair<ChipSpec, ChipValues.Value>> {
        val values = ChipValues.values.value
        return specs.mapNotNull { spec ->
            val value = if (spec.kind == Complication.RAIL_TICKET) ChipValues.Value(spec.kind.sample) else values[spec.key]
            value?.let { spec to it }
        }
    }
}
