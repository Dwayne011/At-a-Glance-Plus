package com.ataglanceplus.cards

import android.app.PendingIntent
import android.app.smartspace.SmartspaceTarget
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.ui.MainActivity
import java.util.Date
import java.util.Locale

/** A sample state of a preview card, e.g. "Arrived". */
data class PreviewState(val id: String, val label: String)

/**
 * The cards that have a layout but no data source yet. Each is filled with the design spec's
 * sample content, so the look can be checked on the phone before the real data arrives.
 */
enum class PreviewCard(val id: String, val label: String, val states: List<PreviewState>) {
    LIVE("live", "Live update", listOf(PreviewState("progress", "In progress"), PreviewState("arrived", "Arrived"), PreviewState("textonly", "Text only"))),
    MEDIA("media", "Media", listOf(PreviewState("full", "Wave"), PreviewState("compact", "Track bar"))),
    CALENDAR("calendar", "Calendar", listOf(PreviewState("soon", "Starting soon"), PreviewState("now", "Happening now"), PreviewState("allday", "All day"))),
    TIMER("timer", "Timer", listOf(PreviewState("timer", "Timer"), PreviewState("alarm", "Alarm only"))),
    WALLET("wallet", "Wallet pass", listOf(PreviewState("barcode", "Barcode"), PreviewState("qr", "QR code"), PreviewState("link", "No code"))),
    NAV("nav", "Navigation", listOf(PreviewState("driving", "Driving"), PreviewState("walking", "Walking"), PreviewState("arriving", "Arriving")));

    val targetId: String get() = "aagp_preview_$id"
}

object PreviewCards {

    private const val REQUEST_LIVE = 301
    private const val REQUEST_MEDIA = 302
    private const val REQUEST_CALENDAR = 303
    private const val REQUEST_TIMER = 304
    private const val REQUEST_WALLET = 305
    private const val REQUEST_NAV = 306
    private const val REQUEST_ISLAND = 307

    private const val WALLET_PKG = "com.google.android.apps.walletnfcrel"
    private const val MAPS_PKG = "com.google.android.apps.maps"

    /** Message toast duration (spec 6.1). */
    const val ISLAND_MS = 4_500L

    fun build(context: Context, card: PreviewCard, aod: Boolean): SmartspaceTarget = when (card) {
        PreviewCard.LIVE -> live(context, aod)
        PreviewCard.MEDIA -> media(context, aod)
        PreviewCard.CALENDAR -> calendar(context, aod)
        PreviewCard.TIMER -> timer(context, aod)
        PreviewCard.WALLET -> wallet(context, aod)
        PreviewCard.NAV -> nav(context, aod)
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent =
        Style.gated(context, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), requestCode)

    // --- 8.2 Live update ---

    private fun live(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.LIVE
        var title = "Rider is on the way"
        var detail = "Deliveroo · Order #4821 · 3 of 4 steps"
        var eta = "12 min"
        var filled = 3
        var initial = "D"
        when (Prefs.previewState(context, card)) {
            "arrived" -> { title = "Order has arrived outside"; detail = "Deliveroo · Order #4821 · 4 of 4 steps"; eta = "Now"; filled = 4 }
            "textonly" -> { title = "Gate B24 · Boarding Group 2"; detail = "British Airways · BA 1482"; eta = "14:55"; filled = 0; initial = "B" }
        }
        val views = RemoteViews(context.packageName, R.layout.card_live_update)
        views.setTextViewText(R.id.live_app_initial, initial)
        views.setTextViewText(R.id.live_title, title)
        views.setTextViewText(R.id.live_detail, detail)
        views.setTextViewText(R.id.live_eta, eta)
        val segments = intArrayOf(R.id.live_seg_1, R.id.live_seg_2, R.id.live_seg_3, R.id.live_seg_4)
        if (filled == 0) {
            views.setViewVisibility(R.id.live_segments, View.GONE)
        } else {
            segments.forEachIndexed { i, id ->
                val on = i < filled
                val res = when {
                    aod -> if (on) R.drawable.aod_segment_on else R.drawable.aod_segment_off
                    else -> if (on) R.drawable.segment_on else R.drawable.segment_off
                }
                views.setInt(id, "setBackgroundResource", res)
            }
        }
        if (aod) {
            Style.aodBody(views, R.id.live_app_initial, R.id.live_title, R.id.live_detail, R.id.live_eta)
            views.setInt(R.id.live_badge, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setInt(R.id.live_eta, "setBackgroundResource", R.drawable.aod_outline_r14)
            // Nothing on the always-on display can be tapped.
            views.setViewVisibility(R.id.dismiss, View.GONE)
        } else {
            views.setOnClickPendingIntent(R.id.dismiss, CardActionReceiver.dismiss(context, card))
        }
        val tap = openApp(context, REQUEST_LIVE)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, title, detail, R.drawable.ic_card, tap, 1.6f)
    }

