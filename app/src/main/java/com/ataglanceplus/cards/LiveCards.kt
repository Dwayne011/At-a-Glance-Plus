package com.ataglanceplus.cards

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.smartspace.SmartspaceTarget
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.live.CalendarNow
import com.ataglanceplus.live.IncomingMessage
import com.ataglanceplus.live.LiveNow
import com.ataglanceplus.live.MediaNow
import com.ataglanceplus.live.NavNow
import com.ataglanceplus.live.TimerNow
import com.ataglanceplus.ui.UnlockGateActivity
import java.util.Date
import java.util.Locale

/** Card types the user can switch off. Each appears only when there is something real to show. */
enum class CardType(val id: String, val label: String, val detail: String) {
    NAV("nav", "Navigation", "Turn-by-turn directions from Google Maps or another maps app"),
    LIVE("live", "Live updates", "Deliveries, rides, parcels and flights from any app that posts Live Updates"),
    TIMER("timer", "Timers and alarms", "A running timer, or your next alarm within 12 hours"),
    MEDIA("media", "Media", "Whatever is playing, with controls; stays 5 minutes after pausing"),
    CALENDAR("calendar", "Calendar", "Your next event, shortly before it starts and while it is on"),
    MESSAGES("messages", "Message pop-up", "New messages show for 4.5 seconds in place of the first card"),
}

/**
 * The cards other than weather, drawn from real data. Every card has an instance key
 * ("media:com.spotify.music"); dismissing a card hides that instance until it ends.
 */
object LiveCards {

    const val ISLAND_TARGET_ID = "aagp_island"
    private const val TARGET_PREFIX = "aagp_card:"

    /** Message toast duration (spec 6.1). */
    const val ISLAND_MS = 4_500L

    fun targetId(instanceKey: String) = TARGET_PREFIX + instanceKey

    /** The instance key behind a target id, or null for the weather card and the toast. */
    fun instanceKey(targetId: String): String? = targetId.removePrefix(TARGET_PREFIX).takeIf { targetId.startsWith(TARGET_PREFIX) }

    /** Another app's PendingIntent, sent only after unlocking. Falls back to launching the app. */
    private fun gate(context: Context, open: PendingIntent?, pkg: String?, instanceKey: String): PendingIntent {
        val code = REQUEST_BASE + (instanceKey.hashCode() and 0xFFFF)
        if (open != null) return UnlockGateActivity.wrap(context, open, code)
        return Style.gatedApp(context, pkg ?: context.packageName, code)
    }

    private fun dismissButton(context: Context, views: RemoteViews, instanceKey: String, aod: Boolean) {
        if (aod) views.setViewVisibility(R.id.dismiss, View.GONE)
        else views.setOnClickPendingIntent(R.id.dismiss, CardActionReceiver.dismiss(context, instanceKey))
    }

    // --- 8.2 Live update ---

