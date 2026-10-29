package com.ataglanceplus.cards

import android.app.smartspace.SmartspaceTarget
import android.content.Context
import com.ataglanceplus.CrashLog
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
    val sports: List<com.ataglanceplus.live.SportsShowing>,
    val torch: com.ataglanceplus.live.TorchNow?,
    val message: IncomingMessage?,
    val messageRemainingMs: Long,
)

/** Everything shown on a surface, in order. */
object CardDeck {

    /** Live updates shown at once; more would push the other cards off. */
    private const val MAX_LIVE = 2

    /**
     * Cards with something real to show, most time-sensitive first: a new message, the torch while it is on, navigation,
     * live updates, live games, a timer or alarm, media, the next calendar event, other sports
     * cards (before, after, next game), finance, then the weather
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

        val torch = data.torch?.takeIf { on(CardType.TORCH) && "torch" !in dismissed }
        val nav = data.nav?.takeIf { on(CardType.NAV) && "nav:${it.key}" !in dismissed }
        val live = if (on(CardType.LIVE)) data.live.filter { "live:${it.key}" !in dismissed }.take(MAX_LIVE) else emptyList()
        val timer = data.timer?.takeIf { on(CardType.TIMER) && "timer:${it.key}" !in dismissed }
        val alarm = if (timer == null && on(CardType.TIMER)) {
            LiveCards.upcomingAlarm(context)?.takeIf { "alarm:${it.triggerTime}" !in dismissed }
        } else null
        val media = data.media?.takeIf { on(CardType.MEDIA) && "media:${it.pkg}" !in dismissed }
        val calendar = data.calendar?.takeIf { on(CardType.CALENDAR) && it.instanceKey !in dismissed }
        val sports = if (on(CardType.SPORTS)) data.sports.filter { it.instanceKey !in dismissed } else emptyList()
        val (liveSports, otherSports) = sports.partition { it.phase == com.ataglanceplus.live.SportsPhase.LIVE }
        val finance = if (on(CardType.FINANCE)) FinanceRules.showing(context, data.quotes)?.takeIf { it.instanceKey !in dismissed } else null

        // Share out the user's chips. Before the user picks any, each card keeps the spec's own.
        val layout = Prefs.complicationLayout(context)
        val pool = Prefs.chips(context)?.takeIf { layout != ComplicationLayout.NONE }
            ?.let { chosen -> Chips.withValues(chosen).map { it.first }.toMutableList() }
        fun take(count: Int): List<ChipSpec>? = pool?.let { p -> List(minOf(count, p.size)) { p.removeAt(0) } }
        // A chip taking 2 slots counts once, so each card takes as many as it really shows.
        fun takeFor(chipLayout: ComplicationLayout): List<ChipSpec>? = take(Chips.taken(context, pool.orEmpty(), chipLayout))
        val bottom = layout == ComplicationLayout.BOTTOM
        val weatherChips = take(WeatherCard.room(context, Prefs.weatherLayout(context), pool.orEmpty()))
        val navChips = if (nav != null) takeFor(ComplicationLayout.RIGHT) else null
        val timerChips = if ((timer != null || alarm != null) && layout == ComplicationLayout.RIGHT) takeFor(ComplicationLayout.RIGHT) else null
        val calendarChips = if (calendar != null) takeFor(if (bottom) ComplicationLayout.BOTTOM else ComplicationLayout.RIGHT) else null
        // The rest on extra chips cards, two rows each.
        val extraChips = buildList {
            while (!pool.isNullOrEmpty()) {
                val first = Chips.taken(context, pool, ComplicationLayout.BOTTOM)
                val second = Chips.taken(context, pool.drop(first), ComplicationLayout.BOTTOM)
                // At least one, so a chip whose value just went away cannot stall the loop.
                add(take((first + second).coerceAtLeast(1)).orEmpty())
            }
        }

        // Each card is built on its own: one that fails is left out (and its error kept for the
        // Problems section) rather than taking the others with it.
        fun MutableList<SmartspaceTarget>.card(name: String, build: () -> SmartspaceTarget) {
            try {
                add(build())
            } catch (e: Exception) {
                CrashLog.record(context, "Building the $name card for $surface", e)
            }
        }
        val contextual = buildList {
            if (data.message != null && data.messageRemainingMs > 0 && on(CardType.MESSAGES)) {
                val hideText = surface == AtAGlanceService.SURFACE_LOCKSCREEN && !LiveCards.lockScreenShowsPrivate(context)
                card("message") { LiveCards.island(context, aod, data.message, data.messageRemainingMs, hideText) }
            }
            // The torch first: it is on now, and the card is how to turn it off.
            torch?.let { card("torch") { LiveCards.torch(context, aod, it) } }
            nav?.let { card("navigation") { LiveCards.nav(context, aod, it, navChips) } }
            live.forEach { card("live update") { LiveCards.live(context, aod, it) } }
            liveSports.forEach { card("sports") { sportsCard(context, aod, it) } }
            timer?.let { card("timer") { LiveCards.timer(context, aod, it, timerChips) } }
            alarm?.let { card("alarm") { LiveCards.alarm(context, aod, it, timerChips) } }
            media?.let { card("media") { LiveCards.media(context, aod, it) } }
            calendar?.let { card("calendar") { LiveCards.calendar(context, aod, it, calendarChips) } }
            otherSports.forEach { card("sports") { sportsCard(context, aod, it) } }
            finance?.let { card("finance") { LiveCards.finance(context, aod, data.quotes, it) } }
        }
        val weatherCard = WeatherCard.build(context, surface, aod, data.weather, data.weatherStatus, weatherChips)
        val extras = buildList { extraChips.forEachIndexed { i, chips -> card("extra chips") { LiveCards.chips(context, aod, chips, i) } } }
        if (maxCards <= 0) return contextual + weatherCard + extras
        // Out of room: the weather card always stays; the least urgent cards go first.
        return (contextual.take(maxCards - 1) + weatherCard + extras).take(maxCards)
    }

    private fun sportsCard(context: Context, aod: Boolean, showing: com.ataglanceplus.live.SportsShowing): SmartspaceTarget = when (showing) {
        is com.ataglanceplus.live.SportsShowing.Team -> LiveCards.sports(context, aod, showing)
        is com.ataglanceplus.live.SportsShowing.Race -> LiveCards.race(context, aod, showing)
    }

    /** A card's kind from its target id, in words: "timer", "weather", "extra chips". */
    fun label(targetId: String): String = when {
        targetId == WeatherCard.TARGET_ID -> "weather"
        targetId == LiveCards.ISLAND_TARGET_ID -> "message"
        targetId.startsWith("aagp_chips:") -> "extra chips"
        else -> LiveCards.instanceKey(targetId)?.substringBefore(':') ?: targetId
    }

    /** Instance keys of everything that exists now, dismissed or not, so stale dismissals can be forgotten. */
    fun activeInstances(context: Context, data: CardData): Set<String> = buildSet {
        data.torch?.let { add("torch") }
        data.nav?.let { add("nav:${it.key}") }
        data.live.forEach { add("live:${it.key}") }
        data.timer?.let { add("timer:${it.key}") }
        LiveCards.upcomingAlarm(context)?.let { add("alarm:${it.triggerTime}") }
        data.media?.let { add("media:${it.pkg}") }
        data.calendar?.let { add(it.instanceKey) }
        FinanceRules.showing(context, data.quotes)?.let { add(it.instanceKey) }
        data.sports.forEach { add(it.instanceKey) }
    }
}
