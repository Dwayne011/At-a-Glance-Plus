package com.ataglanceplus.live

import android.content.Context
import com.ataglanceplus.Prefs
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The finance card is always up while it is switched on and has prices, updating as they
 * refresh. Two optional rules bring it to the front (ahead of upcoming sports cards):
 * - big moves: a stock has moved at least the threshold today (or outside regular hours since the
 *   close). Once on, it stays until the biggest move falls below three quarters of the
 *   threshold, so a stock hovering at the threshold does not make it jump back and forth;
 * - time windows: during any of the user's windows.
 *
 * Dismissing the card hides it until the next price refresh (the user's "Refresh prices every"),
 * when it comes back with the new prices: every instance key ends in the time of the last fetch.
 */
object FinanceRules {

    /** A big-moves showing ends when the biggest move falls below this share of the threshold. */
    private const val MOVES_KEEP = 0.75

    /** [forward]: a rule matched, so the card goes ahead of upcoming sports cards. */
    data class Showing(val instanceKey: String, val byMoves: Boolean, val forward: Boolean, val reason: String)

    /** The day the big-moves showing is on (epoch day), or null; kept while the service runs. */
    @Volatile private var movesOnDay: Long? = null

    /** What shows now, or null while dismissed (until the next price refresh). */
    fun showing(context: Context, quotes: List<Quote>): Showing? {
        val all = matches(context, quotes)
        return if (all.any { it.instanceKey in Prefs.dismissed(context) }) null else all.firstOrNull()
    }

    /** The instance keys of every showing now, dismissed or not (so dismissals in force are kept). */
    fun instanceKeys(context: Context, quotes: List<Quote>): List<String> = matches(context, quotes).map { it.instanceKey }

    /** Every showing now: big moves, then windows, then the everyday card. */
    private fun matches(context: Context, quotes: List<Quote>): List<Showing> {
        if (quotes.isEmpty()) return emptyList()
        val day = LocalDate.now().toEpochDay()
        val fetch = FinanceSource.refreshedAt
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val out = mutableListOf<Showing>()
        if (Prefs.financeOnMoves(context)) {
            val threshold = Prefs.financeThreshold(context).toDouble()
            val biggest = quotes.maxBy { it.moved }
            val on = biggest.moved >= threshold || (movesOnDay == day && biggest.moved >= threshold * MOVES_KEEP)
            movesOnDay = if (on) day else null
            if (on) {
                val session = biggest.extended?.takeIf { kotlin.math.abs(it.changePercent) >= kotlin.math.abs(biggest.changePercent) }
                    ?.let { " ${it.session.label.lowercase(Locale.getDefault())}" } ?: " today"
                out += Showing(
                    "finance:moves:$day:$fetch", byMoves = true, forward = true,
                    reason = String.format(Locale.getDefault(), "%s moved %.1f%%%s (big moves at %d%%)", biggest.symbol, biggest.moved, session, threshold.toInt()),
                )
            }
        }
        if (Prefs.financeInWindows(context)) {
            Prefs.financeWindows(context).forEachIndexed { i, (start, end) ->
                val inside = if (start <= end) minute in start until end else minute >= start || minute < end
                if (inside) {
                    out += Showing(
                        "finance:window:$i:${if (start > end && minute < end) day - 1 else day}:$fetch", byMoves = false, forward = true,
                        reason = "in your ${clock(start)}–${clock(end)} window",
                    )
                }
            }
        }
        out += Showing("finance:up:$fetch", byMoves = false, forward = false, reason = "always up")
        return out
    }

    /** For the settings: whether the card is up and why, or that it was dismissed for today. */
    fun explain(context: Context, quotes: List<Quote>): String {
        if (quotes.isEmpty()) return "Waiting for prices."
        val shown = showing(context, quotes)
            ?: return "Dismissed; it comes back with the next price refresh (every ${Prefs.financeRefreshMinutes(context)} min)."
        return if (shown.forward) "Up, at the front: ${shown.reason}." else "Up, updating with each price refresh."
    }

    private fun clock(minuteOfDay: Int) = String.format(Locale.getDefault(), "%02d:%02d", minuteOfDay / 60 % 24, minuteOfDay % 60)
}
