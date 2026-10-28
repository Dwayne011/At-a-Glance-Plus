package com.ataglanceplus.cards

import android.app.smartspace.SmartspaceTarget
import android.content.Context
import com.ataglanceplus.Prefs
import com.ataglanceplus.live.CalendarNow
import com.ataglanceplus.live.IncomingMessage
import com.ataglanceplus.live.LiveNow
import com.ataglanceplus.live.MediaNow
import com.ataglanceplus.live.NavNow
import com.ataglanceplus.live.TimerNow
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.weather.Weather

/** Everything the cards can show right now. */
data class CardData(
    val weather: Weather?,
    val weatherStatus: String,
    val nav: NavNow?,
    val live: List<LiveNow>,
    val timer: TimerNow?,
    val media: MediaNow?,
    val calendar: CalendarNow?,
    val message: IncomingMessage?,
    val messageRemainingMs: Long,
)

/** Everything shown on a surface, in order. */
object CardDeck {

    /** Live updates shown at once; more would push the other cards off. */
    private const val MAX_LIVE = 2

    /**
     * Cards with something real to show, most time-sensitive first: a new message, navigation,
     * live updates, a timer or alarm, media, the next calendar event, then the weather card,
     * which is always there and cannot be dismissed. Switched-off types and dismissed instances
     * are left out. The list is cut to the number of cards the surface asked for.
     */
    fun build(context: Context, surface: String, maxCards: Int, aod: Boolean, data: CardData): List<SmartspaceTarget> {
        if (surface != AtAGlanceService.SURFACE_HOME && surface != AtAGlanceService.SURFACE_LOCKSCREEN) {
            // Media, glanceable hub, screensaver and ambient surfaces: nothing yet.
            return emptyList()
        }
        val dismissed = Prefs.dismissed(context)
        fun on(type: CardType) = Prefs.cardEnabled(context, type)
        val cards = buildList {
            if (data.message != null && data.messageRemainingMs > 0 && on(CardType.MESSAGES)) {
                val hideText = surface == AtAGlanceService.SURFACE_LOCKSCREEN && !LiveCards.lockScreenShowsPrivate(context)
                add(LiveCards.island(context, aod, data.message, data.messageRemainingMs, hideText))
            }
            if (on(CardType.NAV)) data.nav?.takeIf { "nav:${it.key}" !in dismissed }?.let { add(LiveCards.nav(context, aod, it)) }
            if (on(CardType.LIVE)) {
                data.live.filter { "live:${it.key}" !in dismissed }.take(MAX_LIVE).forEach { add(LiveCards.live(context, aod, it)) }
            }
            if (on(CardType.TIMER)) {
                val timer = data.timer?.takeIf { "timer:${it.key}" !in dismissed }
                if (timer != null) {
                    add(LiveCards.timer(context, aod, timer))
                } else {
                    LiveCards.upcomingAlarm(context)?.takeIf { "alarm:${it.triggerTime}" !in dismissed }?.let { add(LiveCards.alarm(context, aod, it)) }
                }
            }
            if (on(CardType.MEDIA)) data.media?.takeIf { "media:${it.pkg}" !in dismissed }?.let { add(LiveCards.media(context, aod, it)) }
            if (on(CardType.CALENDAR)) data.calendar?.takeIf { it.instanceKey !in dismissed }?.let { add(LiveCards.calendar(context, aod, it)) }
            add(WeatherCard.build(context, surface, aod, data.weather, data.weatherStatus))
        }
        return if (maxCards > 0) cards.take(maxCards) else cards
    }

    /** Instance keys of everything that exists now, dismissed or not, so stale dismissals can be forgotten. */
    fun activeInstances(context: Context, data: CardData): Set<String> = buildSet {
        data.nav?.let { add("nav:${it.key}") }
        data.live.forEach { add("live:${it.key}") }
        data.timer?.let { add("timer:${it.key}") }
        LiveCards.upcomingAlarm(context)?.let { add("alarm:${it.triggerTime}") }
        data.media?.let { add("media:${it.pkg}") }
        data.calendar?.let { add(it.instanceKey) }
    }
}
