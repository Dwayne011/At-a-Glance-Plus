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
import com.ataglanceplus.live.NavDirection
import com.ataglanceplus.live.NavLeg
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
    CALENDAR("calendar", "Calendar", "Each event from shortly before it starts until it ends (or you dismiss it); several stack"),
    MESSAGES("messages", "Message pop-up", "New messages show for 4.5 seconds in place of the first card"),
    FINANCE("finance", "Finance", "Your stocks, always up and updating; one page each, taking turns"),
    SPORTS("sports", "Sports", "Your teams' games: live scores, results, kick-off times and the next fixture"),
    TORCH("torch", "Torch", "While the torch is on: tap to turn it off, and set its brightness"),
    WALLET("wallet", "Wallet passes", "Your Google Wallet passes, near the places and times you set; tap to show the code"),
}

/**
 * The cards other than weather, drawn from real data. Every card has an instance key
 * ("media:com.spotify.music"); dismissing a card hides that instance until it ends.
 */
object LiveCards {

    const val ISLAND_TARGET_ID = "aagp_island"
    private const val TARGET_PREFIX = "aagp_card:"

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
        // The album under the artist, unless it only repeats the title.
        val album = media.album.takeIf { it.isNotBlank() && !it.equals(media.title, ignoreCase = true) }
        views.setViewVisibility(R.id.media_album, if (album == null) View.GONE else View.VISIBLE)
        album?.let { views.setTextViewText(R.id.media_album, it) }
        if (aod) Style.aodBody(views, R.id.media_album)
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

