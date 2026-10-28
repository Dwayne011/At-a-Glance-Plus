package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.R
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity

/**
 * The complication catalogue (spec 7.4), with the spec's sample values.
 *
 * Values are samples until each data source is built; a chip with nothing to report would be
 * hidden rather than drawn as "0" (spec 7.2).
 */
enum class Complication(
    val id: String,
    val label: String,
    val sample: String,
    @DrawableRes val icon: Int,
    @DrawableRes val aodIcon: Int,
    val attention: Boolean = false,
    /** Offered in the picker; false for chips that only appear through a rule (the rail ticket). */
    val pickable: Boolean = true,
) {
    GMAIL("gmail", "Gmail unread", "4", R.drawable.ic_comp_mail, R.drawable.ic_comp_mail_aod),
    MISSED_CALLS("calls", "Missed calls", "2", R.drawable.ic_comp_phone, R.drawable.ic_comp_phone_aod, attention = true),
    TEXTS("texts", "Unread texts", "3", R.drawable.ic_comp_chat, R.drawable.ic_comp_chat_aod),
    ALARM("alarm", "Next alarm", "07:30", R.drawable.ic_comp_alarm, R.drawable.ic_comp_alarm_aod),
    SCREEN_TIME("screen", "Screen time", "3h 12m", R.drawable.ic_comp_screen, R.drawable.ic_comp_screen_aod),
    STEPS("steps", "Steps today", "6,214", R.drawable.ic_comp_steps, R.drawable.ic_comp_steps_aod),
    SLEEP("sleep", "Sleep last night", "7h 40m", R.drawable.ic_comp_sleep, R.drawable.ic_comp_sleep_aod),
    SUNSET("sunset", "Next sunset", "16:42", R.drawable.ic_comp_sunset, R.drawable.ic_comp_sunset_aod),
    BATTERY("battery", "Phone battery", "67%", R.drawable.ic_comp_battery, R.drawable.ic_comp_battery_aod),
    BUDS("buds", "Earbuds battery", "100%", R.drawable.ic_comp_buds, R.drawable.ic_comp_buds_aod),
    COUNTDOWN("count", "Date countdown", "12d", R.drawable.ic_comp_countdown, R.drawable.ic_comp_countdown_aod),
    APP_NOTIFICATIONS("whatsapp", "App notifications", "5", R.drawable.ic_comp_bell, R.drawable.ic_comp_bell_aod),
    RAIL_TICKET("ticket", "Rail ticket", "Ticket", R.drawable.ic_comp_ticket, R.drawable.ic_comp_ticket_aod, pickable = false);

    /** What a tap opens. Always behind the unlock gate. */
    private fun targetIntent(context: Context): Intent {
        fun app(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg)
        val intent = when (this) {
            GMAIL -> app("com.google.android.gm")
            MISSED_CALLS -> app("com.google.android.dialer")
            TEXTS -> app("com.google.android.apps.messaging")
            ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            SCREEN_TIME -> app("com.google.android.apps.wellbeing")
            STEPS, SLEEP -> Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
            SUNSET -> app("com.google.android.apps.weather")
            BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            BUDS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            COUNTDOWN -> null
            APP_NOTIFICATIONS -> app("com.whatsapp")
            RAIL_TICKET -> app("com.google.android.apps.walletnfcrel")
        } ?: Intent(context, MainActivity::class.java)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun tapIntent(context: Context): PendingIntent {
        val code = REQUEST_BASE + ordinal
        val target = PendingIntent.getActivity(
            context, code, targetIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, code)
    }

    companion object {
        private const val REQUEST_BASE = 400

        fun fromId(id: String): Complication? = entries.firstOrNull { it.id == id }
    }
}

/** Where a card's complications sit (spec 7.1). */
enum class ComplicationLayout { RIGHT, BOTTOM, NONE }

/** Adds complication chips to a card's `complications` container. */
object Chips {

    const val MAX_RIGHT = 2
    const val MAX_BOTTOM = 4

    /**
     * Adds [chips] (already capped to the layout's maximum) as tappable chips. With none, the
     * container is hidden so the card's text gets the whole width (spec 7.1 collapse rule).
     */
    fun add(context: Context, views: RemoteViews, chips: List<Complication>, layout: ComplicationLayout, aod: Boolean) {
        if (chips.isEmpty() || layout == ComplicationLayout.NONE) {
            views.setViewVisibility(R.id.complications, View.GONE)
            return
        }
        views.setViewVisibility(R.id.complications, View.VISIBLE)
        val chipLayout = if (layout == ComplicationLayout.BOTTOM) R.layout.include_complication_chip_strip
        else R.layout.include_complication_chip
        chips.forEach { chip ->
            val child = RemoteViews(context.packageName, chipLayout)
            child.setTextViewText(R.id.chip_text, chip.sample)
            child.setContentDescription(R.id.chip_zone, "${chip.label}: ${chip.sample}")
            when {
                aod -> {
                    child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_bg_aod)
                    child.setImageViewResource(R.id.chip_icon, chip.aodIcon)
                    child.setTextColor(R.id.chip_text, Style.AOD_TEXT)
                }
                chip.attention -> {
                    child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_selector_attention)
                    child.setImageViewResource(R.id.chip_icon, chip.icon)
                    child.setInt(R.id.chip_icon, "setColorFilter", ATTENTION_ICON)
                    child.setTextColor(R.id.chip_text, ATTENTION_TEXT)
                }
                else -> child.setImageViewResource(R.id.chip_icon, chip.icon)
            }
            child.setOnClickPendingIntent(R.id.chip_zone, chip.tapIntent(context))
            views.addView(R.id.complications, child)
        }
    }

    private const val ATTENTION_ICON = 0xFFFF8A80.toInt()
    private const val ATTENTION_TEXT = 0xFFFFDAD6.toInt()
}
