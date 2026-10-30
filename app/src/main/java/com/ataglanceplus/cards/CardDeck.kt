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
    /** Every calendar event with a card now, soonest first. */
    val calendars: List<CalendarNow>,
    val quotes: List<Quote>,
    val sports: List<com.ataglanceplus.live.SportsShowing>,
    val torch: com.ataglanceplus.live.TorchNow?,
    val message: IncomingMessage?,
    val messageRemainingMs: Long,
    /** True until the message card has been shown on this surface: it plays its entrance. */
    val messageEntering: Boolean,
    /** Wallet passes whose rules say to show them right now. */
    val wallet: List<com.ataglanceplus.wallet.WalletPass>,
)

/** Everything shown on a surface, in order. */
object CardDeck {


    /**
     * Cards with something real to show, most time-sensitive first: a new message, the torch while it is on, navigation,
     * live updates, live games, a timer or alarm, media, calendar events (one card each, soonest first), Wallet passes,
     * finance on a big move or in a time window, other sports cards (before, after, next game),
     * finance the rest of the time (it is always up), then the weather
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
        val live = if (on(CardType.LIVE)) data.live.filter { "live:${it.key}" !in dismissed } else emptyList()
        val timer = data.timer?.takeIf { on(CardType.TIMER) && "timer:${it.key}" !in dismissed }
        val alarm = if (timer == null && on(CardType.TIMER)) {
            LiveCards.upcomingAlarm(context)?.takeIf { "alarm:${it.triggerTime}" !in dismissed }
        } else null
        val media = data.media?.takeIf { on(CardType.MEDIA) && "media:${it.pkg}" !in dismissed }
        val calendars = if (on(CardType.CALENDAR)) data.calendars.filter { it.instanceKey !in dismissed } else emptyList()
        val sports = if (on(CardType.SPORTS)) data.sports.filter { it.instanceKey !in dismissed } else emptyList()
        val (liveSports, otherSports) = sports.partition { it.phase == com.ataglanceplus.live.SportsPhase.LIVE }
        val finance = if (on(CardType.FINANCE)) FinanceRules.showing(context, data.quotes) else null
        val wallet = if (on(CardType.WALLET)) data.wallet.filter { "wallet:${it.id}" !in dismissed } else emptyList()

        // Share out the user's chips. Before the user picks any, each card keeps the spec's own.
        val pool = Prefs.chips(context)
            ?.let { chosen -> Chips.withValues(chosen).map { it.first }.toMutableList() }
        fun take(count: Int): List<ChipSpec>? = pool?.let { p -> List(minOf(count, p.size)) { p.removeAt(0) } }
        // A chip taking 2 slots counts once, so each card takes as many as it really shows.
        fun takeForCard(): List<ChipSpec>? = take(Chips.taken(context, pool.orEmpty()))
        // The weather card fits what it can, not only the first in line, so it picks its own.
        val weatherChips = pool?.let { p -> WeatherCard.chipsTaken(context, p).also { p.removeAll(it) } }
        val navChips = if (nav != null) takeForCard() else null
        val timerChips = if (timer != null || alarm != null) takeForCard() else null
        // Chips go on the first calendar card; the others stack without any.
        val calendarChips = if (calendars.isNotEmpty()) takeForCard() else null
        // The rest on extra chips cards, two rows each.
        val extraChips = buildList {
            while (!pool.isNullOrEmpty()) {
                // At least one, so a chip whose value just went away cannot stall the loop.
                add(take(Chips.overflowTaken(context, pool).coerceAtLeast(1)).orEmpty())
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
                card("message") { LiveCards.island(context, aod, data.message, hideText, data.messageEntering) }
            }
            // The torch first: it is on now, and the card is how to turn it off.
            torch?.let { card("torch") { LiveCards.torch(context, aod, it) } }
            nav?.let { card("navigation") { LiveCards.nav(context, aod, it, navChips) } }
            live.forEach { card("live update") { LiveCards.live(context, aod, it) } }
            liveSports.forEach { card("sports") { sportsCard(context, aod, it) } }
            timer?.let { card("timer") { LiveCards.timer(context, aod, it, timerChips) } }
            alarm?.let { card("alarm") { LiveCards.alarm(context, aod, it, timerChips) } }
            media?.let { card("media") { LiveCards.media(context, aod, it) } }
            calendars.forEachIndexed { i, event -> card("calendar") { LiveCards.calendar(context, aod, event, if (i == 0) calendarChips else emptyList()) } }
            wallet.forEach { pass -> card("wallet") { LiveCards.wallet(context, aod, pass) } }
            // Finance is always up; a big move or a time window brings it ahead of upcoming games.
            finance?.takeIf { it.forward }?.let { card("finance") { LiveCards.finance(context, aod, data.quotes, it) } }
            otherSports.forEach { card("sports") { sportsCard(context, aod, it) } }
            finance?.takeIf { !it.forward }?.let { card("finance") { LiveCards.finance(context, aod, data.quotes, it) } }
        }
        val weatherCard = WeatherCard.build(context, surface, aod, data.weather, data.weatherStatus, weatherChips)
        val extras = buildList { extraChips.forEachIndexed { i, chips -> card("extra chips") { LiveCards.chips(context, aod, chips, i) } } }
        // No count asked for, or the user chose to show them all: send everything.
        if (maxCards <= 0 || Prefs.showAllCards(context)) return contextual + weatherCard + extras
        // Out of room: the weather card always stays; the least urgent cards go first.
        return (contextual.take(maxCards - 1) + weatherCard + extras).take(maxCards)
    }

    private fun sportsCard(context: Context, aod: Boolean, showing: com.ataglanceplus.live.SportsShowing): SmartspaceTarget = when (showing) {
        is com.ataglanceplus.live.SportsShowing.Team -> LiveCards.sports(context, aod, showing)
        is com.ataglanceplus.live.SportsShowing.Race -> {
            // While the session is on and F1's live timing is coming in: the live card.
            val live = com.ataglanceplus.live.LiveTiming.state.value
            if (showing.phase == com.ataglanceplus.live.SportsPhase.LIVE && live != null && live.cars.isNotEmpty()) LiveCards.raceLive(context, aod, showing, live)
            else LiveCards.race(context, aod, showing)
        }
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
        data.calendars.forEach { add(it.instanceKey) }
        addAll(FinanceRules.instanceKeys(context, data.quotes))
        data.sports.forEach { add(it.instanceKey) }
        data.wallet.forEach { add("wallet:${it.id}") }
    }
}
