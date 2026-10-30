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

    /**
     * [session] is the one on the card: the one the rules chose, or another of the weekend's
     * [sessions] the user stepped to with Next. The key stays the chosen one's, so a dismissal
     * holds while stepping.
     */
    class Race(
        val session: RaceSession,
        phase: SportsPhase,
        val sessions: List<RaceSession>,
        /** How many sessions on from the one the rules chose (0 for that one). */
        val step: Int,
        /** Whether this is the weekend's last session, counting on from the chosen one. */
        val lastStep: Boolean,
        private val chosen: RaceSession,
        private val chosenPhase: SportsPhase,
    ) : SportsShowing(phase) {
        override val instanceKey get() = "sports:${chosen.eventId}:${chosen.session}:$chosenPhase"
    }
}

/**
 * Which game each followed team or series shows, by the user's choices: during the game, for a
 * while before and after it, and otherwise (if switched on) the next fixture. A game between two
 * followed teams shows once.
 */
object SportsRules {

    private const val HOUR = 3_600_000L
    const val STEP_LASTS = 60_000L

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
                    ?.let { (s, phase) -> result += stepped(context, sessions, s, phase, now, before) }
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

    /**
     * The session the user stepped to with Next, counted from the chosen one and wrapping back to
     * it. A step lasts a minute, so the card returns to what is on now by itself.
     */
    private fun stepped(
        context: Context, sessions: List<RaceSession>, chosen: RaceSession, phase: SportsPhase, now: Long, before: Long,
    ): SportsShowing.Race {
        val base = sessions.indexOf(chosen)
        val (eventId, steps, at) = Prefs.sportsRaceStep(context)
        val step = if (eventId == chosen.eventId && now - at < STEP_LASTS) steps % sessions.size else 0
        val session = sessions[(base + step) % sessions.size]
        val shownPhase = when {
            step == 0 -> phase
            session.state == "in" -> SportsPhase.LIVE
            session.state == "post" -> SportsPhase.AFTER
            session.start - now in 0..before -> SportsPhase.BEFORE
            else -> SportsPhase.NEXT
        }
        return SportsShowing.Race(session, shownPhase, sessions, step, step == sessions.size - 1, chosen, phase)
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
