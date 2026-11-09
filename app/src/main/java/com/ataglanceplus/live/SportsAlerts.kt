package com.ataglanceplus.live

import android.app.Notification
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

/**
 * Game alerts for followed teams (Settings › Sports › Game alerts, off by default): goals
 * (football and hockey), kick-off, half-time and the end of each period, and full time, each
 * with its own switch. Checked whenever live scores arrive; while a followed team plays they are
 * fetched every minute even with the screen off, when alerts are on. Each moment alerts once.
 * Teams whose results are hidden get the kick-off alert only.
 */
object SportsAlerts {

    private const val CHANNEL = "game_alerts"
    private const val KEY_ON = "sports_alerts_on"
    private const val KEY_SENT = "sports_alerts_sent"

    enum class Moment(val key: String, val label: String, val detail: String, val default: Boolean) {
        GOALS("sports_alert_goals", "Goals", "Football and hockey: the scorer and the new score", true),
        START("sports_alert_start", "Kick-off", "When the game starts", true),
        PERIODS("sports_alert_periods", "Half-time and periods", "Half-time, and the end of each quarter or period, with the score", false),
        FINAL("sports_alert_final", "Full time", "The final score", true),
    }

    fun enabled(context: Context) = Prefs.get(context).getBoolean(KEY_ON, false)
    fun setEnabled(context: Context, on: Boolean) = Prefs.get(context).edit { putBoolean(KEY_ON, on) }
    fun wants(context: Context, m: Moment) = Prefs.get(context).getBoolean(m.key, m.default)
    fun setWants(context: Context, m: Moment, on: Boolean) = Prefs.get(context).edit { putBoolean(m.key, on) }

    /** Sports where each score is a moment worth a notification. */
    private fun scoresGoals(sport: String) = sport == "soccer" || sport == "hockey"

    /** Compares a game's last known state ([old]) with the latest ([fresh]) for followed [key]. */
    fun check(context: Context, key: String, old: Game, fresh: Game, detail: () -> GameDetail?) {
        if (!enabled(context)) return
        val hidden = key in Prefs.sportsHidden(context)
        val soccer = fresh.sport == "soccer"
        if (wants(context, Moment.START) && old.state == "pre" && fresh.state == "in") {
            send(context, fresh, "ko", "${if (soccer) "Kick-off" else "Started"}: ${matchup(fresh)}", fresh.leagueName)
        }
        if (hidden) return
        val (oh, oa) = old.home.score?.toIntOrNull() to old.away.score?.toIntOrNull()
        val (fh, fa) = fresh.home.score?.toIntOrNull() to fresh.away.score?.toIntOrNull()
        if (wants(context, Moment.GOALS) && scoresGoals(fresh.sport) && fresh.state == "in" && fh != null && fa != null && (fh > (oh ?: 0) || fa > (oa ?: 0))) {
            val scorerSide = if (fh > (oh ?: 0)) fresh.home else fresh.away
            val d = detail()
            // The latest goal for that side, as the timeline has it.
            val goal = d?.let { (it.events + it.scoring).lastOrNull { e -> e.scoring && (e.team == null || e.team.contains(scorerSide.name, true) || scorerSide.name.contains(e.team, true)) } }
            send(
                context, fresh, "g:$fh-$fa", "Goal! ${score(fresh)}",
                listOfNotNull(scorerSide.name, goal?.let { listOf(it.text, it.clock).filter { s -> s.isNotEmpty() }.joinToString(" ") }).joinToString(" · "),
                logo = scorerSide.logoUrl,
            )
        }
        val periodEnd = fresh.statusName == "STATUS_HALFTIME" || fresh.statusName == "STATUS_END_PERIOD"
        // Each break alerts once (by its status: "HT", "End of 1st"…).
        if (wants(context, Moment.PERIODS) && periodEnd) {
            send(context, fresh, "p:${fresh.status}", "${fresh.status}: ${score(fresh)}", fresh.leagueName)
        }
        if (wants(context, Moment.FINAL) && old.state != "post" && fresh.state == "post") {
            send(context, fresh, "ft", "${if (soccer) "Full time" else "Final"}: ${score(fresh)}", fresh.leagueName,
                logo = listOf(fresh.home, fresh.away).firstOrNull { it.winner }?.logoUrl)
        }
    }

    /** "Liverpool v Chelsea" for football, "Lakers @ Celtics" (away first) elsewhere. */
    private fun matchup(g: Game) = if (g.sport == "soccer") "${g.home.name} v ${g.away.name}" else "${g.away.name} @ ${g.home.name}"

    private fun score(g: Game) = if (g.sport == "soccer") "${g.home.name} ${g.home.score ?: 0}–${g.away.score ?: 0} ${g.away.name}"
        else "${g.away.name} ${g.away.score ?: 0}, ${g.home.name} ${g.home.score ?: 0}"

    /** True the first time [tag] is seen for [game]; remembers it (the last 40 games). */
    private fun firstTime(context: Context, game: Game, tag: String): Boolean {
        val prefs = Prefs.get(context)
        val sent = runCatching { JSONObject(prefs.getString(KEY_SENT, "{}")!!) }.getOrDefault(JSONObject())
        val tags = sent.optJSONArray(game.eventId) ?: JSONArray()
        if ((0 until tags.length()).any { tags.optString(it) == tag }) return false
        sent.put(game.eventId, tags.put(tag))
        while (sent.length() > 40) sent.remove(sent.keys().next())
        prefs.edit { putString(KEY_SENT, sent.toString()) }
        return true
    }

    private fun send(context: Context, game: Game, tag: String, title: String, text: String, logo: String? = null) {
        if (!firstTime(context, game, tag)) return
        DebugLog.add(context, DebugLog.Kind.FETCH, "Game alert: $title")
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, game.eventId.hashCode(),
            com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_GAME_PREFIX + game.eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_trophy)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        (SportsSource.logo(logo) ?: logo?.let { SportsSource.logoNow(context, it) })?.let { builder.setLargeIcon(it) }
        // One notification per game, replaced as it goes on.
        manager.notify("game:${game.eventId}", 0, builder.build())
    }
}
