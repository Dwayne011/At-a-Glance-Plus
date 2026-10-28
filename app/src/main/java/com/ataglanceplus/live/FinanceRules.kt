package com.ataglanceplus.live

import android.content.Context
import com.ataglanceplus.Prefs
import java.time.LocalDate
import java.time.LocalTime

/**
 * When the finance card shows, from the user's choices (any that match):
 * - big moves: a stock has moved at least the threshold today;
 * - time windows: during any of the user's windows;
 * - periodically: for 10 minutes at the start of every N hours (e.g. 10:00–10:10, 12:00–12:10).
 *
 * The result is an instance key naming this showing, so a dismissal lasts until it ends.
 */
object FinanceRules {

    private const val PERIOD_SHOW_MINUTES = 10

    data class Showing(val instanceKey: String, val byMoves: Boolean)

    fun showing(context: Context, quotes: List<Quote>): Showing? {
        if (quotes.isEmpty()) return null
        val day = LocalDate.now().toEpochDay()
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        if (Prefs.financeOnMoves(context) && quotes.any { it.moved >= Prefs.financeThreshold(context) }) {
            return Showing("finance:moves:$day", byMoves = true)
        }
        if (Prefs.financeInWindows(context)) {
            Prefs.financeWindows(context).forEachIndexed { i, (start, end) ->
                val inside = if (start <= end) minute in start until end else minute >= start || minute < end
                if (inside) return Showing("finance:window:$i:${if (start > end && minute < end) day - 1 else day}", byMoves = false)
            }
        }
        if (Prefs.financePeriodic(context)) {
            val period = Prefs.financePeriodHours(context) * 60
            if (minute % period < PERIOD_SHOW_MINUTES) return Showing("finance:period:$day:${minute / period}", byMoves = false)
        }
        return null
    }
}
