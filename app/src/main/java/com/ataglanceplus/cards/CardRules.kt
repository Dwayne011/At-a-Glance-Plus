package com.ataglanceplus.cards

import android.content.Context
import androidx.core.content.edit
import com.ataglanceplus.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Quiet hours and the smarter card order: rules with a time window (it can run past midnight),
 * the days it applies, and the cards it names.
 *
 * - Quiet hours hide the named cards during their windows (the weather card always stays).
 * - Card order (on by default; one switch turns it all off) brings cards forward: your own
 *   windows first, in the order their cards are listed, then the built-in rules, each switchable:
 *   calendar first in the morning, sports first around kick-off, rain and snow first when coming.
 *   Cards nothing names keep the usual order; the message pop-up always stays first.
 */
object CardRules {

    /** A window from [start] to [end] (minutes after midnight; end before start runs past midnight) on [days] (1 = Monday). */
    data class Rule(val start: Int, val end: Int, val days: Set<Int>, val cards: List<String>) {
        fun covers(now: LocalDateTime): Boolean {
            val m = now.hour * 60 + now.minute
            val day = now.dayOfWeek.value
            return if (start <= end) day in days && m in start until end
            // Past midnight: the evening part belongs to the day it starts, the morning part to the day before's rule.
            else (m >= start && day in days) || (m < end && now.minusDays(1).dayOfWeek.value in days)
        }

        fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("d", JSONArray(days.sorted())).put("c", JSONArray(cards))

        companion object {
            fun fromJson(o: JSONObject) = Rule(
                o.getInt("s"), o.getInt("e"),
                o.getJSONArray("d").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
                o.getJSONArray("c").let { a -> (0 until a.length()).map { a.getString(it) } },
            )
        }
    }

    /** The built-in order rules. */
    enum class Builtin(val key: String, val label: String, val detail: String) {
        MORNING_CALENDAR("order_morning_calendar", "Calendar first in the morning", "05:00 to 10:00"),
        SPORTS_KICKOFF("order_sports_kickoff", "Sports first around kick-off", "From an hour before a game or session until it ends"),
        RAIN_COMING("order_rain", "Rain and snow first", "When rain or snow is coming within the hour"),
        COMMUTE("order_commute", "Commute first", "At your commute times, when the commute card shows"),
    }

    private const val KEY_ORDER_ON = "card_order_on"
    private const val KEY_ORDER_WINDOWS = "card_order_windows"
    private const val KEY_QUIET = "quiet_rules"

    fun orderOn(context: Context) = Prefs.get(context).getBoolean(KEY_ORDER_ON, true)
    fun setOrderOn(context: Context, on: Boolean) = Prefs.get(context).edit { putBoolean(KEY_ORDER_ON, on) }
    fun builtinOn(context: Context, b: Builtin) = Prefs.get(context).getBoolean(b.key, true)
    fun setBuiltinOn(context: Context, b: Builtin, on: Boolean) = Prefs.get(context).edit { putBoolean(b.key, on) }

    private fun read(context: Context, key: String): List<Rule> = runCatching {
        val a = JSONArray(Prefs.get(context).getString(key, "[]"))
        (0 until a.length()).map { Rule.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(context: Context, key: String, rules: List<Rule>) =
        Prefs.get(context).edit { putString(key, JSONArray(rules.map { it.toJson() }).toString()) }

    fun orderWindows(context: Context) = read(context, KEY_ORDER_WINDOWS)
    fun setOrderWindows(context: Context, rules: List<Rule>) = write(context, KEY_ORDER_WINDOWS, rules)
    fun quietRules(context: Context) = read(context, KEY_QUIET)
    fun setQuietRules(context: Context, rules: List<Rule>) = write(context, KEY_QUIET, rules)

    /** Card ids hidden by quiet hours now. */
    fun quietNow(context: Context, now: LocalDateTime = LocalDateTime.now()): Set<String> =
        quietRules(context).filter { it.covers(now) }.flatMap { it.cards }.toSet()

    /** A card on show, for ordering: its type id, and whether it is "around kick-off" (sports). */
    data class Slot(val card: String, val aroundKickoff: Boolean = false)

    /**
     * The ranks that order the cards (lower first; equal ranks keep the usual order). [slots] are
     * the cards in the usual order; the result has one rank each.
     */
    fun ranks(context: Context, slots: List<Slot>, now: LocalDateTime = LocalDateTime.now()): List<Int> {
        if (!orderOn(context)) return slots.map { 0 }
        // The user's windows: the first window covering now that names the card decides, by its position.
        val windows = orderWindows(context).filter { it.covers(now) }
        val m = now.hour * 60 + now.minute
        return slots.map { slot ->
            if (slot.card == CardType.MESSAGES.id) return@map Int.MIN_VALUE
            windows.firstNotNullOfOrNull { w -> w.cards.indexOf(slot.card).takeIf { it >= 0 } }?.let { return@map -1000 + it }
            val boosted = when (slot.card) {
                CardType.CALENDAR.id -> builtinOn(context, Builtin.MORNING_CALENDAR) && m in 5 * 60 until 10 * 60
                CardType.SPORTS.id -> builtinOn(context, Builtin.SPORTS_KICKOFF) && slot.aroundKickoff
                CardType.RAIN.id -> builtinOn(context, Builtin.RAIN_COMING)
                CardType.COMMUTE.id -> builtinOn(context, Builtin.COMMUTE)
                else -> false
            }
            if (boosted) -100 else 0
        }
    }

    /** What the rules say now, as a string that changes only when the cards would: the service redraws then. */
    fun stateNow(context: Context, now: LocalDateTime = LocalDateTime.now()): String {
        val m = now.hour * 60 + now.minute
        val order = if (orderOn(context)) orderWindows(context).mapIndexedNotNull { i, w -> i.takeIf { w.covers(now) } } + listOf(if (m in 5 * 60 until 10 * 60) 1 else 0) else emptyList()
        return quietNow(context, now).sorted().joinToString() + "|" + order.joinToString()
    }

    /** "07:00–09:00 · Mon–Fri": a rule's window and days, for settings. */
    fun describe(rule: Rule): String {
        fun hm(v: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", v / 60, v % 60)
        val days = when (rule.days) {
            (1..7).toSet() -> "every day"
            (1..5).toSet() -> "Mon–Fri"
            setOf(6, 7) -> "weekends"
            else -> rule.days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
        }
        return "${hm(rule.start)}–${hm(rule.end)} · $days"
    }
}