    fun live(context: Context, aod: Boolean, live: LiveNow): SmartspaceTarget {
        val key = "live:${live.key}"
        val title = live.title.ifEmpty { live.app }
        val detail = listOf(live.app, live.text).filter { it.isNotEmpty() && it != title }.joinToString(" · ")
        val views = RemoteViews(context.packageName, R.layout.card_live_update)
        if (live.icon != null) {
            views.setImageViewIcon(R.id.live_app_icon, live.icon)
            views.setViewVisibility(R.id.live_app_icon, View.VISIBLE)
            views.setViewVisibility(R.id.live_app_initial, View.GONE)
            views.setInt(R.id.live_app_icon, "setColorFilter", if (aod) Style.AOD_TEXT else TEAL)
        } else {
            views.setTextViewText(R.id.live_app_initial, live.app.take(1).uppercase())
        }
        views.setTextViewText(R.id.live_title, title)
        views.setTextViewText(R.id.live_detail, detail)
        if (live.chip != null) views.setTextViewText(R.id.live_eta, live.chip) else views.setViewVisibility(R.id.live_eta, View.GONE)
        val segments = intArrayOf(R.id.live_seg_1, R.id.live_seg_2, R.id.live_seg_3, R.id.live_seg_4)
        if (live.filled == null) {
            views.setViewVisibility(R.id.live_segments, View.GONE)
        } else {
            segments.forEachIndexed { i, id ->
                val on = i < live.filled
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
        }
        dismissButton(context, views, key, aod)
        val tap = gate(context, live.open, null, key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, title, detail, R.drawable.ic_card, tap, 1.6f)
    }

    // --- 8.3 Media ---

    fun media(context: Context, aod: Boolean, media: MediaNow): SmartspaceTarget {
        val key = "media:${media.pkg}"
        val playing = media.playing && !aod
        val wave = Prefs.mediaWave(context)
        val artist = media.artist.ifEmpty { media.app }

        val views = RemoteViews(context.packageName, R.layout.card_media)
        views.setTextViewText(R.id.media_title, media.title)
        views.setTextViewText(R.id.media_detail, "$artist ·")
        if (media.art != null) {
            views.setImageViewBitmap(R.id.media_art, media.art)
        } else {
            views.setViewVisibility(R.id.media_art_fallback_icon, View.VISIBLE)
        }

        // Position: the Chronometer counts up by itself while playing. Stopped, it would work its
        // value out again whenever the card is redrawn, so a paused position is plain text.
        val duration = if (media.durationMs > 0) " / " + DateUtils.formatElapsedTime(media.durationMs / 1000) else ""
        val position = media.positionNow().coerceAtLeast(0)
        if (playing) {
            views.setChronometer(R.id.media_time, SystemClock.elapsedRealtime() - position, "%s$duration", true)
        } else {
            views.setTextViewText(R.id.media_time, DateUtils.formatElapsedTime(position / 1000) + duration)
        }

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
            val fraction = if (media.durationMs > 0) (1000 * position / media.durationMs).toInt() else 0
            views.setProgressBar(R.id.media_track, 1000, fraction.coerceIn(0, 1000), false)
        }
        views.setImageViewResource(
            R.id.media_play_icon,
            when {
                aod -> if (media.playing) R.drawable.ic_media_pause_aod else R.drawable.ic_media_play_aod
                playing -> R.drawable.ic_media_pause
                else -> R.drawable.ic_media_play
            }
        )
        if (aod) {
            Style.aodBody(views, R.id.media_title, R.id.media_detail, R.id.media_time, R.id.media_eq_label)
            views.setInt(R.id.media_art, "setBackgroundResource", R.drawable.aod_outline_r12)
            if (media.art != null) views.setImageViewResource(R.id.media_art, android.R.color.transparent)
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
        val tap = gate(context, media.open, media.pkg, key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, media.title, artist, R.drawable.ic_music_note, tap, 1.5f)
    }

    // --- 8.4 Calendar ---

    fun calendar(context: Context, aod: Boolean, event: CalendarNow): SmartspaceTarget {
        val key = event.instanceKey
        val now = System.currentTimeMillis()
        val time = { t: Long -> DateFormat.getTimeFormat(context).format(Date(t)) }
        val detail = listOfNotNull(
            when {
                event.allDay -> "All day"
                event.begin > now -> {
                    val minutes = ((event.begin - now + 59_999) / 60_000).toInt()
                    "${time(event.begin)} · in " + if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
                }
                else -> "${time(event.begin)} – ${time(event.end)}"
            },
            event.location.ifEmpty { null },
        ).joinToString(" · ")
        val layout = Prefs.complicationLayout(context)
        val chips = if (layout == ComplicationLayout.NONE) emptyList() else listOf(ChipSpec(Complication.STEPS), ChipSpec(Complication.BATTERY))
        val bottom = layout == ComplicationLayout.BOTTOM && Chips.withValues(chips).isNotEmpty()
        val views = RemoteViews(context.packageName, if (bottom) R.layout.card_calendar_bottom else R.layout.card_calendar)
        views.setTextViewText(R.id.calendar_title, event.title)
        views.setTextViewText(R.id.calendar_detail, detail)
        views.setInt(R.id.calendar_bar, "setColorFilter", if (aod) Style.AOD_TEXT else event.colour)
        if (aod) Style.aodBody(views, R.id.calendar_title, R.id.calendar_detail)
        Chips.add(context, views, chips, if (bottom) ComplicationLayout.BOTTOM else ComplicationLayout.RIGHT, aod)

        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        val tap = Style.gated(context, intent, REQUEST_BASE + (key.hashCode() and 0xFFFF))
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, event.title, detail, R.drawable.ic_comp_countdown, tap, 1.4f)
    }

