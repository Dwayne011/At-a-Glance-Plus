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
) {
    GMAIL("gmail", "Gmail unread", "4", R.drawable.ic_comp_mail, R.drawable.ic_comp_mail_aod, needsArg = true),
    MISSED_CALLS("calls", "Missed calls", "2", R.drawable.ic_comp_phone, R.drawable.ic_comp_phone_aod),
    TEXTS("texts", "Unread texts", "3", R.drawable.ic_comp_chat, R.drawable.ic_comp_chat_aod),
    ALARM("alarm", "Next alarm", "07:30", R.drawable.ic_comp_alarm, R.drawable.ic_comp_alarm_aod),
    SCREEN_TIME("screen", "Screen time", "3h 12m", R.drawable.ic_comp_screen, R.drawable.ic_comp_screen_aod),
    STEPS("steps", "Steps today", "6,214", R.drawable.ic_comp_steps, R.drawable.ic_comp_steps_aod),
    SLEEP("sleep", "Sleep last night", "7h 40m", R.drawable.ic_comp_sleep, R.drawable.ic_comp_sleep_aod),
    SUNSET("sunset", "Next sunrise or sunset", "16:42", R.drawable.ic_comp_sunset, R.drawable.ic_comp_sunset_aod),
    BATTERY("battery", "Phone battery", "67%", R.drawable.ic_comp_battery, R.drawable.ic_comp_battery_aod),
    BUDS("buds", "Bluetooth battery", "100%", R.drawable.ic_comp_bluetooth, R.drawable.ic_comp_bluetooth_aod),
    COUNTDOWN("count", "Date countdown", "12d", R.drawable.ic_comp_countdown, R.drawable.ic_comp_countdown_aod),
    APP_NOTIFICATIONS("notif", "App notifications", "5", R.drawable.ic_comp_bell, R.drawable.ic_comp_bell_aod, needsArg = true),
    RAIL_TICKET("ticket", "Rail ticket", "Ticket", R.drawable.ic_comp_ticket, R.drawable.ic_comp_ticket_aod, pickable = false);

    companion object {
        fun fromId(id: String): Complication? = entries.firstOrNull { it.id == id }
    }
}

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
        else -> null
    }

    /** What a tap opens: the app the user chose, else this chip's usual screen. */
    private fun targetIntent(context: Context): Intent {
        val pm = context.packageManager
        val chosen = Prefs.chipApp(context, key)?.let { pm.getLaunchIntentForPackage(it) }
        val intent = chosen ?: when (kind) {
            Complication.ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            Complication.STEPS, Complication.SLEEP -> Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
            Complication.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            Complication.BUDS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            else -> defaultPackage()?.let { pm.getLaunchIntentForPackage(it) }
        } ?: Intent(context, MainActivity::class.java)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

        fun parse(key: String): ChipSpec? {
            val kind = Complication.fromId(key.substringBefore(':')) ?: return null
            return ChipSpec(kind, key.substringAfter(':', "").ifEmpty { null })
        }
    }
}

/** Where a card's complications sit (spec 7.1). */
enum class ComplicationLayout { RIGHT, BOTTOM, NONE }

/**
 * The weather card's arrangement (A+C hybrid spec): 3-hour columns + 2 stacked chips, a compact
 * 3-hour row over 2 wide chips, a 2-hour box + 2 x 2 chip grid, or no chips (forecast only).
 */
enum class WeatherLayout(val label: String) {
    COLUMNS("Columns"),
    SPLIT("Split"),
    GRID("Grid"),
    NONE("No chips");

    /** The matching chip arrangement for the other cards. */
    val others: ComplicationLayout
        get() = when (this) {
            SPLIT -> ComplicationLayout.BOTTOM
            NONE -> ComplicationLayout.NONE
            else -> ComplicationLayout.RIGHT
        }
}

/** Adds complication chips to a card's `complications` container. */
object Chips {

    const val MAX_RIGHT = 2
    const val MAX_BOTTOM = 4
    /** Chips the user can pick (no practical limit). Only those with something to report are shown, up to the layout's room. */
    const val MAX_CHOSEN = 50

