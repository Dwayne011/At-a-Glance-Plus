package com.ataglanceplus.live

import android.content.Context
import com.ataglanceplus.Prefs

/** Where a game or session stands for the card. */
enum class SportsPhase { LIVE, AFTER, BEFORE, NEXT }

/** One sports card: a team game or a race session, and why it is showing. */
sealed class SportsShowing(val phase: SportsPhase) {
    abstract val instanceKey: String

    class Team(val game: Game, phase: SportsPhase) : SportsShowing(phase) {
        override val instanceKey get() = "sports:${game.eventId}:$phase"
    }

    class Race(val session: RaceSession, phase: SportsPhase) : SportsShowing(phase) {
        override val instanceKey get() = "sports:${session.eventId}:${session.session}:$phase"
    }
}

/**
 * Which game each followed team or series shows, by the user's choices: during the game, for a
 * while before and after it, and otherwise (if switched on) the next fixture. A game between two
 * followed teams shows once.
 */
object SportsRules {

    private const val HOUR = 3_600_000L

    fun showing(context: Context, games: Map<String, List<Game>>, races: Map<String, List<RaceSession>>): List<SportsShowing> {
        val now = System.currentTimeMillis()
        val before = Prefs.sportsBeforeHours(context) * HOUR
        val after = Prefs.sportsAfterHours(context) * HOUR
        val next = Prefs.sportsNextAlways(context)
        val result = mutableListOf<SportsShowing>()
        Prefs.sportsFollowed(context).forEach { f ->
            if (f.isRacing) {
                val sessions = races[f.key].orEmpty()
                pick(sessions, now, before, after, next, { it.state }, { it.start }, { endOf(it) })
                    ?.let { (s, phase) -> result += SportsShowing.Race(s, phase) }
            } else {
                val list = games[f.key].orEmpty()
                pick(list, now, before, after, next, { it.state }, { it.start }, { endOf(it) })
                    ?.let { (g, phase) -> result += SportsShowing.Team(g, phase) }
            }
        }
        return result.distinctBy { it.instanceKey.substringBeforeLast(':') }
    }

    /** Live first, then a recent result, then the next one if it is close, then (optionally) the next one anyway. */
    private fun <T> pick(
        items: List<T>, now: Long, before: Long, after: Long, nextAlways: Boolean,
        state: (T) -> String, start: (T) -> Long, end: (T) -> Long,
    ): Pair<T, SportsPhase>? {
        items.firstOrNull { state(it) == "in" }?.let { return it to SportsPhase.LIVE }
        items.filter { state(it) == "post" && now - end(it) in 0..after }.maxByOrNull { start(it) }
            ?.let { return it to SportsPhase.AFTER }
        val upcoming = items.filter { state(it) == "pre" && start(it) > now - 6 * HOUR }.minByOrNull { start(it) } ?: return null
        return when {
            start(upcoming) - now <= before -> upcoming to SportsPhase.BEFORE
            nextAlways -> upcoming to SportsPhase.NEXT
            else -> null
        }
    }

    /** When a game ended: when it was first seen finished, else its start plus a typical length. */
    private fun endOf(game: Game): Long = SportsSource.finishedAt(game.eventId) ?: (game.start + when (game.sport) {
        "soccer" -> 2 * HOUR
        "football" -> 3 * HOUR + HOUR / 2
        "baseball" -> 3 * HOUR
        else -> 2 * HOUR + HOUR / 2
    })

    private fun endOf(session: RaceSession): Long = SportsSource.finishedAt(session.eventId + session.session)
        ?: (session.start + if (session.session.equals("Race", ignoreCase = true)) 2 * HOUR else HOUR)
}
