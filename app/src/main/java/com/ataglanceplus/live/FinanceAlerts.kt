package com.ataglanceplus.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.ataglanceplus.DebugLog
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Price alerts (Settings › Stocks › Price alerts, off by default): a notification when one of the
 * user's stocks crosses a level they set (once, then the level is used up), or moves more than
 * their chosen percentage in a day (once a day per stock). Checked whenever prices arrive: the
 * card's refresh (every few minutes, as set) and the stock screen while it is open.
 */
object FinanceAlerts {

    private const val CHANNEL = "price_alerts"
    private const val KEY_ON = "finance_alerts_on"
    private const val KEY_MOVE = "finance_alert_move"
    private const val KEY_LEVELS = "finance_alert_levels"
    private const val KEY_MOVED = "finance_alert_moved"

    /** A level: notify once when [symbol] goes [above] (or below) [price]. */
    data class Level(val symbol: String, val price: Double, val above: Boolean)

    fun enabled(context: Context) = Prefs.get(context).getBoolean(KEY_ON, false)
    fun setEnabled(context: Context, on: Boolean) = Prefs.get(context).edit { putBoolean(KEY_ON, on) }

    /** The day's move that alerts, in percent; 0 for none. */
    fun movePercent(context: Context) = Prefs.get(context).getInt(KEY_MOVE, 5)
    fun setMovePercent(context: Context, percent: Int) = Prefs.get(context).edit { putInt(KEY_MOVE, percent) }

    fun levels(context: Context): List<Level> = runCatching {
        val a = JSONArray(Prefs.get(context).getString(KEY_LEVELS, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Level(o.getString("s"), o.getDouble("p"), o.getBoolean("a")) } }
    }.getOrDefault(emptyList())

    fun setLevels(context: Context, levels: List<Level>) = Prefs.get(context).edit {
        putString(KEY_LEVELS, JSONArray(levels.map { JSONObject().put("s", it.symbol).put("p", it.price).put("a", it.above) }).toString())
    }

    fun addLevel(context: Context, level: Level) = setLevels(context, levels(context) + level)
    fun removeLevel(context: Context, level: Level) = setLevels(context, levels(context) - level)

    /** Checks fresh [quotes] against the alerts. */
    fun check(context: Context, quotes: List<Quote>) {
        if (!enabled(context) || quotes.isEmpty()) return
        val levels = levels(context)
        val hit = mutableListOf<Level>()
        quotes.forEach { q ->
            val price = q.extended?.price ?: q.price
            levels.filter { it.symbol.equals(q.symbol, true) }.forEach { l ->
                if ((l.above && price >= l.price) || (!l.above && price <= l.price)) {
                    hit += l
                    notify(context, q, "${q.symbol} is ${if (l.above) "above" else "below"} ${money(l.price)}",
                        "Now ${money(price)} ${q.currency} · ${signed(q.changePercent)} today", "level:${q.symbol}:${l.price}:${l.above}")
                }
            }
            val move = movePercent(context)
            val moved = if (abs(q.extended?.changePercent ?: 0.0) > abs(q.changePercent)) q.extended!!.changePercent else q.changePercent
            if (move > 0 && abs(moved) >= move && firstMoveToday(context, q.symbol)) {
                notify(context, q, "${q.symbol} ${if (moved >= 0) "up" else "down"} ${String.format(Locale.ROOT, "%.1f", abs(moved))}% today",
                    "${q.name} · ${money(price)} ${q.currency}", "move:${q.symbol}")
            }
        }
        // A level alerts once, then is used up.
        if (hit.isNotEmpty()) setLevels(context, levels - hit.toSet())
    }

    /** True the first time a stock's big move is seen today; remembers it. */
    private fun firstMoveToday(context: Context, symbol: String): Boolean {
        val prefs = Prefs.get(context)
        val today = LocalDate.now().toString()
        val seen = runCatching { JSONObject(prefs.getString(KEY_MOVED, "{}")) }.getOrDefault(JSONObject())
        if (seen.optString(symbol.uppercase()) == today) return false
        seen.put(symbol.uppercase(), today)
        prefs.edit { putString(KEY_MOVED, seen.toString()) }
        return true
    }

    private fun money(v: Double) = String.format(Locale.ROOT, "%,.2f", v)
    private fun signed(v: Double) = String.format(Locale.ROOT, "%+.2f%%", v)

    private fun notify(context: Context, quote: Quote, title: String, text: String, tag: String) {
        DebugLog.add(context, DebugLog.Kind.FETCH, "Price alert: $title")
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, tag.hashCode(),
            com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_STOCK_PREFIX + quote.symbol),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(tag, 0, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_comp_trend)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