    // --- 8.3 Media ---

    private fun media(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.MEDIA
        val playing = Prefs.mediaPlaying(context) && !aod
        val wave = Prefs.previewState(context, card) == "full"
        val title = if (Prefs.longText(context)) "Dreams (2004 Remaster - Live at Wembley Stadium)" else "Dreams"
        val time = if (Prefs.mediaRestarted(context)) "0:00 / 4:14" else "1:42 / 4:14"
        val detail = "Fleetwood Mac · $time"

        val views = RemoteViews(context.packageName, R.layout.card_media)
        views.setTextViewText(R.id.media_title, title)
        views.setTextViewText(R.id.media_detail, detail)

        // Equalisers: animated while playing, flat bars when paused or on the always-on display.
        views.setViewVisibility(R.id.media_art_eq, if (playing) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.media_art_eq_paused, if (playing) View.GONE else View.VISIBLE)
        if (wave) {
            views.setViewVisibility(R.id.media_eq, if (playing) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.media_eq_paused, if (playing) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.media_eq_label, if (playing) "LIVE EQ" else "PAUSED")
        } else {
            views.setViewVisibility(R.id.media_wave_row, View.GONE)
            views.setViewVisibility(R.id.media_track, View.VISIBLE)
            views.setProgressBar(R.id.media_track, 1000, if (Prefs.mediaRestarted(context)) 0 else 420, false)
        }
        views.setImageViewResource(
            R.id.media_play_icon,
            when {
                aod -> if (Prefs.mediaPlaying(context)) R.drawable.ic_media_pause_aod else R.drawable.ic_media_play_aod
                playing -> R.drawable.ic_media_pause
                else -> R.drawable.ic_media_play
            }
        )
        if (aod) {
            Style.aodBody(views, R.id.media_title, R.id.media_detail, R.id.media_eq_label)
            views.setInt(R.id.media_art, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setInt(R.id.media_art_eq_paused, "setColorFilter", Style.AOD_TEXT)
            views.setInt(R.id.media_eq_paused, "setColorFilter", Style.AOD_TEXT)
            views.setColorStateList(R.id.media_track, "setProgressTintList", ColorStateList.valueOf(Style.AOD_TEXT))
            views.setInt(R.id.media_play, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.media_prev_icon, R.drawable.ic_media_prev_aod)
            views.setImageViewResource(R.id.media_next_icon, R.drawable.ic_media_next_aod)
        } else {
            views.setOnClickPendingIntent(R.id.media_prev, CardActionReceiver.action(context, CardActionReceiver.ACTION_MEDIA_PREV))
            views.setOnClickPendingIntent(R.id.media_play, CardActionReceiver.action(context, CardActionReceiver.ACTION_MEDIA_TOGGLE))
            views.setOnClickPendingIntent(R.id.media_next, CardActionReceiver.action(context, CardActionReceiver.ACTION_MEDIA_NEXT))
        }
        val tap = openApp(context, REQUEST_MEDIA)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, title, detail, R.drawable.ic_music_note, tap, 1.5f)
    }

    // --- 8.4 Calendar ---