        // Equaliser: animated while playing, flat bars when paused or on the always-on display.
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
        val chips = given ?: listOf(ChipSpec(Complication.STEPS), ChipSpec(Complication.BATTERY))
        val views = RemoteViews(context.packageName, R.layout.card_calendar)
        views.setTextViewText(R.id.calendar_title, event.title)
        views.setTextViewText(R.id.calendar_detail, detail)
        views.setInt(R.id.calendar_bar, "setColorFilter", if (aod) Style.AOD_TEXT else event.colour)
        // The next event later today, under the time and place.
        val then = event.thenTitle?.let { title -> event.thenBegin?.let { "Then ${time(it)} · $title" } }
        views.setViewVisibility(R.id.calendar_next, if (then == null) View.GONE else View.VISIBLE)
        then?.let { views.setTextViewText(R.id.calendar_next_text, it) }
        views.setInt(R.id.calendar_next_icon, "setColorFilter", if (aod) Style.AOD_TEXT else CALENDAR_NEXT)
        if (aod) Style.aodBody(views, R.id.calendar_title, R.id.calendar_detail, R.id.calendar_next_text)
        Chips.add(context, views, chips, aod)

        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        val tap = Style.gated(context, intent, REQUEST_BASE + (key.hashCode() and 0xFFFF))
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, event.title, detail, R.drawable.ic_comp_countdown, tap, 1.4f)
    }

    // --- 8.6 Wallet pass ---

    fun wallet(context: Context, aod: Boolean, pass: com.ataglanceplus.wallet.WalletPass): SmartspaceTarget {
        val key = "wallet:${pass.id}"
        val views = RemoteViews(context.packageName, R.layout.card_wallet)
        views.setTextViewText(R.id.wallet_title, pass.title)
        val detail = pass.subtitle ?: pass.issuer ?: ""
        views.setTextViewText(R.id.wallet_detail, detail)
        views.setTextViewText(R.id.wallet_initial, pass.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•")

        val barcode = pass.barcode
        when {
            aod || barcode == null -> {
                views.setViewVisibility(R.id.wallet_barcode_box, View.GONE)
                views.setViewVisibility(R.id.wallet_qr_box, View.GONE)
                views.setViewVisibility(R.id.wallet_open, if (aod) View.GONE else View.VISIBLE)
            }
            com.ataglanceplus.wallet.WalletBarcode.isSquare(barcode.format) -> {
                views.setViewVisibility(R.id.wallet_barcode_box, View.GONE)
                views.setViewVisibility(R.id.wallet_qr_box, View.VISIBLE)
                views.setViewVisibility(R.id.wallet_open, View.GONE)
                com.ataglanceplus.wallet.WalletBarcode.render(barcode, 240)?.let { views.setImageViewBitmap(R.id.wallet_qr, it) }
            }
            else -> {
                views.setViewVisibility(R.id.wallet_barcode_box, View.VISIBLE)
                views.setViewVisibility(R.id.wallet_qr_box, View.GONE)
                views.setViewVisibility(R.id.wallet_open, View.GONE)
                com.ataglanceplus.wallet.WalletBarcode.render(barcode, 440)?.let { views.setImageViewBitmap(R.id.wallet_barcode, it) }
                views.setTextViewText(R.id.wallet_barcode_digits, barcode.displayText ?: barcode.value)
            }
        }
        if (aod) Style.aodBody(views, R.id.wallet_title, R.id.wallet_detail)

        val tap = Style.gated(context, com.ataglanceplus.wallet.WalletPassActivity.intent(context, pass.id), REQUEST_BASE + (key.hashCode() and 0xFFFF))
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, pass.title, detail, R.drawable.ic_card, tap, 1.5f)
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
        Chips.add(context, views, given ?: listOf(ChipSpec(Complication.ALARM), ChipSpec(Complication.SLEEP)), aod)
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
        // The arrow for the manoeuvre the instruction names, in the card's green; with none named
        // (a bus or train leg), the maps app's own picture (a line's roundel); otherwise straight on.
        val arrow = nav.direction ?: if (nav.icon == null) NavDirection.STRAIGHT else null
        if (arrow != null) {
            views.setImageViewResource(R.id.nav_icon, arrow.icon)
            views.setInt(R.id.nav_icon, "setColorFilter", if (aod) Style.AOD_TEXT else NAV_GREEN)
        } else {
            views.setImageViewIcon(R.id.nav_icon, nav.icon)
            if (aod) views.setInt(R.id.nav_icon, "setColorFilter", Style.AOD_TEXT)
        }
        nav.mode?.let { mode ->
            views.setViewVisibility(R.id.nav_mode, View.VISIBLE)
            views.setImageViewResource(R.id.nav_mode, modeIcon(mode, aod))
            views.setInt(R.id.nav_mode, "setColorFilter", if (aod) Style.AOD_TEXT else NAV_GREEN)
        }
        if (aod) {
            Style.aodBody(views, R.id.nav_distance, R.id.nav_instruction, R.id.nav_detail, R.id.nav_trip_left, R.id.nav_trip_arrival)
            views.setInt(R.id.nav_box, "setBackgroundResource", R.drawable.aod_outline_r12)
        }
        val chips = Chips.add(context, views, given ?: listOf(ChipSpec(Complication.BATTERY)), aod)
        tripFraction(nav)?.let { fraction ->
            // The text column's width in the card's dp: the card less its padding, the arrow box
            // and its gap, and the chip column when it has chips.
            val width = WeatherCard.cardWidthDp(context) - 34f - 48f - 11f - if (chips > 0) 92f else 0f
            views.setViewVisibility(R.id.nav_trip, View.VISIBLE)
            views.setImageViewBitmap(R.id.nav_trip, tripBar(context, width, nav, fraction, aod))
            val left = listOfNotNull(nav.distanceLeft, nav.minutesLeft?.let { minutesText(it) + " left" }).joinToString(" · ")
            val arrival = nav.arrival?.let { "Arrive $it" }
            if (left.isNotEmpty() || arrival != null) {
                views.setViewVisibility(R.id.nav_trip_labels, View.VISIBLE)
                views.setTextViewText(R.id.nav_trip_left, left)
                views.setTextViewText(R.id.nav_trip_arrival, arrival.orEmpty())
            }
        }
        val tap = gate(context, nav.open, "com.google.android.apps.maps", key)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        val title = listOfNotNull(lead, nav.instruction).joinToString(" · ")
        return Style.target(context, targetId(key), views, title, detail, R.drawable.ic_nav_turn_left, tap, 1.8f)
    }

    /** "12 min", or "1 h 5 min". */
    private fun minutesText(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

    /** The fraction of the trip shown last, per navigation, so the dot never steps back on its own. */
    private val tripShown = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /**
     * How far through the trip to draw the dot (0 to 1), or null with no progress from the maps
     * app. Between the app's updates it moves on at the pace the time left implies (at most 15%
     * past the app's figure); it never goes back unless the app's figure drops by more than 10%,
     * which is a reroute.
     */
    private fun tripFraction(nav: NavNow): Float? {
        val reported = (nav.progress ?: return null) / 1000f
        var fraction = reported
        val minutes = nav.minutesLeft
        if (minutes != null && minutes > 0) {
            val perMs = (1f - reported) / (minutes * 60_000f)
            val since = (System.currentTimeMillis() - nav.seenAt).coerceAtLeast(0L)
            fraction = (reported + perMs * since).coerceAtMost(minOf(1f, reported + 0.15f))
        }
        val shown = tripShown[nav.key]
        if (shown != null && fraction < shown && shown - fraction < 0.1f) fraction = shown
        if (tripShown.size > 8) tripShown.clear()
        tripShown[nav.key] = fraction
        return fraction.coerceIn(0f, 1f)
    }

    /**
     * The trip bar, [widthDp] wide and 14dp tall: a 6dp bar of the trip's legs, each in its line's
     * colour (one leg, or none given: the card's green), done in full and the rest faded; walks
     * (grey legs) dotted; a ring at each change; a white dot with a ring where you are; a flag at
     * the end. On the always-on display, all in grey.
     */
    private fun tripBar(context: Context, widthDp: Float, nav: NavNow, fraction: Float, aod: Boolean): android.graphics.Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).toInt().coerceAtLeast(1)
        val h = (14 * d).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val flagSpace = 16 * d
        val start = 5 * d
        val end = w - flagSpace
        val span = end - start
        val cy = h / 2f
        val thick = 6 * d
        val legs = nav.legs.takeIf { it.size > 1 } ?: listOf(NavLeg(1f, null))
        fun colourOf(leg: NavLeg) = when {
            aod -> Style.AOD_TEXT
            legs.size == 1 || leg.colour == null -> NAV_GREEN
            else -> leg.colour
        }
        fun grey(colour: Int): Boolean {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(colour, hsv)
            return hsv[1] < 0.2f
        }
        val at = start + span * fraction
        var x = start
        legs.forEachIndexed { i, leg ->
            val legEnd = if (i == legs.lastIndex) end else x + span * leg.share
            val gap = if (i < legs.lastIndex) 2 * d else 0f
            val colour = colourOf(leg)
            val dotted = !aod && legs.size > 1 && leg.colour != null && grey(leg.colour)
            fun draw(from: Float, to: Float, alpha: Int) {
                if (to <= from) return
                paint.color = colour
                paint.alpha = alpha
                if (dotted) {
                    var dx = from
                    while (dx < to) {
                        canvas.drawRect(dx, cy - 2 * d, minOf(dx + 4 * d, to), cy + 2 * d, paint)
                        dx += 7 * d
                    }
                } else {
                    canvas.drawRoundRect(from, cy - thick / 2, to, cy + thick / 2, thick / 2, thick / 2, paint)
                }
            }
            draw(x, legEnd - gap, 90)
            draw(x, minOf(at, legEnd - gap), 255)
            x = legEnd
        }
        // A ring at each change.
        paint.style = android.graphics.Paint.Style.FILL
        x = start
        legs.dropLast(1).forEach { leg ->
            x += span * leg.share
            paint.color = 0xFF0B1E1A.toInt(); paint.alpha = 255
            canvas.drawCircle(x - d, cy, 3.5f * d, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2 * d
            paint.color = if (aod) Style.AOD_TEXT else 0xFFE2E8F0.toInt()
            canvas.drawCircle(x - d, cy, 3f * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
        }
        // Where you are: white, ringed in the current leg's colour.
        var reach = 0f
        val current = legs.firstOrNull { reach += it.share; fraction <= reach + 0.0001f } ?: legs.last()
        paint.color = 0x59000000
        canvas.drawCircle(at, cy, 7 * d, paint)
        paint.color = colourOf(current)
        canvas.drawCircle(at, cy, 6 * d, paint)
        paint.color = if (aod) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawCircle(at, cy, 4 * d, paint)
        // The flag at the end.
        context.getDrawable(R.drawable.ic_nav_flag)?.mutate()?.let { flag ->
            flag.setTint(if (aod) Style.AOD_TEXT else 0xFFE2E8F0.toInt())
            val size = (12 * d).toInt()
            val left = (w - size - d).toInt()
            flag.setBounds(left, ((h - size) / 2f).toInt(), left + size, ((h + size) / 2f).toInt())
            flag.draw(canvas)
        }
        return bitmap
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
     * A followed team's game (Material You sports spec 3.1 and 3.2): each team in its own colours
     * with its HOME or AWAY tag, scores while live and after, and in the middle the league, the
     * clock or start time, and a pill: LIVE with its dot, the countdown or "Next game" before, the
     * result after. The surface is washed with the home team's colour from the left and the away
     * team's from the right, with a wave while live.
     */
    fun sports(context: Context, aod: Boolean, showing: SportsShowing.Team): SmartspaceTarget {
        val game = showing.game
        val key = showing.instanceKey
        val views = RemoteViews(context.packageName, R.layout.card_sports)
        val upcoming = showing.phase == SportsPhase.BEFORE || showing.phase == SportsPhase.NEXT
        listOf(
            TeamViews(game.home, R.id.sports_home_logo, R.id.sports_home_abbr, R.id.sports_home_circle, R.id.sports_home_ring, R.id.sports_wash_home, R.id.sports_home_side, home = true),
            TeamViews(game.away, R.id.sports_away_logo, R.id.sports_away_abbr, R.id.sports_away_circle, R.id.sports_away_ring, R.id.sports_wash_away, R.id.sports_away_side, home = false),
        ).forEach { t ->
            val bitmap = SportsSource.logo(t.side.logoUrl)
            if (bitmap != null) views.setImageViewBitmap(t.logo, bitmap) else views.setViewVisibility(t.logo, View.GONE)
            views.setTextViewText(t.abbr, t.side.abbr)
            val (main, alt) = SportsSource.colours(game, t.side) ?: (null to null)
            if (aod) {
                views.setViewVisibility(t.circle, View.GONE)
                views.setColorStateList(t.ring, "setImageTintList", ColorStateList.valueOf(Style.AOD_TEXT))
                views.setInt(t.tag, "setBackgroundResource", R.drawable.aod_outline_r12)
                views.setTextColor(t.tag, Style.AOD_TEXT)
            } else {
                // The team's colour, darkened to sit on the dark card (as the spec's examples are),
                // with a rim in its second colour.
                views.setColorStateList(t.circle, "setImageTintList", ColorStateList.valueOf(main?.let { darken(it, TEAM_CIRCLE_SHADE) } ?: NO_TEAM_COLOUR))
                views.setColorStateList(t.ring, "setImageTintList", ColorStateList.valueOf(alt?.let { it and 0x00FFFFFF or 0x80000000.toInt() } ?: RIM))
                main?.let { views.setColorStateList(t.wash, "setImageTintList", ColorStateList.valueOf(it)) } ?: views.setViewVisibility(t.wash, View.GONE)
                val (tag, text) = when {
                    upcoming && t.home -> R.drawable.tag_home_next to TAG_HOME_NEXT_TEXT
                    upcoming -> R.drawable.tag_away_next to TAG_AWAY_NEXT_TEXT
                    t.home -> R.drawable.tag_home_live to TAG_HOME_LIVE_TEXT
                    else -> R.drawable.tag_away_live to TAG_AWAY_LIVE_TEXT
                }
                views.setInt(t.tag, "setBackgroundResource", tag)
                views.setTextColor(t.tag, text)
            }
        }
        // The user may hide this team's scores: then the card says the game is on or over, not how.
        val hidden = SportsSource.scoresHidden(context, game.eventId)
        val scored = !hidden && (showing.phase == SportsPhase.LIVE || showing.phase == SportsPhase.AFTER)
        listOf(game.home to R.id.sports_home_score, game.away to R.id.sports_away_score).forEach { (side, id) ->
            if (scored) {
                views.setTextViewText(id, side.score ?: "0")
                // After the game the loser's score is dimmed.
                if (showing.phase == SportsPhase.AFTER && !side.winner && (game.home.winner || game.away.winner)) {
                    views.setTextColor(id, if (aod) Style.AOD_TEXT else TRAILING)
                }
            } else {
                views.setViewVisibility(id, View.GONE)
            }
        }
        views.setTextViewText(R.id.sports_league, game.leagueName)
        // The clock reads "1:31 · 3rd" rather than the feed's "1:31 - 3rd".
        val (status, pill) = when (showing.phase) {
            SportsPhase.LIVE -> game.status.replace(" - ", " · ") to "LIVE"
            SportsPhase.AFTER -> game.status.replace(" - ", " · ") to if (hidden) "Score hidden" else "Result"
            SportsPhase.BEFORE -> day(context, game.start) to countdown(game.start).replaceFirstChar { it.uppercase() }
            SportsPhase.NEXT -> day(context, game.start) to "Next game"
        }
        views.setTextViewText(R.id.sports_status, status)
        if (upcoming) views.setTextColor(R.id.sports_status, UPCOMING_TIME)
        views.setViewVisibility(R.id.sports_pill, View.VISIBLE)
        views.setTextViewText(R.id.sports_note, pill)
        when (showing.phase) {
            SportsPhase.LIVE -> if (!aod) views.setViewVisibility(R.id.sports_wave, View.VISIBLE)
            SportsPhase.AFTER -> {
                views.setViewVisibility(R.id.sports_dot, View.GONE)
                views.setInt(R.id.sports_pill, "setBackgroundResource", R.drawable.status_pill_final)
                views.setTextColor(R.id.sports_note, FINAL_TEXT)
            }
            else -> {
                views.setViewVisibility(R.id.sports_dot, View.GONE)
                views.setInt(R.id.sports_pill, "setBackgroundResource", R.drawable.status_pill_next)
                views.setTextColor(R.id.sports_note, NEXT_TEXT)
                views.setTextViewTextSize(R.id.sports_note, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        }
        if (aod) {
            views.setViewVisibility(R.id.sports_surface, View.GONE)
            views.setInt(R.id.sports_pill, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setViewVisibility(R.id.sports_dot, View.GONE)
            Style.aodBody(
                views, R.id.sports_home_abbr, R.id.sports_away_abbr, R.id.sports_home_score, R.id.sports_away_score,
                R.id.sports_league, R.id.sports_status, R.id.sports_note
            )
        }
        val tap = gameTap(context, "${game.home.name} vs ${game.away.name}", game.link, key, com.ataglanceplus.ui.DashboardActivity.KIND_GAME_PREFIX + game.eventId)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        val title = if (scored) "${game.home.abbr} ${game.home.score ?: ""} ${game.away.abbr} ${game.away.score ?: ""}".trim() else "${game.home.abbr} v ${game.away.abbr}"
        return Style.target(context, targetId(key), views, title, "$status · ${game.leagueName}", R.drawable.ic_nav_flag, tap, if (showing.phase == SportsPhase.LIVE) 1.65f else 1.2f)
    }

    /** One team's views on the sports card. */
    private class TeamViews(val side: com.ataglanceplus.live.Side, val logo: Int, val abbr: Int, val circle: Int, val ring: Int, val wash: Int, val tag: Int, val home: Boolean)

    /** [colour] with its red, green and blue scaled by [by] (0 to 1). */
    private fun darken(colour: Int, by: Float): Int {
        fun part(shift: Int) = (((colour shr shift) and 0xFF) * by).toInt().coerceIn(0, 255) shl shift
        return (0xFF shl 24) or part(16) or part(8) or part(0)
    }

    /**
     * A followed series' session, Formula 1 (Material You sports spec 3.3): the circuit in its box,
     * the Grand Prix, the session, and when (with a countdown before it, LIVE and the order during,
     * the result after), with Next stepping through the weekend's sessions.
     */
    fun race(context: Context, aod: Boolean, showing: SportsShowing.Race): SmartspaceTarget {
        val session = showing.session
        val key = showing.instanceKey
        val views = RemoteViews(context.packageName, R.layout.card_race)
        val grandPrix = session.eventName.replace("Grand Prix", "GP").trim()
        val title = "${session.eventName} · ${session.session}"
        val podium = if (SportsSource.scoresHidden(context, session.eventId)) "" else session.top.mapIndexed { i, name -> "${i + 1} ${name.substringAfter(". ")}" }.joinToString(" · ")
        views.setTextViewText(R.id.race_title, grandPrix)
        views.setTextViewText(R.id.race_session, sessionName(session.session))
        val (detail, pill) = when (showing.phase) {
            SportsPhase.LIVE -> podium.ifEmpty { session.status } to "LIVE"
            SportsPhase.AFTER -> ("Result · " + podium.ifEmpty { session.status }) to null
            SportsPhase.BEFORE -> day(context, session.start) to countdown(session.start).replaceFirstChar { it.uppercase() }
            SportsPhase.NEXT -> "${day(context, session.start)} · Next session" to countdown(session.start).replaceFirstChar { it.uppercase() }
        }
        views.setTextViewText(R.id.race_detail, detail)
        if (pill != null) {
            views.setViewVisibility(R.id.race_countdown, View.VISIBLE)
            views.setTextViewText(R.id.race_countdown, pill)
            if (showing.phase == SportsPhase.LIVE) {
                views.setInt(R.id.race_countdown, "setBackgroundResource", R.drawable.f1_live_pill)
                views.setTextColor(R.id.race_countdown, LIVE_RED)
            }
        }
        if (showing.sessions.size > 1) {
            // Next steps on through the weekend; after the first step, Back steps back one. On the
            // last session only Back is left.
            val next = CardActionReceiver.sportsStep(context, session.eventId)
            val back = CardActionReceiver.sportsStep(context, session.eventId, by = -1)
            if (showing.step == 0) {
                views.setTextViewText(R.id.race_badge_text, "Next ›")
                views.setOnClickPendingIntent(R.id.race_next, next)
            } else if (showing.lastStep) {
                views.setTextViewText(R.id.race_badge_text, "‹ Back")
                views.setOnClickPendingIntent(R.id.race_next, back)
            } else {
                views.setTextViewText(R.id.race_badge_text, "Next ›")
                views.setViewVisibility(R.id.race_back, View.VISIBLE)
                views.setViewLayoutHeight(R.id.race_badge_text, RACE_PAIR_DP, android.util.TypedValue.COMPLEX_UNIT_DIP)
                views.setOnClickPendingIntent(R.id.race_badge_text, next)
                views.setOnClickPendingIntent(R.id.race_back, back)
            }
        } else {
            views.setViewVisibility(R.id.race_next, View.GONE)
        }
        if (aod) {
            Style.aodBody(views, R.id.race_title, R.id.race_session, R.id.race_detail, R.id.race_badge_text, R.id.race_back, R.id.race_countdown)
            views.setInt(R.id.race_badge, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setInt(R.id.race_badge_text, "setBackgroundResource", R.drawable.aod_outline_r14)
            views.setInt(R.id.race_back, "setBackgroundResource", R.drawable.aod_outline_r14)
            views.setInt(R.id.race_countdown, "setBackgroundResource", R.drawable.aod_outline_r12)
            views.setViewVisibility(R.id.race_mark_top, View.GONE)
            views.setViewVisibility(R.id.race_mark_bottom, View.GONE)
            views.setImageViewResource(R.id.race_icon, R.drawable.ic_nav_flag_aod)
        }
        // The circuit's outline in place of the flag, when it is one we have.
        CircuitIcons.find(session.circuitCity, session.circuitName, aod)?.let { views.setImageViewResource(R.id.race_icon, it) }
        val tap = gameTap(context, "F1 ${session.eventName} ${session.session}", session.link, key, com.ataglanceplus.ui.DashboardActivity.KIND_RACE_PREFIX + session.eventId)
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, targetId(key), views, title, "$grandPrix · $detail", R.drawable.ic_nav_flag, tap, if (showing.phase == SportsPhase.LIVE) 1.65f else 1.2f)
    }

    /** "Practice 1 · FP1", "Qualifying", "Sprint", "Race": the session's name with ESPN's short code. */
    private fun sessionName(code: String): String = when (code.uppercase()) {
        "FP1" -> "Practice 1 · FP1"
        "FP2" -> "Practice 2 · FP2"
        "FP3" -> "Practice 3 · FP3"
        "QUAL", "Q" -> "Qualifying"
        "SQ", "SPRINT QUAL", "SS" -> "Sprint qualifying"
        "SPRINT", "SR" -> "Sprint"
        "RACE", "R" -> "Race"
        else -> code
    }

    /**
     * After unlocking: Google's results for the game (scores, stats and highlights, in the Google
     * app when installed), or ESPN's page if the user prefers it.
     */
    private fun gameTap(context: Context, search: String, link: String?, key: String, dashboard: String): PendingIntent {
        val code = REQUEST_BASE + (key.hashCode() and 0xFFFF)
        val open = Prefs.sportsOpen(context)
        if (open == Prefs.SportsOpen.DASHBOARD) return Style.gated(context, com.ataglanceplus.ui.DashboardActivity.intent(context, dashboard), code)
        if (open == Prefs.SportsOpen.ESPN && link != null) return Style.gated(context, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)), code)
        return Style.gated(context, googleSearch(context, search), code)
    }

    /** Google's results for [search], in the Google app when installed. */
    fun googleSearch(context: Context, search: String): Intent {
        val app = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, search).setPackage(GOOGLE_APP)
        return if (app.resolveActivity(context.packageManager) != null) app
        else Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(search)))
    }

    /** A stock's dashboard in this app, or its Google Finance page. */
    private fun stockTap(context: Context, quote: Quote): Intent =
        if (Prefs.financeOpensDashboard(context)) com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_STOCK_PREFIX + quote.symbol)
        else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(FinanceSource.googleFinanceUrl(quote)))

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

    /**
     * A card of chips that did not fit on the other cards: two rows of up to 4 slots, shared
     * between the rows, every chip that can be extended shown extended, and each row's chips
     * stretched to fill it.
     */
    fun chips(context: Context, aod: Boolean, specs: List<ChipSpec>, index: Int): SmartspaceTarget {
        val views = RemoteViews(context.packageName, R.layout.card_chips)
        if (aod) Style.aodBody(views)
        val (first, second) = Chips.overflowRows(context, specs)
        // The card's inside width: its own width less the body's padding (16 + 18dp).
        val row = WeatherCard.cardWidthDp(context) - 34f
        val only = (first + second).singleOrNull()
        if (only != null) {
            // One chip: the first row grows to both rows' height and the chip fills it.
            views.setViewLayoutHeight(R.id.complications, 2 * 34f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            views.setViewVisibility(R.id.complications_2, View.GONE)
            Chips.addOverflowSingle(context, views, only, row, 2 * 34f, aod, R.id.complications)
        } else {
            Chips.addOverflowRow(context, views, first, row, aod, R.id.complications)
            Chips.addOverflowRow(context, views, second, row, aod, R.id.complications_2)
        }
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
            // Outside regular hours: the latest price and its move from the close, named for its
            // session ("Overnight 6.56 · −0.32"), as eToro shows it.
            val extended = quote.extended
            val change = extended?.change ?: quote.change
            val up = change >= 0
            val colour = if (aod) Style.AOD_TEXT else if (up) UP else DOWN
            val page = RemoteViews(context.packageName, R.layout.include_finance_page)
            page.setTextViewText(R.id.finance_title, "${quote.symbol} · ${quote.name}")
            page.setTextViewText(
                R.id.finance_detail,
                if (extended != null) "${extended.session.label} ${price(extended.price, quote.currency)} · ${signed(extended.change)}"
                else "${price(quote.price, quote.currency)} · ${signed(quote.change)}",
            )
            page.setTextViewText(R.id.finance_change, signed(extended?.changePercent ?: quote.changePercent) + "%")
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
                val open = stockTap(context, quote)
                page.setOnClickPendingIntent(R.id.finance_page, Style.gated(context, open, REQUEST_BASE + ("stock:" + quote.symbol).hashCode().and(0xFFFF)))
            }
            views.addView(R.id.finance_flipper, page)
        }
        val first = ordered.first()
        val tap = Style.gated(
            context, stockTap(context, first),
            REQUEST_BASE + ("stock:" + first.symbol).hashCode().and(0xFFFF)
        )
        Style.holdBar(views, pinned = false, aod = aod)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(
            context, targetId(key), views, "${first.symbol} ${price(first.extended?.price ?: first.price, first.currency)}",
            signed(first.extended?.changePercent ?: first.changePercent) + "%", R.drawable.ic_trend_up, tap, 1.1f
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
        // The line ends at the price outside regular hours, when there is one.
        val points = quote.points + listOfNotNull(quote.extended?.price)
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

    /**
     * The toast shown in place of the first card, until it is swiped past, tapped or replaced. The
     * avatar pops in with a ring bursting out while [entering] (not yet seen on this surface), and
     * pulses a halo in the app's colour throughout.
     */
    fun island(context: Context, aod: Boolean, message: IncomingMessage, hideText: Boolean, entering: Boolean): SmartspaceTarget {
        val views = RemoteViews(context.packageName, R.layout.include_island_message_toast)
        views.setTextViewText(R.id.island_app, message.app)
        views.setTextColor(R.id.island_app, message.colour)
        views.setInt(R.id.island_avatar, "setColorFilter", message.colour)
        // The animated avatar draws the disc itself, so the plain one is only for the always-on display.
        val tint = android.content.res.ColorStateList.valueOf(message.colour)
        views.setColorStateList(R.id.island_fx_enter, "setIndeterminateTintList", tint)
        views.setColorStateList(R.id.island_fx_pulse, "setIndeterminateTintList", tint)
        views.setColorStateList(R.id.island_dot, "setIndeterminateTintList", tint)
        views.setViewVisibility(R.id.island_fx_enter, if (!aod && entering) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.island_fx_pulse, if (!aod && !entering) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.island_avatar, if (aod) View.VISIBLE else View.INVISIBLE)
        views.setTextViewText(R.id.island_sender, message.sender)
        views.setTextViewText(R.id.island_initial, message.sender.trimStart('#', '+').take(1).uppercase())
        // On the lock screen, follow Android's "sensitive content" setting: sender only.
        val text = if (hideText) "New message" else message.text
        views.setTextViewText(R.id.island_message, text)
        // Reply and the toast itself open the conversation, after unlocking.
        val open = gate(context, message.open, message.pkg, "message:${message.key}")
        if (aod) {
            Style.aodBody(views, R.id.island_app, R.id.island_sender, R.id.island_time, R.id.island_message, R.id.island_initial)
            views.setImageViewResource(R.id.island_avatar, R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.island_badge_icon, R.drawable.ic_chat_badge_aod)
            views.setViewVisibility(R.id.island_dot, View.GONE)
            views.setViewVisibility(R.id.island_reply, View.GONE)
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
    private const val CALENDAR_NEXT = 0xFF94A3B8.toInt()
    private const val NAV_GREEN = 0xFF6EE7B7.toInt()
    private const val UP = 0xFF34D399.toInt()
    private const val LIVE_RED = 0xFFF87171.toInt()

    /** Next and Back stacked on the race card: each button 32dp tall. */
    private const val RACE_PAIR_DP = 32f
    private const val DOWN = 0xFFF87171.toInt()
    // Sports spec 3.1 and 3.2.
    private const val TEAM_CIRCLE_SHADE = 0.6f
    private const val NO_TEAM_COLOUR = 0xFF2A3140.toInt()
    private const val RIM = 0x4DFFFFFF
    private const val TRAILING = 0xFFE2E2E6.toInt()
    private const val UPCOMING_TIME = 0xFFE2E2E6.toInt()
    private const val NEXT_TEXT = 0xFF7FCFFF.toInt()
    private const val FINAL_TEXT = 0xFFC4C6D0.toInt()
    private const val TAG_HOME_LIVE_TEXT = 0xFF410002.toInt()
    private const val TAG_AWAY_LIVE_TEXT = 0xFF1B1C1E.toInt()
    private const val TAG_HOME_NEXT_TEXT = 0xFF101E42.toInt()
    private const val TAG_AWAY_NEXT_TEXT = 0xFF3F0D0C.toInt()
    private const val MAX_STOCKS = 6
    private const val ALARM_WINDOW_MS = 12 * 60 * 60 * 1000L
}
