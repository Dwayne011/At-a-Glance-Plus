package com.ataglanceplus.cards

import android.app.smartspace.SmartspaceTarget
import android.content.Context
import com.ataglanceplus.Prefs
import com.ataglanceplus.live.CalendarNow
import com.ataglanceplus.live.FinanceRules
import com.ataglanceplus.live.IncomingMessage
import com.ataglanceplus.live.LiveNow
import com.ataglanceplus.live.MediaNow
import com.ataglanceplus.live.NavNow
import com.ataglanceplus.live.Quote
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
    val quotes: List<Quote>,
    val message: IncomingMessage?,
    val messageRemainingMs: Long,
)

/** Everything shown on a surface, in order. */
object CardDeck {

    /** Live updates shown at once; more would push the other cards off. */
    private const val MAX_LIVE = 2
    /** Chips on one extra chips card: two rows of 4. */
    private const val PER_CHIPS_CARD = 2 * Chips.MAX_BOTTOM

    /**
     * Cards with something real to show, most time-sensitive first: a new message, navigation,
     * live updates, a timer or alarm, media, the next calendar event, finance, then the weather
     * card, which is always there and cannot be dismissed. Switched-off types and dismissed
     * instances are left out.
     *
     * The user's chips (those with something to report, in their order) fill the weather card
     * first, then the other cards on show that have room for chips; any left over go on extra
     * chips cards at the end. The list is cut to the number of cards the surface asked for, always
     * keeping the weather card.
     */
    fun build(context: Context, surface: String, maxCards: Int, aod: Boolean, data: CardData): List<SmartspaceTarget> {
        if (surface != AtAGlanceService.SURFACE_HOME && surface != AtAGlanceService.SURFACE_LOCKSCREEN) {
            // Media, glanceable hub, screensaver and ambient surfaces: nothing yet.
            return emptyList()
        }
        val dismissed = Prefs.dismissed(context)
        fun on(type: CardType) = Prefs.cardEnabled(context, type)

        val nav = data.nav?.takeIf { on(CardType.NAV) && "nav:${it.key}" !in dismissed }
        val live = if (on(CardType.LIVE)) data.live.filter { "live:${it.key}" !in dismissed }.take(MAX_LIVE) else emptyList()
        val timer = data.timer?.takeIf { on(CardType.TIMER) && "timer:${it.key}" !in dismissed }
        val alarm = if (timer == null && on(CardType.TIMER)) {
            LiveCards.upcomingAlarm(context)?.takeIf { "alarm:${it.triggerTime}" !in dismissed }
        } else null
        val media = data.media?.takeIf { on(CardType.MEDIA) && "media:${it.pkg}" !in dismissed }
        val calendar = data.calendar?.takeIf { on(CardType.CALENDAR) && it.instanceKey !in dismissed }
        val finance = if (on(CardType.FINANCE)) FinanceRules.showing(context, data.quotes)?.takeIf { it.instanceKey !in dismissed } else null

        // Share out the user's chips. Before the user picks any, each card keeps the spec's own.
        val layout = Prefs.complicationLayout(context)
        val pool = Prefs.chips(context)?.takeIf { layout != ComplicationLayout.NONE }
            ?.let { chosen -> Chips.withValues(chosen).map { it.first }.toMutableList() }
        fun take(room: Int): List<ChipSpec>? = pool?.let { p -> List(minOf(room, p.size)) { p.removeAt(0) } }
        val bottom = layout == ComplicationLayout.BOTTOM
        val weatherChips = take(if (bottom) Chips.MAX_BOTTOM else Chips.MAX_RIGHT)
        val navChips = if (nav != null) take(Chips.MAX_RIGHT) else null
        val timerChips = if ((timer != null || alarm != null) && layout == ComplicationLayout.RIGHT) take(Chips.MAX_RIGHT) else null
        val calendarChips = if (calendar != null) take(if (bottom) Chips.MAX_BOTTOM else Chips.MAX_RIGHT) else null
        val extraChips = pool.orEmpty().chunked(PER_CHIPS_CARD)

        val contextual = buildList {
            if (data.message != null && data.messageRemainingMs > 0 && on(CardType.MESSAGES)) {
                val hideText = surface == AtAGlanceService.SURFACE_LOCKSCREEN && !LiveCards.lockScreenShowsPrivate(context)
                add(LiveCards.island(context, aod, data.message, data.messageRemainingMs, hideText))
            }
            nav?.let { add(LiveCards.nav(context, aod, it, navChips)) }
            live.forEach { add(LiveCards.live(context, aod, it)) }
            timer?.let { add(LiveCards.timer(context, aod, it, timerChips)) }
            alarm?.let { add(LiveCards.alarm(context, aod, it, timerChips)) }
            media?.let { add(LiveCards.media(context, aod, it)) }
            calendar?.let { add(LiveCards.calendar(context, aod, it, calendarChips)) }
            finance?.let { add(LiveCards.finance(context, aod, data.quotes, it)) }
        }
        val weatherCard = WeatherCard.build(context, surface, aod, data.weather, data.weatherStatus, weatherChips)
        val extras = extraChips.mapIndexed { i, chips -> LiveCards.chips(context, aod, chips, i) }
        if (maxCards <= 0) return contextual + weatherCard + extras
        // Out of room: the weather card always stays; the least urgent cards go first.
        return (contextual.take(maxCards - 1) + weatherCard + extras).take(maxCards)
    }

    /** Instance keys of everything that exists now, dismissed or not, so stale dismissals can be forgotten. */
    fun activeInstances(context: Context, data: CardData): Set<String> = buildSet {
        data.nav?.let { add("nav:${it.key}") }
        data.live.forEach { add("live:${it.key}") }
        data.timer?.let { add("timer:${it.key}") }
        LiveCards.upcomingAlarm(context)?.let { add("alarm:${it.triggerTime}") }
        data.media?.let { add("media:${it.pkg}") }
        data.calendar?.let { add(it.instanceKey) }
        FinanceRules.showing(context, data.quotes)?.let { add(it.instanceKey) }
    }
}