    private fun calendar(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.CALENDAR
        // The spec gives one sample title; each state changes the time line and bar colour.
        val title = "Gym – Upper Body B (Bench Press, Overhead Press & Weighted Pull-ups)"
        val (detail, colour) = when (Prefs.previewState(context, card)) {
            "now" -> "14:00 – 15:00 · Room 4B" to 0xFF34D399.toInt()
            "allday" -> "All day" to 0xFFA78BFA.toInt()
            else -> "18:00 · in 25 min · PureGym London Holborn, 120 High Holborn" to 0xFF60A5FA.toInt()
        }
        val layout = Prefs.complicationLayout(context)
        val chips = if (layout == ComplicationLayout.NONE) emptyList() else listOf(Complication.STEPS, Complication.BATTERY)
        val bottom = layout == ComplicationLayout.BOTTOM
        val views = RemoteViews(context.packageName, if (bottom) R.layout.card_calendar_bottom else R.layout.card_calendar)
        views.setTextViewText(R.id.calendar_title, title)
        views.setTextViewText(R.id.calendar_detail, detail)
        views.setInt(R.id.calendar_bar, "setColorFilter", if (aod) Style.AOD_TEXT else colour)
        if (aod) Style.aodBody(views, R.id.calendar_title, R.id.calendar_detail)
        Chips.add(context, views, chips, layout, aod)

        val now = Uri.parse("content://com.android.calendar/time")
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(now, System.currentTimeMillis()))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
        val tap = Style.gated(context, intent, REQUEST_CALENDAR)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, title, detail, R.drawable.ic_comp_countdown, tap, 1.4f)
    }

    // --- 8.5 Timer and next alarm ---

    private fun timer(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.TIMER
        val views = RemoteViews(context.packageName, R.layout.card_timer)
        val title: String
        val detail: String
        val readout: String
        if (Prefs.previewState(context, card) == "alarm") {
            val tomorrow = Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
            title = "Alarm tomorrow"
            detail = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), tomorrow).toString()
            readout = "07:30"
            showFixed(views, readout)
            views.setViewVisibility(R.id.timer_button, View.GONE)
        } else {
            val end = Prefs.timerEnd(context)
            val now = System.currentTimeMillis()
            val running = end > now
            val left = if (end > 0) (end - now).coerceAtLeast(0) else Prefs.timerLeft(context)
            title = "Pasta"
            if (left == 0L) {
                readout = "0:00"
                detail = "Timer finished"
                showFixed(views, readout)
            } else {
                readout = android.text.format.DateUtils.formatElapsedTime(left / 1000)
                detail = "Timer · ${if (running) "Running" else "Paused"} · Next alarm 07:30"
                if (running) {
                    // The system ticks the Chronometer itself; the app does not redraw every second.
                    views.setChronometer(R.id.timer_chrono, SystemClock.elapsedRealtime() + left, null, true)
                    views.setChronometerCountDown(R.id.timer_chrono, true)
                } else {
                    // A stopped Chronometer would recompute its value whenever the card is redrawn.
                    showFixed(views, readout)
                }
            }
            views.setImageViewResource(
                R.id.timer_button_icon,
                if (running) (if (aod) R.drawable.ic_media_pause_aod else R.drawable.ic_media_pause)
                else (if (aod) R.drawable.ic_media_play_aod else R.drawable.ic_media_play)
            )
            if (!aod) views.setOnClickPendingIntent(R.id.timer_button, CardActionReceiver.action(context, CardActionReceiver.ACTION_TIMER_TOGGLE))
        }
        views.setTextViewText(R.id.timer_title, title)
        views.setTextViewText(R.id.timer_detail, detail)
        if (aod) {
            Style.aodBody(views, R.id.timer_chrono, R.id.timer_fixed, R.id.timer_title, R.id.timer_detail)
            views.setInt(R.id.timer_button, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setInt(R.id.timer_button_icon, "setColorFilter", Style.AOD_TEXT)
        }
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.RIGHT) listOf(Complication.ALARM, Complication.SLEEP) else emptyList()
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)

        val tap = Style.gated(context, Intent(AlarmClock.ACTION_SHOW_TIMERS), REQUEST_TIMER)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, "$readout $title", detail, R.drawable.ic_comp_alarm, tap, 1.7f)
    }

    private fun showFixed(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.timer_chrono, View.GONE)
        views.setViewVisibility(R.id.timer_fixed, View.VISIBLE)
        views.setTextViewText(R.id.timer_fixed, text)
    }

    // --- 8.6 Wallet pass ---

    private fun wallet(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.WALLET
        val title = "Tesco Clubcard"
        val detail = "Tesco Extra · 120 m away"
        val views = RemoteViews(context.packageName, R.layout.card_wallet)
        views.setTextViewText(R.id.wallet_initial, "T")
        views.setTextViewText(R.id.wallet_title, title)
        views.setTextViewText(R.id.wallet_detail, detail)
        views.setTextViewText(R.id.wallet_barcode_digits, "9841 2049 11")
        when (Prefs.previewState(context, card)) {
            "qr" -> {
                views.setViewVisibility(R.id.wallet_barcode_box, View.GONE)
                views.setViewVisibility(R.id.wallet_qr_box, View.VISIBLE)
            }
            "link" -> {
                views.setViewVisibility(R.id.wallet_barcode_box, View.GONE)
                views.setViewVisibility(R.id.wallet_open, View.VISIBLE)
            }
        }
        if (aod) {
            Style.aodBody(views, R.id.wallet_initial, R.id.wallet_title, R.id.wallet_detail, R.id.wallet_open)
            views.setInt(R.id.wallet_badge, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setInt(R.id.wallet_open, "setBackgroundResource", R.drawable.aod_outline_r14)
            // No large filled blocks on the always-on display, so no codes.
            views.setViewVisibility(R.id.wallet_barcode_box, View.GONE)
            views.setViewVisibility(R.id.wallet_qr_box, View.GONE)
        }
        val tap = Style.gatedApp(context, WALLET_PKG, REQUEST_WALLET)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, title, detail, R.drawable.ic_comp_ticket, tap, 1.3f)
    }

    // --- 8.7 Navigation ---

    private fun nav(context: Context, aod: Boolean): SmartspaceTarget {
        val card = PreviewCard.NAV
        val detail = "Manchester Piccadilly · ETA 15:10 · 2.4 mi"
        val long = Prefs.longText(context)
        val (icon, distance, instruction) = when (Prefs.previewState(context, card)) {
            "walking" -> Triple(R.drawable.ic_nav_walk, "120 m", "Head north on Station Approach")
            "arriving" -> Triple(R.drawable.ic_nav_flag, "Here", "Arriving at Manchester Piccadilly")
            else -> Triple(R.drawable.ic_nav_turn_left, "350 yd", if (long) "Keep left onto A57(M) Mancunian Way towards Piccadilly" else "Turn left onto A57")
        }
        val views = RemoteViews(context.packageName, R.layout.card_navigation)
        views.setTextViewText(R.id.nav_distance, distance)
        views.setTextViewText(R.id.nav_instruction, instruction)
        views.setTextViewText(R.id.nav_detail, detail)
        if (aod) {
            Style.aodBody(views, R.id.nav_distance, R.id.nav_instruction, R.id.nav_detail)
            views.setInt(R.id.nav_box, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setImageViewResource(
                R.id.nav_icon,
                when (icon) {
                    R.drawable.ic_nav_walk -> R.drawable.ic_nav_walk_aod
                    R.drawable.ic_nav_flag -> R.drawable.ic_nav_flag_aod
                    else -> R.drawable.ic_nav_turn_left_aod
                }
            )
            views.setInt(R.id.nav_icon, "setColorFilter", Style.AOD_TEXT)
        } else {
            views.setImageViewResource(R.id.nav_icon, icon)
        }
        // The rail ticket chip is the spec's example of a rule: "navigating to Manchester Piccadilly".
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.NONE) emptyList()
        else listOf(Complication.RAIL_TICKET, Complication.BATTERY)
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)

        val tap = Style.gatedApp(context, MAPS_PKG, REQUEST_NAV)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, card.targetId, views, "$distance · $instruction", detail, icon, tap, 1.8f)
    }

    // --- 6.1 Incoming message toast ---

    private data class Message(val app: String, val colour: Int, val sender: String, val text: String)

    private val MESSAGES = listOf(
        Message("WhatsApp", 0xFF25D366.toInt(), "Tasha", "Can you grab milk on the way back? 🥛"),
        Message("Messages", 0xFF38BDF8.toInt(), "Alex Rivera", "Outside PureGym Holborn now — meet by the squat racks"),
        Message("Slack", 0xFFE879F9.toInt(), "#android-dev", "Pixel 10 Android 17 RemoteViews build passed QA ✅"),
    )

    const val ISLAND_TARGET_ID = "aagp_island"

    /** The toast shown in place of the first card; [remainingMs] drives the 3dp countdown bar. */
    fun island(context: Context, aod: Boolean, remainingMs: Long): SmartspaceTarget {
        val message = MESSAGES[Prefs.islandIndex(context) % MESSAGES.size]
        val views = RemoteViews(context.packageName, R.layout.include_island_message_toast)
        views.setTextViewText(R.id.island_app, message.app)
        views.setTextColor(R.id.island_app, message.colour)
        views.setTextViewText(R.id.island_sender, message.sender)
        views.setTextViewText(R.id.island_initial, message.sender.trimStart('#').take(1).uppercase())
        views.setTextViewText(R.id.island_message, "\"${message.text}\"")
        views.setProgressBar(R.id.island_countdown, 1000, ((remainingMs * 1000) / ISLAND_MS).toInt().coerceIn(0, 1000), false)
        if (aod) {
            Style.aodBody(views, R.id.island_app, R.id.island_sender, R.id.island_time, R.id.island_message, R.id.island_initial)
            views.setImageViewResource(R.id.island_avatar, R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.island_badge_icon, R.drawable.ic_chat_badge_aod)
            views.setViewVisibility(R.id.island_eq, View.GONE)
            views.setViewVisibility(R.id.island_reply, View.GONE)
            views.setViewVisibility(R.id.island_countdown, View.GONE)
        } else {
            views.setOnClickPendingIntent(R.id.island_reply, CardActionReceiver.action(context, CardActionReceiver.ACTION_ISLAND_DISMISS))
        }
        val tap = openApp(context, REQUEST_ISLAND)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, ISLAND_TARGET_ID, views, message.sender, message.text, R.drawable.ic_chat_badge, tap, 3f)
    }
}
