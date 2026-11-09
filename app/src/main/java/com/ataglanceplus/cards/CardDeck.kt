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
    /** When the message pop-up appeared and when it ends at the latest (epoch ms), for a host's countdown bar. */
    val messageSince: Long = 0L,
    val messageUntil: Long = 0L,
    /** True until the message card has been shown on this surface: it plays its entrance. */
    val messageEntering: Boolean,
    /** Wallet passes whose rules say to show them right now. */
    val wallet: List<com.ataglanceplus.wallet.WalletPass>,
    /** Rain or snow coming within the hour, while it is dry. */
    val rain: com.ataglanceplus.weather.RainSoon? = null,
    /** The journey to work or home, in a commute window. */
    val commute: com.ataglanceplus.live.CommuteSource.Trip? = null,
)

/** Everything shown on a surface, in order. */
object CardDeck {


    /**
     * Cards with something real to show, most time-sensitive first: a new message, the torch while it is on, navigation,
     * live updates, live games, a timer or alarm, rain or snow on the way, media, calendar events (one card each,
     * soonest first), Wallet passes, finance on a big move or in a time window, other sports cards (before, after,
     * next game), finance the rest of the time (it is always up), then the weather card, which is always there and
     * cannot be dismissed. Switched-off types, those in quiet hours and dismissed instances are left out; the
     * smarter order ([CardRules]) can then bring some forward.
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
        // Quiet hours hide the cards they name for now; the weather card always stays.
        val quiet = CardRules.quietNow(context)
        fun on(type: CardType) = Prefs.cardEnabled(context, type) && type.id !in quiet

        val torch = data.torch?.takeIf { on(CardType.TORCH) && "torch" !in dismissed }
        val nav = data.nav?.takeIf { on(CardType.NAV) && "nav:${it.key}" !in dismissed }
        val live = if (on(CardType.LIVE)) data.live.filter { "live:${it.key}" !in dismissed } else emptyList()
        val timer = data.timer?.takeIf { on(CardType.TIMER) && "timer:${it.key}" !in dismissed }
        val alarm = if (timer == null && on(CardType.TIMER)) {
            LiveCards.upcomingAlarm(context)?.takeIf { "alarm:${it.triggerTime}" !in dismissed }
        } else null
        val rain = data.rain?.takeIf { on(CardType.RAIN) && it.key !in dismissed }
        // The commute card stays away while navigating (the journey is under way).
        val commute = data.commute?.takeIf { on(CardType.COMMUTE) && nav == null && it.key !in dismissed }
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

        // The cards on show in the usual order (most time-sensitive first). [chips] names the
        // cards that hold chips; each built later with the chips it was given.
        class Entry(val name: String, val slot: CardRules.Slot, val chips: Boolean, val build: (List<ChipSpec>?) -> SmartspaceTarget)
        val now = System.currentTimeMillis()
        fun aroundKickoff(s: com.ataglanceplus.live.SportsShowing): Boolean = s.phase == com.ataglanceplus.live.SportsPhase.LIVE ||
            (s.phase == com.ataglanceplus.live.SportsPhase.BEFORE && when (s) {
                is com.ataglanceplus.live.SportsShowing.Team -> s.game.start - now < 60 * 60_000L
                is com.ataglanceplus.live.SportsShowing.Race -> s.session.start - now < 60 * 60_000L
            })
        val usual = buildList {
            if (data.message != null && data.messageRemainingMs > 0 && on(CardType.MESSAGES)) {
                val hideText = surface == AtAGlanceService.SURFACE_LOCKSCREEN && !LiveCards.lockScreenShowsPrivate(context)
                add(Entry("message", CardRules.Slot(CardType.MESSAGES.id), false) {
                    LiveCards.island(context, aod, data.message, hideText, data.messageEntering, data.messageSince, data.messageUntil)
                })
            }
            // The torch first: it is on now, and the card is how to turn it off.
            torch?.let { add(Entry("torch", CardRules.Slot(CardType.TORCH.id), false) { _ -> LiveCards.torch(context, aod, it) }) }
            nav?.let { add(Entry("navigation", CardRules.Slot(CardType.NAV.id), true) { c -> LiveCards.nav(context, aod, it, c) }) }
            live.forEach { l -> add(Entry("live update", CardRules.Slot(CardType.LIVE.id), false) { _ -> LiveCards.live(context, aod, l) }) }
            liveSports.forEach { sp -> add(Entry("sports", CardRules.Slot(CardType.SPORTS.id, aroundKickoff(sp)), false) { _ -> sportsCard(context, aod, sp) }) }
            timer?.let { add(Entry("timer", CardRules.Slot(CardType.TIMER.id), true) { c -> LiveCards.timer(context, aod, it, c) }) }
            alarm?.let { add(Entry("alarm", CardRules.Slot(CardType.TIMER.id), true) { c -> LiveCards.alarm(context, aod, it, c) }) }
            commute?.let { add(Entry("commute", CardRules.Slot(CardType.COMMUTE.id), true) { c -> LiveCards.commute(context, aod, it, c) }) }
            rain?.let { add(Entry("rain", CardRules.Slot(CardType.RAIN.id), false) { _ -> LiveCards.rain(context, aod, it) }) }
            media?.let { add(Entry("media", CardRules.Slot(CardType.MEDIA.id), false) { _ -> LiveCards.media(context, aod, it) }) }
            // One card per event, soonest first; only the first holds chips.
            calendars.forEachIndexed { i, event ->
                add(Entry("calendar", CardRules.Slot(CardType.CALENDAR.id), i == 0) { c -> LiveCards.calendar(context, aod, event, if (i == 0) c else emptyList()) })
            }
            wallet.forEach { pass -> add(Entry("wallet", CardRules.Slot(CardType.WALLET.id), false) { _ -> LiveCards.wallet(context, aod, pass) }) }
            // Finance is always up; a big move or a time window brings it ahead of upcoming games.
            finance?.takeIf { it.forward }?.let { f -> add(Entry("finance", CardRules.Slot(CardType.FINANCE.id), true) { c -> LiveCards.finance(context, aod, data.quotes, f, c) }) }
            otherSports.forEach { sp -> add(Entry("sports", CardRules.Slot(CardType.SPORTS.id, aroundKickoff(sp)), false) { _ -> sportsCard(context, aod, sp) }) }
            finance?.takeIf { !it.forward }?.let { f -> add(Entry("finance", CardRules.Slot(CardType.FINANCE.id), true) { c -> LiveCards.finance(context, aod, data.quotes, f, c) }) }
        }
        // The smarter order (your windows, then the built-in rules); equal ranks keep the usual order.
        val ranks = CardRules.ranks(context, usual.map { it.slot })
        val ordered = usual.indices.sortedBy { ranks[it] }.map { usual[it] }

        // Chips go to the cards in the order they appear: every card in front of the weather card
        // that can hold chips takes its share first; the weather card then fits what it can of the
        // rest, and the extra cards take what is left. A card the launcher has no room for takes none.
        val limited = !(maxCards <= 0 || Prefs.showAllCards(context))
        val given = HashMap<Entry, List<ChipSpec>?>()
        (if (limited) ordered.take((maxCards - 1).coerceAtLeast(0)) else ordered).forEach { e ->
            if (e.chips) given[e] = takeForCard()
        }
        // The weather card fits what it can of the rest, not only the first in line, so it picks its own.
        val weatherChips = pool?.let { p -> WeatherCard.chipsTaken(context, p).also { p.removeAll(it) } }
        // The rest on extra chips cards, two rows each.
        val extraChips = buildList {
            while (!pool.isNullOrEmpty()) {
                // At least one, so a chip whose value just went away cannot stall the loop.
                add(take(LiveCards.extraTaken(context, pool).coerceAtLeast(1)).orEmpty())
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
        val contextual = buildList { ordered.forEach { e -> card(e.name) { e.build(given[e]) } } }
        val weatherCard = WeatherCard.build(context, surface, aod, data.weather, data.weatherStatus, weatherChips)
        // The extra card: the chips that did not fit, or, with none, today and the next days.
        val extras = buildList {
            extraChips.forEachIndexed { i, chips -> card("extra chips") { LiveCards.chips(context, aod, chips, i, data.weather) } }
            if (extraChips.isEmpty() && data.weather != null) card("forecast") { LiveCards.chips(context, aod, emptyList(), 0, data.weather) }
        }
        // No count asked for, or the user chose to show them all: send everything.
        if (maxCards <= 0 || Prefs.showAllCards(context)) return contextual + weatherCard + extras
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
        data.rain?.let { add(it.key) }
        data.commute?.let { add(it.key) }
        data.calendars.forEach { add(it.instanceKey) }
        addAll(FinanceRules.instanceKeys(context, data.quotes))
        data.sports.forEach { add(it.instanceKey) }
        data.wallet.forEach { add("wallet:${it.id}") }
    }
}
