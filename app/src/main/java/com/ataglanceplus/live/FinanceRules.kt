package com.ataglanceplus.live

import android.content.Context
import com.ataglanceplus.Prefs
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * When the finance card shows, from the user's choices (any that match):
 * - big moves: a stock has moved at least the threshold today (or outside regular hours since the
 *   close). Once shown, it stays until the biggest move falls below three quarters of the
 *   threshold, so a stock hovering at the threshold does not make the card come and go;
 * - time windows: during any of the user's windows;
 * - periodically: for 10 minutes at the start of every N hours (e.g. 10:00–10:10, 12:00–12:10).
 *
 * Each match has an instance key naming that showing, so a dismissal lasts until it ends. A
 * dismissed match does not hide the others: dismissing the big-moves card still lets a window or
 * a periodic showing through.
 */
object FinanceRules {

    private const val PERIOD_SHOW_MINUTES = 10
    /** A big-moves showing ends when the biggest move falls below this share of the threshold. */
    private const val MOVES_KEEP = 0.75

    data class Showing(val instanceKey: String, val byMoves: Boolean, val reason: String)

    /** The day the big-moves showing is on (epoch day), or null; kept while the service runs. */
    @Volatile private var movesOnDay: Long? = null

    /** The first matching showing the user has not dismissed, or null. */
    fun showing(context: Context, quotes: List<Quote>): Showing? {
        val dismissed = Prefs.dismissed(context)
        return matches(context, quotes).firstOrNull { it.instanceKey !in dismissed }
    }

    /** The instance keys of every rule that matches now, dismissed or not (so dismissals in force are kept). */
    fun instanceKeys(context: Context, quotes: List<Quote>): List<String> = matches(context, quotes).map { it.instanceKey }

    /** Every rule that matches now, big moves first. */
    private fun matches(context: Context, quotes: List<Quote>): List<Showing> {
        if (quotes.isEmpty()) return emptyList()
        val day = LocalDate.now().toEpochDay()
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
                    "finance:moves:$day", byMoves = true,
                    reason = String.format(Locale.getDefault(), "%s moved %.1f%%%s (big moves at %d%%)", biggest.symbol, biggest.moved, session, threshold.toInt()),
                )
            }
        }
        if (Prefs.financeInWindows(context)) {
            Prefs.financeWindows(context).forEachIndexed { i, (start, end) ->
                val inside = if (start <= end) minute in start until end else minute >= start || minute < end
                if (inside) {
                    out += Showing(
                        "finance:window:$i:${if (start > end && minute < end) day - 1 else day}", byMoves = false,
                        reason = "in your ${clock(start)}–${clock(end)} window",
                    )
                }
            }
        }
        if (Prefs.financePeriodic(context)) {
            val period = Prefs.financePeriodHours(context) * 60
            if (minute % period < PERIOD_SHOW_MINUTES) {
                out += Showing("finance:period:$day:${minute / period}", byMoves = false, reason = "every ${period / 60} h, for $PERIOD_SHOW_MINUTES minutes")
            }
        }
        return out
    }

    /**
     * For the settings: why the card is showing now, or that it is not and when it next will by
     * time (a window or the next period). Big moves cannot be foreseen, so they are only named.
     */
    fun explain(context: Context, quotes: List<Quote>): String {
        if (quotes.isEmpty()) return "Not showing: no prices yet."
        val dismissed = Prefs.dismissed(context)
        val all = matches(context, quotes)
        all.firstOrNull { it.instanceKey !in dismissed }?.let { return "Showing now: ${it.reason}." }
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val next = buildList {
            if (Prefs.financeInWindows(context)) Prefs.financeWindows(context).forEach { (start, _) -> add(start) }
            if (Prefs.financePeriodic(context)) {
                val period = Prefs.financePeriodHours(context) * 60
                add((minute / period + 1) * period % (24 * 60))
            }
        }.minByOrNull { (it - minute + 24 * 60) % (24 * 60) }
        return buildString {
            append(if (all.isNotEmpty()) "Dismissed until ${all.first().reason} ends." else "Not showing now.")
            if (next != null) append(" Next by time: ${clock(next)}.")
            if (Prefs.financeOnMoves(context)) {
                val biggest = quotes.maxBy { it.moved }
                append(String.format(Locale.getDefault(), " Biggest move: %s %.1f%% (shows at %d%%).", biggest.symbol, biggest.moved, Prefs.financeThreshold(context)))
            }
        }
    }

    private fun clock(minuteOfDay: Int) = String.format(Locale.getDefault(), "%02d:%02d", minuteOfDay / 60 % 24, minuteOfDay % 60)
}