    // --- 8.5 Timer and next alarm ---

    fun timer(context: Context, aod: Boolean, timer: TimerNow): SmartspaceTarget {
        val key = "timer:${timer.key}"
        val views = RemoteViews(context.packageName, R.layout.card_timer)
        val running = timer.endsAt != null
        val readout: String
        if (running) {
            val left = (timer.endsAt!! - System.currentTimeMillis()).coerceAtLeast(0)
            readout = DateUtils.formatElapsedTime(left / 1000)
            // The system ticks the Chronometer itself; the app does not redraw every second.
            views.setChronometer(R.id.timer_chrono, SystemClock.elapsedRealtime() + left, null, true)
            views.setChronometerCountDown(R.id.timer_chrono, true)
        } else {
            readout = timer.pausedText.orEmpty()
            showFixed(views, readout)
        }
        val detail = listOfNotNull("Timer", if (running) "Running" else "Paused", nextAlarmText(context)?.let { "Next alarm $it" })
            .joinToString(" · ")
        views.setTextViewText(R.id.timer_title, timer.title)
        views.setTextViewText(R.id.timer_detail, detail)
        if (timer.toggle != null && !aod) {
            views.setImageViewResource(R.id.timer_button_icon, if (running) R.drawable.ic_media_pause else R.drawable.ic_media_play)
            views.setOnClickPendingIntent(R.id.timer_button, timer.toggle)
        } else {
            views.setViewVisibility(R.id.timer_button, View.GONE)
        }
        if (aod) Style.aodBody(views, R.id.timer_chrono, R.id.timer_fixed, R.id.timer_title, R.id.timer_detail)
        timerChips(context, views, aod)
        val tap = gate(context, timer.open, null, key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, "$readout ${timer.title}", detail, R.drawable.ic_comp_alarm, tap, 1.7f)
    }

    /** The next alarm, when it is within [ALARM_WINDOW_MS]. */
    fun upcomingAlarm(context: Context): AlarmManager.AlarmClockInfo? {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return next.takeIf { it.triggerTime - System.currentTimeMillis() in 0..ALARM_WINDOW_MS }
    }