    /**
     * Adds the chips that have something to show, up to the layout's maximum. A chip with no
     * value (nothing to report, no permission yet, or not set up) is left out rather than drawn
     * as "0" (spec 7.2). With none left, the container is hidden so the card's text gets the
     * whole width (spec 7.1 collapse rule).
     */
    fun add(
        context: Context,
        views: RemoteViews,
        specs: List<ChipSpec>,
        layout: ComplicationLayout,
        aod: Boolean,
        container: Int = R.id.complications,
    ) {
        if (layout == ComplicationLayout.NONE) {
            views.setViewVisibility(container, View.GONE)
            return
        }
        val bottom = layout == ComplicationLayout.BOTTOM
        addStyled(
            context, views, specs, if (bottom) MAX_BOTTOM else MAX_RIGHT,
            if (bottom) R.layout.include_complication_chip_strip else R.layout.include_complication_chip, aod, container
        )
    }

    /**
     * Adds up to [max] chips with something to show into [container], each drawn from
     * [chipLayout] (any layout with chip_zone, chip_pill, chip_icon and chip_text). Returns how
     * many were added; with none, the container is hidden.
     */
    fun addStyled(
        context: Context,
        views: RemoteViews,
        specs: List<ChipSpec>,
        max: Int,
        chipLayout: Int,
        aod: Boolean,
        container: Int,
    ): Int {
        val shown = withValues(specs).take(max)
        views.setViewVisibility(container, if (shown.isEmpty()) View.GONE else View.VISIBLE)
        shown.forEach { (spec, value) ->
            val child = RemoteViews(context.packageName, chipLayout)
            if (value.parts.size > 1) {
                // Several values (Bluetooth devices): take turns inside the pill.
                child.setViewVisibility(R.id.chip_icon, View.GONE)
                child.setViewVisibility(R.id.chip_text, View.GONE)
                val flipper = RemoteViews(context.packageName, R.layout.include_chip_flipper_inner)
                value.parts.forEach { part ->
                    val row = RemoteViews(context.packageName, R.layout.include_chip_part)
                    row.setImageViewResource(R.id.chip_icon, if (aod) part.aodIcon else part.icon)
                    row.setTextViewText(R.id.chip_text, part.text)
                    if (aod) row.setTextColor(R.id.chip_text, Style.AOD_TEXT)
                    flipper.addView(R.id.chip_flipper_inner, row)
                }
                child.addView(R.id.chip_pill, flipper)
                child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + value.parts.joinToString(", ") { it.text })
                if (aod) child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_bg_aod)
            } else {
                val icon = value.parts.singleOrNull()?.let { if (aod) it.aodIcon else it.icon }
                child.setTextViewText(R.id.chip_text, value.text)
                child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: ${value.text}")
                when {
                    aod -> {
                        child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_bg_aod)
                        child.setImageViewResource(R.id.chip_icon, icon ?: spec.kind.aodIcon)
                        child.setTextColor(R.id.chip_text, Style.AOD_TEXT)
                    }
                    value.attention -> {
                        child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_selector_attention)
                        child.setImageViewResource(R.id.chip_icon, spec.kind.icon)
                        child.setInt(R.id.chip_icon, "setColorFilter", ATTENTION_ICON)
                        child.setTextColor(R.id.chip_text, ATTENTION_TEXT)
                    }
                    else -> child.setImageViewResource(R.id.chip_icon, icon ?: spec.kind.icon)
                }
            }
            child.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
            views.addView(container, child)
        }
        return shown.size
    }

    /** The chips that currently have something to show, with their values. */
    fun withValues(specs: List<ChipSpec>): List<Pair<ChipSpec, ChipValues.Value>> {
        val values = ChipValues.values.value
        return specs.mapNotNull { spec ->
            val value = if (spec.kind == Complication.RAIL_TICKET) ChipValues.Value(spec.kind.sample) else values[spec.key]
            value?.let { spec to it }
        }
    }

    private const val ATTENTION_ICON = 0xFFFF8A80.toInt()
    private const val ATTENTION_TEXT = 0xFFFFDAD6.toInt()
}
