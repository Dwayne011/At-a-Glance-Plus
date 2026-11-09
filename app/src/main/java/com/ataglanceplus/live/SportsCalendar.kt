package com.ataglanceplus.live

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.edit
import com.ataglanceplus.DebugLog
import com.ataglanceplus.Prefs
import org.json.JSONObject

/**
 * Followed teams' upcoming games in one of the user's Google calendars (Settings › Sports ›
 * Add games to calendar, off by default). Kept up to date with the schedules (every half hour
 * while the app checks scores): a moved game moves, a postponed or dropped one is removed. Only
 * events this app added are ever changed; one the user deletes is not added again. Turning it
 * off, or choosing another calendar, removes the upcoming games it added.
 */
object SportsCalendar {

    private const val KEY_ON = "sports_calendar_on"
    private const val KEY_CALENDAR = "sports_calendar_id"
    /** eventId → {"row": the calendar event's id (-1 once the user deleted it), "key": followed key, "start": ms}. */
    private const val KEY_ADDED = "sports_calendar_added"
    private const val NOTE = "Added by At a Glance Plus"
    private const val AHEAD_MS = 60L * 24 * 3_600_000L

    data class Calendar(val id: Long, val name: String, val account: String, val colour: Int)

    fun enabled(context: Context) = Prefs.get(context).getBoolean(KEY_ON, false) && calendarId(context) != null
    fun on(context: Context) = Prefs.get(context).getBoolean(KEY_ON, false)
    fun calendarId(context: Context): Long? = Prefs.get(context).getLong(KEY_CALENDAR, -1L).takeIf { it >= 0 }

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The Google calendars the user can add events to. Blocking. */
    fun calendars(context: Context): List<Calendar> {
        if (!allowed(context)) return emptyList()
        val columns = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val where = "${CalendarContract.Calendars.ACCOUNT_TYPE} = 'com.google' AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, where, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Calendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))) }
        }.orEmpty()
    }

    /** Turns it on with [calendar] (removing the games added to another one first), and adds the games. Blocking. */
    fun choose(context: Context, calendar: Long) {
        if (calendarId(context) != calendar) removeUpcoming(context)
        Prefs.get(context).edit { putBoolean(KEY_ON, true); putLong(KEY_CALENDAR, calendar) }
        sync(context)
    }

    /** Turns it off and removes the upcoming games it added. Blocking. */
    fun turnOff(context: Context) {
        Prefs.get(context).edit { putBoolean(KEY_ON, false) }
        removeUpcoming(context)
    }

    private fun added(context: Context): JSONObject =
        runCatching { JSONObject(Prefs.get(context).getString(KEY_ADDED, "{}")!!) }.getOrDefault(JSONObject())

    private fun save(context: Context, added: JSONObject) = Prefs.get(context).edit { putString(KEY_ADDED, added.toString()) }

    private fun removeUpcoming(context: Context) {
        val added = added(context)
        val now = System.currentTimeMillis()
        if (allowed(context)) added.keys().asSequence().toList().forEach { id ->
            val o = added.getJSONObject(id)
            if (o.optLong("start") > now && o.optLong("row", -1) >= 0) delete(context, o.getLong("row"))
        }
        save(context, JSONObject())
    }

    private fun delete(context: Context, row: Long) {
        runCatching { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row), null, null) }
    }

    /** Whether event [row] is still in the calendar (not deleted by the user). */
    private fun exists(context: Context, row: Long): Boolean =
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row),
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED), null, null, null,
        )?.use { c -> c.moveToFirst() && c.getInt(1) == 0 } ?: false

    private fun minutes(sport: String) = when (sport) {
        "soccer", "rugby" -> 120
        "football" -> 210
        "baseball" -> 180
        "cricket" -> 480
        else -> 150
    }

    private fun title(g: Game) = if (g.sport == "soccer") "${g.home.name} v ${g.away.name}" else "${g.away.name} @ ${g.home.name}"

    /** Adds, moves and removes games to match the latest schedules. Blocking. */
    fun sync(context: Context) {
        if (!enabled(context) || !allowed(context)) return
        val calendar = calendarId(context) ?: return
        val now = System.currentTimeMillis()
        val followed = Prefs.sportsFollowed(context).filter { !it.isRacing }.map { it.key }.toSet()
        val schedules = SportsSource.games.value
        val added = added(context)
        val dropped = setOf("STATUS_POSTPONED", "STATUS_CANCELED", "STATUS_CANCELLED", "STATUS_SUSPENDED")
        var changes = 0
        // Upcoming games, each once (two followed teams can meet).
        val upcoming = schedules.filterKeys { it in followed }.flatMap { (key, games) -> games.map { key to it } }
            .filter { (_, g) -> g.state == "pre" && g.start > now && g.start - now < AHEAD_MS && g.statusName !in dropped }
            .distinctBy { it.second.eventId }
        upcoming.forEach { (key, g) ->
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, g.start)
                put(CalendarContract.Events.DTEND, g.start + minutes(g.sport) * 60_000L)
                put(CalendarContract.Events.TITLE, title(g))
                put(CalendarContract.Events.DESCRIPTION, "${g.leagueName}\n$NOTE")
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
            val known = added.optJSONObject(g.eventId)
            val row = known?.optLong("row", -1L)
            when {
                known == null -> {
                    values.put(CalendarContract.Events.CALENDAR_ID, calendar)
                    val uri = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull() ?: return@forEach
                    added.put(g.eventId, JSONObject().put("row", ContentUris.parseId(uri)).put("key", key).put("start", g.start).put("title", title(g)))
                    changes++
                }
                row == null || row < 0 -> Unit
                // Moved, or the teams' names changed (a fixture decided by an earlier round).
                known.optLong("start") != g.start || known.optString("title") != title(g) -> {
                    if (exists(context, row)) {
                        runCatching { context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row), values, null, null) }
                        known.put("start", g.start).put("title", title(g))
                    } else known.put("row", -1L)
                    changes++
                }
            }
        }
        // Games no longer coming: the team unfollowed, or the game postponed or gone from its
        // schedule (only judged when that schedule was fetched). Past games are forgotten but stay.
        val upcomingIds = upcoming.map { it.second.eventId }.toSet()
        added.keys().asSequence().toList().forEach { id ->
            val o = added.getJSONObject(id)
            val key = o.optString("key")
            val start = o.optLong("start")
            val row = o.optLong("row", -1L)
            val gone = id !in upcomingIds && (key !in followed || schedules[key].orEmpty().isNotEmpty())
            when {
                start <= now -> added.remove(id)
                gone && start - now < AHEAD_MS -> {
                    if (row >= 0) delete(context, row)
                    added.remove(id)
                    changes++
                }
            }
        }
        save(context, added)
        if (changes > 0) DebugLog.add(context, DebugLog.Kind.FETCH, "Games calendar: $changes change(s), ${upcoming.size} upcoming")
    }
}
