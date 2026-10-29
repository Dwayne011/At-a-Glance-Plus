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
import com.ataglanceplus.live.TravelMode
import com.ataglanceplus.live.FinanceRules
import com.ataglanceplus.live.FinanceSource
import com.ataglanceplus.live.Quote
import com.ataglanceplus.live.SportsPhase
import com.ataglanceplus.live.SportsShowing
import com.ataglanceplus.live.SportsSource
import com.ataglanceplus.live.TorchNow
import com.ataglanceplus.live.TorchSource
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
    FINANCE("finance", "Finance", "Your stocks, when the rules below say so; one page each, taking turns"),
    SPORTS("sports", "Sports", "Your teams' games: live scores, results, kick-off times and the next fixture"),
    TORCH("torch", "Torch", "While the torch is on: tap to turn it off, and set its brightness"),
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

    fun calendar(context: Context, aod: Boolean, event: CalendarNow, given: List<ChipSpec>? = null): SmartspaceTarget {
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
        val chips = if (layout == ComplicationLayout.NONE) emptyList() else given ?: listOf(ChipSpec(Complication.STEPS), ChipSpec(Complication.BATTERY))
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

    fun timer(context: Context, aod: Boolean, timer: TimerNow, given: List<ChipSpec>? = null): SmartspaceTarget {
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
        timerChips(context, views, aod, given)
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

    fun alarm(context: Context, aod: Boolean, alarm: AlarmManager.AlarmClockInfo, given: List<ChipSpec>? = null): SmartspaceTarget {
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
        timerChips(context, views, aod, given)
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

    private fun timerChips(context: Context, views: RemoteViews, aod: Boolean, given: List<ChipSpec>?) {
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.RIGHT) given ?: listOf(ChipSpec(Complication.ALARM), ChipSpec(Complication.SLEEP)) else emptyList()
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)
    }

    private fun showFixed(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.timer_chrono, View.GONE)
        views.setViewVisibility(R.id.timer_fixed, View.VISIBLE)
        views.setTextViewText(R.id.timer_fixed, text)
    }

    // --- 8.7 Navigation ---

    fun nav(context: Context, aod: Boolean, nav: NavNow, given: List<ChipSpec>? = null): SmartspaceTarget {
        val key = "nav:${nav.key}"
        val views = RemoteViews(context.packageName, R.layout.card_navigation)
        // The platform matters most on public transport, so it takes the bold green spot and the
        // distance moves to the second line.
        val lead = nav.platform ?: nav.distance
        val detail = listOfNotNull(nav.distance.takeIf { nav.platform != null }, nav.detail.ifEmpty { null }).joinToString(" · ")
        if (lead != null) views.setTextViewText(R.id.nav_distance, lead) else views.setViewVisibility(R.id.nav_distance, View.GONE)
        views.setTextViewText(R.id.nav_instruction, nav.instruction)
        views.setTextViewText(R.id.nav_detail, detail)
        // The maps app's own picture (turn arrow, or a line's roundel) in its own colours;
        // otherwise the design's arrow in the card's green.
        if (nav.icon != null) {
            views.setImageViewIcon(R.id.nav_icon, nav.icon)
        } else {
            views.setInt(R.id.nav_icon, "setColorFilter", NAV_GREEN)
        }
        nav.mode?.let { mode ->
            views.setViewVisibility(R.id.nav_mode, View.VISIBLE)
            views.setImageViewResource(R.id.nav_mode, modeIcon(mode, aod))
            views.setInt(R.id.nav_mode, "setColorFilter", if (aod) Style.AOD_TEXT else NAV_GREEN)
        }
        nav.progress?.let { progress ->
            views.setViewVisibility(R.id.nav_progress, View.VISIBLE)
            views.setProgressBar(R.id.nav_progress, 1000, progress.coerceIn(0, 1000), false)
            val colour = if (aod) Style.AOD_TEXT else nav.progressColour ?: NAV_GREEN
            views.setColorStateList(R.id.nav_progress, "setProgressTintList", ColorStateList.valueOf(colour))
        }
        if (aod) {
            Style.aodBody(views, R.id.nav_distance, R.id.nav_instruction, R.id.nav_detail)
            views.setInt(R.id.nav_box, "setBackgroundResource", R.drawable.aod_outline_r12)
            if (nav.icon == null) views.setImageViewResource(R.id.nav_icon, R.drawable.ic_nav_turn_left_aod)
            views.setInt(R.id.nav_icon, "setColorFilter", Style.AOD_TEXT)
        }
        val chips = if (Prefs.complicationLayout(context) == ComplicationLayout.NONE) emptyList() else given ?: listOf(ChipSpec(Complication.BATTERY))
        Chips.add(context, views, chips, ComplicationLayout.RIGHT, aod)
        val tap = gate(context, nav.open, "com.google.android.apps.maps", key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        val title = listOfNotNull(lead, nav.instruction).joinToString(" · ")
        return Style.target(context, targetId(key), views, title, detail, R.drawable.ic_nav_turn_left, tap, 1.8f)
    }

    private fun modeIcon(mode: TravelMode, aod: Boolean): Int = when (mode) {
        TravelMode.TRANSIT -> if (aod) R.drawable.ic_mode_train_aod else R.drawable.ic_mode_train
        TravelMode.BUS -> if (aod) R.drawable.ic_mode_bus_aod else R.drawable.ic_mode_bus
        TravelMode.BIKE -> if (aod) R.drawable.ic_mode_bike_aod else R.drawable.ic_mode_bike
        TravelMode.CAR -> if (aod) R.drawable.ic_mode_car_aod else R.drawable.ic_mode_car
        TravelMode.WALK -> if (aod) R.drawable.ic_nav_walk_aod else R.drawable.ic_nav_walk
    }

    // --- Sports ---

    /**
     * A followed team's game, scoreboard style: live score and clock, the result for a while
     * after, the kick-off time (with a countdown) before, or the next fixture.
     */
    fun sports(context: Context, aod: Boolean, showing: SportsShowing.Team): SmartspaceTarget {
        val game = showing.game
        val key = showing.instanceKey
        val views = RemoteViews(context.packageName, R.layout.card_sports)
        listOf(
            Triple(game.home, R.id.sports_home_logo, R.id.sports_home_abbr),
            Triple(game.away, R.id.sports_away_logo, R.id.sports_away_abbr),
        ).forEach { (side, logo, abbr) ->
            val bitmap = SportsSource.logo(side.logoUrl)
            if (bitmap != null) views.setImageViewBitmap(logo, bitmap) else views.setViewVisibility(logo, View.GONE)
            views.setTextViewText(abbr, side.abbr)
        }
        val scored = showing.phase == SportsPhase.LIVE || showing.phase == SportsPhase.AFTER
        listOf(game.home to R.id.sports_home_score, game.away to R.id.sports_away_score).forEach { (side, id) ->
            if (scored) {
                views.setTextViewText(id, side.score ?: "0")
                // After the game the loser's score is dimmed.
                if (showing.phase == SportsPhase.AFTER && !side.winner && (game.home.winner || game.away.winner)) {
                    views.setTextColor(id, if (aod) Style.AOD_TEXT else DIMMED)
                }
            } else {
                views.setViewVisibility(id, View.GONE)
            }
        }
        views.setTextViewText(R.id.sports_league, game.leagueName)
        val (status, note) = when (showing.phase) {
            SportsPhase.LIVE -> game.status to "LIVE"
            SportsPhase.AFTER -> game.status to null
            SportsPhase.BEFORE -> clock(context, game.start) to countdown(game.start)
            SportsPhase.NEXT -> day(context, game.start) to "Next game"
        }
        views.setTextViewText(R.id.sports_status, status)
        if (note != null) {
            views.setViewVisibility(R.id.sports_note, View.VISIBLE)
            views.setTextViewText(R.id.sports_note, note)
            if (showing.phase == SportsPhase.LIVE) views.setTextColor(R.id.sports_note, if (aod) Style.AOD_TEXT else LIVE_RED)
        }
        if (aod) {
            Style.aodBody(
                views, R.id.sports_home_abbr, R.id.sports_away_abbr, R.id.sports_home_score, R.id.sports_away_score,
                R.id.sports_league, R.id.sports_status, R.id.sports_note
            )
        }
        val tap = gameTap(context, "${game.home.name} vs ${game.away.name}", game.link, key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        val title = "${game.home.abbr} ${game.home.score ?: ""} ${game.away.abbr} ${game.away.score ?: ""}".trim()
        return Style.target(context, targetId(key), views, title, "$status · ${game.leagueName}", R.drawable.ic_nav_flag, tap, if (showing.phase == SportsPhase.LIVE) 1.65f else 1.2f)
    }

    /** A followed series' session (Formula 1): live order, result, start time or the next session. */
    fun race(context: Context, aod: Boolean, showing: SportsShowing.Race): SmartspaceTarget {
        val session = showing.session
        val key = showing.instanceKey
        val views = RemoteViews(context.packageName, R.layout.card_race)
        val title = "${session.eventName} · ${session.session}"
        val podium = session.top.mapIndexed { i, name -> "${i + 1} ${name.substringAfter(". ")}" }.joinToString(" · ")
        // The pill is the Next button, so what the session is doing leads the detail line.
        val detail: CharSequence = when (showing.phase) {
            SportsPhase.LIVE -> android.text.SpannableString("LIVE · " + podium.ifEmpty { session.status }).apply {
                if (!aod) setSpan(android.text.style.ForegroundColorSpan(LIVE_RED), 0, 4, 0)
            }
            SportsPhase.AFTER -> "Result · " + podium.ifEmpty { session.status }
            SportsPhase.BEFORE -> "${clock(context, session.start)} · ${countdown(session.start)}"
            SportsPhase.NEXT -> day(context, session.start)
        }
        views.setTextViewText(R.id.race_title, title)
        views.setTextViewText(R.id.race_detail, detail)
        if (showing.sessions.size > 1) {
            views.setTextViewText(R.id.race_badge_text, if (showing.lastStep) "Back ›" else "Next ›")
            views.setOnClickPendingIntent(R.id.race_next, CardActionReceiver.sportsStep(context, session.eventId))
        } else {
            views.setViewVisibility(R.id.race_next, View.GONE)
        }
        if (aod) {
            Style.aodBody(views, R.id.race_title, R.id.race_detail, R.id.race_badge_text)
            views.setInt(R.id.race_badge, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setInt(R.id.race_badge_text, "setBackgroundResource", R.drawable.aod_outline_r14)
            views.setImageViewResource(R.id.race_icon, R.drawable.ic_nav_flag_aod)
        }
        val tap = gameTap(context, "F1 ${session.eventName} ${session.session}", session.link, key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, title, detail.toString(), R.drawable.ic_nav_flag, tap, if (showing.phase == SportsPhase.LIVE) 1.65f else 1.2f)
    }

    /**
     * After unlocking: Google's results for the game (scores, stats and highlights, in the Google
     * app when installed), or ESPN's page if the user prefers it.
     */
    private fun gameTap(context: Context, search: String, link: String?, key: String): PendingIntent {
        val code = REQUEST_BASE + (key.hashCode() and 0xFFFF)
        if (Prefs.sportsOpenGoogle(context) || link == null) {
            val app = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, search).setPackage(GOOGLE_APP)
            val intent = if (app.resolveActivity(context.packageManager) != null) app
            else Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(search)))
            return Style.gated(context, intent, code)
        }
        return Style.gated(context, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)), code)
    }

    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    private fun clock(context: Context, time: Long): String =
        DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Date(time)).toString()

    /** "Today 20:00", "Sat 16:30", or "1 Nov 16:30" further ahead. */
    private fun day(context: Context, time: Long): String {
        val days = (time - System.currentTimeMillis()) / (24 * 3_600_000L)
        val prefix = when {
            DateUtils.isToday(time) -> "Today"
            days < 6 -> DateFormat.format("EEE", Date(time)).toString()
            else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Date(time)).toString()
        }
        return "$prefix ${clock(context, time)}"
    }

    /** "in 1 h 20 min", "in 25 min", or "Starting" once the time has come. */
    private fun countdown(time: Long): String {
        val minutes = ((time - System.currentTimeMillis()) / 60_000L).toInt()
        return when {
            minutes <= 0 -> "Starting"
            minutes < 60 -> "in $minutes min"
            else -> "in ${minutes / 60} h ${minutes % 60} min"
        }
    }

    // --- Torch ---

    private val TORCH_STEPS = intArrayOf(
        R.id.torch_step_1, R.id.torch_step_2, R.id.torch_step_3, R.id.torch_step_4,
        R.id.torch_step_5, R.id.torch_step_6, R.id.torch_step_7, R.id.torch_step_8,
    )
    private val TORCH_SEGS = intArrayOf(
        R.id.torch_seg_1, R.id.torch_seg_2, R.id.torch_seg_3, R.id.torch_seg_4,
        R.id.torch_seg_5, R.id.torch_seg_6, R.id.torch_seg_7, R.id.torch_seg_8,
    )

    /**
     * While the torch is on. A tap anywhere but the controls turns it off, without unlocking (like
     * the quick settings tile). The bar's steps and − / + set the brightness.
     */
    fun torch(context: Context, aod: Boolean, torch: TorchNow): SmartspaceTarget {
        val views = RemoteViews(context.packageName, R.layout.card_torch)
        val (lit, count) = TorchSource.steps(torch)
        val dimmable = count > 1
        val detail = if (dimmable) "Brightness $lit of $count · tap to turn off" else "Tap to turn off"
        views.setTextViewText(R.id.torch_detail, detail)
        if (dimmable) {
            TORCH_STEPS.forEachIndexed { i, id ->
                val step = i + 1
                if (step > count) {
                    views.setViewVisibility(id, View.GONE)
                    return@forEachIndexed
                }
                val on = step <= lit
                views.setImageViewResource(
                    TORCH_SEGS[i],
                    if (aod) (if (on) R.drawable.aod_segment_on else R.drawable.aod_segment_off) else if (on) R.drawable.torch_seg_on else R.drawable.torch_seg_off
                )
                views.setOnClickPendingIntent(id, CardActionReceiver.torchStep(context, step))
            }
            views.setOnClickPendingIntent(R.id.torch_down, CardActionReceiver.torchNudge(context, -1))
            views.setOnClickPendingIntent(R.id.torch_up, CardActionReceiver.torchNudge(context, +1))
        } else {
            views.setViewVisibility(R.id.torch_controls, View.GONE)
        }
        if (aod) {
            Style.aodBody(views, R.id.torch_title, R.id.torch_detail)
            views.setInt(R.id.torch_badge, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.torch_icon, R.drawable.ic_torch_aod)
            views.setInt(R.id.torch_down_bg, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setInt(R.id.torch_up_bg, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.torch_down_icon, R.drawable.ic_minus_aod)
            views.setImageViewResource(R.id.torch_up_icon, R.drawable.ic_plus_aod)
        }
        val off = CardActionReceiver.action(context, CardActionReceiver.ACTION_TORCH_OFF)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, off)
        return Style.target(context, targetId("torch"), views, "Torch on", detail, R.drawable.ic_torch, off, 2f)
    }

    // --- Extra chips ---

    /** A card of chips that did not fit on the other cards: up to 4 per row, two rows. */
    fun chips(context: Context, aod: Boolean, specs: List<ChipSpec>, index: Int): SmartspaceTarget {
        val views = RemoteViews(context.packageName, R.layout.card_chips)
        if (aod) Style.aodBody(views)
        Chips.add(context, views, specs.take(Chips.MAX_BOTTOM), ComplicationLayout.BOTTOM, aod)
        Chips.add(context, views, specs.drop(Chips.MAX_BOTTOM), ComplicationLayout.BOTTOM, aod, R.id.complications_2)
        val summary = Chips.withValues(specs).joinToString(" · ") { (spec, value) -> "${spec.kind.label} ${value.text}" }
        val tap = Style.gated(context, Intent(context, com.ataglanceplus.ui.MainActivity::class.java), REQUEST_BASE + 0xFF00 + index)
        return Style.target(context, "aagp_chips:$index", views, "More", summary, R.drawable.ic_card, tap, 0.5f)
    }

    // --- Finance ---

    /**
     * The user's stocks, one page each, taking turns. When shown because of a big move, the
     * biggest movers come first; otherwise the user's order. Each page opens that stock on
     * Google Finance.
     */
    fun finance(context: Context, aod: Boolean, quotes: List<Quote>, showing: FinanceRules.Showing): SmartspaceTarget {
        val key = showing.instanceKey
        val ordered = (if (showing.byMoves) quotes.sortedByDescending { it.moved } else quotes).take(MAX_STOCKS)
        val views = RemoteViews(context.packageName, R.layout.card_finance)
        if (aod) Style.aodBody(views)
        ordered.forEach { quote ->
            val up = quote.change >= 0
            val colour = if (aod) Style.AOD_TEXT else if (up) UP else DOWN
            val page = RemoteViews(context.packageName, R.layout.include_finance_page)
            page.setTextViewText(R.id.finance_title, "${quote.symbol} · ${quote.name}")
            page.setTextViewText(R.id.finance_detail, "${price(quote.price, quote.currency)} · ${signed(quote.change)}")
            page.setTextViewText(R.id.finance_change, signed(quote.changePercent) + "%")
            page.setTextColor(R.id.finance_change, colour)
            page.setImageViewResource(R.id.finance_trend, if (up) R.drawable.ic_trend_up else R.drawable.ic_trend_down)
            page.setInt(R.id.finance_trend, "setColorFilter", colour)
            if (aod) {
                page.setInt(R.id.finance_badge, "setBackgroundResource", R.drawable.aod_outline_r12)
                page.setInt(R.id.finance_change, "setBackgroundResource", R.drawable.aod_outline_r14)
                page.setTextColor(R.id.finance_title, Style.AOD_TEXT)
                page.setTextColor(R.id.finance_detail, Style.AOD_TEXT)
            } else {
                page.setInt(R.id.finance_badge, "setBackgroundResource", if (up) R.drawable.finance_badge_up else R.drawable.finance_badge_down)
                page.setInt(R.id.finance_change, "setBackgroundResource", if (up) R.drawable.finance_pill_up else R.drawable.finance_pill_down)
            }
            chart(quote, colour)?.let { page.setImageViewBitmap(R.id.finance_chart, it) }
            if (!aod) {
                val open = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(FinanceSource.googleFinanceUrl(quote)))
                page.setOnClickPendingIntent(R.id.finance_page, Style.gated(context, open, REQUEST_BASE + ("stock:" + quote.symbol).hashCode().and(0xFFFF)))
            }
            views.addView(R.id.finance_flipper, page)
        }
        val first = ordered.first()
        val tap = Style.gated(
            context, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(FinanceSource.googleFinanceUrl(first))),
            REQUEST_BASE + ("stock:" + first.symbol).hashCode().and(0xFFFF)
        )
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(
            context, targetId(key), views, "${first.symbol} ${price(first.price, first.currency)}",
            signed(first.changePercent) + "%", R.drawable.ic_trend_up, tap, 1.1f
        )
    }

    /** "117.45p" for pence-quoted London prices, otherwise "189.20 USD". */
    private fun price(value: Double, currency: String): String = when (currency) {
        "GBp", "GBX" -> String.format(Locale.getDefault(), "%.2fp", value)
        "" -> String.format(Locale.getDefault(), "%.2f", value)
        else -> String.format(Locale.getDefault(), "%.2f %s", value, currency)
    }

    private fun signed(value: Double): String = String.format(Locale.getDefault(), "%+.2f", value)

    /**
     * Today's prices as a small line chart (128 x 44 px, drawn at 2x for the 64 x 22 dp slot), with
     * yesterday's close as a faint line for reference.
     */
    private fun chart(quote: Quote, colour: Int): android.graphics.Bitmap? {
        val points = quote.points
        if (points.size < 2) return null
        val w = 128f
        val h = 44f
        val pad = 3f
        val low = minOf(points.min(), quote.previousClose)
        val high = maxOf(points.max(), quote.previousClose)
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (h - pad - (v - low) / span * (h - 2 * pad)).toFloat()
        val bitmap = android.graphics.Bitmap.createBitmap(w.toInt(), h.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val base = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF
            strokeWidth = 1.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        canvas.drawLine(0f, y(quote.previousClose), w, y(quote.previousClose), base)
        val path = android.graphics.Path()
        points.forEachIndexed { i, v ->
            val x = pad + i * (w - 2 * pad) / (points.size - 1)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        canvas.drawPath(path, line)
        return bitmap
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
    private const val NAV_GREEN = 0xFF6EE7B7.toInt()
    private const val UP = 0xFF34D399.toInt()
    private const val LIVE_RED = 0xFFF87171.toInt()
    private const val DIMMED = 0x99FFFFFF.toInt()
    private const val DOWN = 0xFFF87171.toInt()
    private const val MAX_STOCKS = 6
    private const val ALARM_WINDOW_MS = 12 * 60 * 60 * 1000L
}