    fun alarm(context: Context, aod: Boolean, alarm: AlarmManager.AlarmClockInfo): SmartspaceTarget {
        val key = "alarm:${alarm.triggerTime}"
        val views = RemoteViews(context.packageName, R.layout.card_timer)
        val readout = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Date(alarm.triggerTime)).toString()
        val tomorrow = !DateUtils.isToday(alarm.triggerTime)
        val title = if (tomorrow) "Alarm tomorrow" else "Alarm"
        val detail = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Date(alarm.triggerTime)).toString()
        showFixed(views, readout)
        views.setViewVisibility(R.id.timer_button, View.GONE)
        views.setTextViewText(R.id.timer_title, title)
        views.setTextViewText(R.id.timer_detail, detail)
        if (aod) Style.aodBody(views, R.id.timer_fixed, R.id.timer_title, R.id.timer_detail)
        timerChips(context, views, aod)
        val tap = alarm.showIntent?.let { gate(context, it, null, key) }
            ?: Style.gated(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), REQUEST_BASE + (key.hashCode() and 0xFFFF))
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, "$readout $title", detail, R.drawable.ic_comp_alarm, tap, 1.2f)
    }

    private fun nextAlarmText(context: Context): String? {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        if (next.triggerTime - System.currentTimeMillis() > 24 * 60 * 60 * 1000L) return null
        return DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Date(next.triggerTime)).toString()
    }

    private fun timerChips(context: Context, views: RemoteViews, aod: Boolean) {
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.RIGHT) listOf(ChipSpec(Complication.ALARM), ChipSpec(Complication.SLEEP)) else emptyList()
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)
    }

    private fun showFixed(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.timer_chrono, View.GONE)
        views.setViewVisibility(R.id.timer_fixed, View.VISIBLE)
        views.setTextViewText(R.id.timer_fixed, text)
    }

    // --- 8.7 Navigation ---

    fun nav(context: Context, aod: Boolean, nav: NavNow): SmartspaceTarget {
        val key = "nav:${nav.key}"
        val views = RemoteViews(context.packageName, R.layout.card_navigation)
        if (nav.distance != null) views.setTextViewText(R.id.nav_distance, nav.distance) else views.setViewVisibility(R.id.nav_distance, View.GONE)
        views.setTextViewText(R.id.nav_instruction, nav.instruction)
        views.setTextViewText(R.id.nav_detail, nav.detail)
        // The maps app's own turn arrow when it gives one, tinted like the design's arrow.
        if (nav.icon != null) views.setImageViewIcon(R.id.nav_icon, nav.icon)
        if (aod) {
            Style.aodBody(views, R.id.nav_distance, R.id.nav_instruction, R.id.nav_detail)
            views.setInt(R.id.nav_box, "setBackgroundResource", R.drawable.aod_outline_r12)
            if (nav.icon == null) views.setImageViewResource(R.id.nav_icon, R.drawable.ic_nav_turn_left_aod)
            views.setInt(R.id.nav_icon, "setColorFilter", Style.AOD_TEXT)
        }
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.NONE) emptyList() else listOf(ChipSpec(Complication.BATTERY))
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)
        val tap = gate(context, nav.open, "com.google.android.apps.maps", key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        val title = listOfNotNull(nav.distance, nav.instruction).joinToString(" · ")
        return Style.target(context, targetId(key), views, title, nav.detail, R.drawable.ic_nav_turn_left, tap, 1.8f)
    }

    // --- 6.1 Incoming message toast ---

    /** The toast shown in place of the first card; [remainingMs] drives the 3dp countdown bar. */
    fun island(context: Context, aod: Boolean, message: IncomingMessage, remainingMs: Long, hideText: Boolean): SmartspaceTarget {
        val views = RemoteViews(context.packageName, R.layout.include_island_message_toast)
        views.setTextViewText(R.id.island_app, message.app)
        views.setTextColor(R.id.island_app, message.colour)
        views.setInt(R.id.island_avatar, "setColorFilter", message.colour)
        views.setTextViewText(R.id.island_sender, message.sender)
        views.setTextViewText(R.id.island_initial, message.sender.trimStart('#', '+').take(1).uppercase())
        // On the lock screen, follow Android's "sensitive content" setting: sender only.
        val text = if (hideText) "New message" else message.text
        views.setTextViewText(R.id.island_message, text)
        views.setProgressBar(R.id.island_countdown, 1000, ((remainingMs * 1000) / ISLAND_MS).toInt().coerceIn(0, 1000), false)
        // Reply and the toast itself open the conversation, after unlocking.
        val open = gate(context, message.open, message.pkg, "message:${message.key}")
        if (aod) {
            Style.aodBody(views, R.id.island_app, R.id.island_sender, R.id.island_time, R.id.island_message, R.id.island_initial)
            views.setImageViewResource(R.id.island_avatar, R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.island_badge_icon, R.drawable.ic_chat_badge_aod)
            views.setViewVisibility(R.id.island_eq, View.GONE)
            views.setViewVisibility(R.id.island_reply, View.GONE)
            views.setViewVisibility(R.id.island_countdown, View.GONE)
        } else {
            views.setOnClickPendingIntent(R.id.island_reply, open)
        }
        views.setOnClickPendingIntent(R.id.card_root, open)
        return Style.target(context, ISLAND_TARGET_ID, views, message.sender, text, R.drawable.ic_chat_badge, open, 3f)
    }

    /**
     * Whether the lock screen may show message text. Android does not let every app read this
     * setting; when it cannot be read, the text is hidden to be safe.
     */
    fun lockScreenShowsPrivate(context: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 0) == 1
    }.getOrDefault(false)

    private const val REQUEST_BASE = 0x20000
    private const val TEAL = 0xFF2DD4BF.toInt()
    private const val ALARM_WINDOW_MS = 12 * 60 * 60 * 1000L
}
