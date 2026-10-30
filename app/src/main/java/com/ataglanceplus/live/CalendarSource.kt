package com.ataglanceplus.live

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import com.ataglanceplus.Prefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The calendar event the card is about. */
data class CalendarNow(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val location: String,
    val colour: Int,
    val allDay: Boolean,
    /** The next timed event later today, for the card's "Then" line; null when there is none. */
    val thenTitle: String? = null,
    val thenBegin: Long? = null,
) {
    val instanceKey: String get() = "calendar:$eventId:$begin"
}

/**
 * The next event from the phone's calendars (every calendar shown in the Calendar app):
 * - a timed event from [Prefs.calendarLeadMinutes] before it starts until it ends,
 *   if it lasts 4 hours or less (longer blocks would sit on the card all day);
 * - otherwise, if switched on, today's all-day event.
 * Declined events are skipped. An upcoming event wins over one already under way.
 */
object CalendarSource {

    private const val TAG = "CalendarSource"
    private const val MAX_TIMED_MS = 4 * 60 * 60 * 1000L

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun next(context: Context): CalendarNow? {
        if (!hasPermission(context)) return null
        val now = System.currentTimeMillis()
        val lead = Prefs.calendarLeadMinutes(context) * 60_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 24 * 60 * 60 * 1000L)
            ContentUris.appendId(it, now + lead)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.ALL_DAY,
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val events: List<CalendarNow> = try {
            context.contentResolver.query(uri, columns, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList<CalendarNow> {
                    while (c.moveToNext()) {
                        add(
                            CalendarNow(
                                eventId = c.getLong(0),
                                begin = c.getLong(1),
                                end = c.getLong(2),
                                title = c.getString(3).orEmpty().ifEmpty { "(No title)" },
                                location = c.getString(4).orEmpty(),
                                colour = c.getInt(5) or 0xFF000000.toInt(),
                                allDay = c.getInt(6) == 1,
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "calendar query failed", e)
            return null
        }
        val timed = events.filter { !it.allDay && it.end > now && it.end - it.begin <= MAX_TIMED_MS }
        val chosen = timed.firstOrNull { it.begin > now } ?: timed.firstOrNull { it.begin <= now } ?: run {
            if (!Prefs.calendarAllDay(context)) return null
            // All-day events are stored against UTC midnight.
            val today = LocalDate.now()
            events.firstOrNull { it.allDay && Instant.ofEpochMilli(it.begin).atZone(ZoneOffset.UTC).toLocalDate() == today }
        } ?: return null
        val then = following(context, chosen, selection)
        return chosen.copy(thenTitle = then?.second, thenBegin = then?.first)
    }

    /** The first timed event starting after [event] and before midnight: its start and title. */
    private fun following(context: Context, event: CalendarNow, selection: String): Pair<Long, String>? {
        val after = if (event.allDay) System.currentTimeMillis() else event.begin + 1
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (after >= midnight) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, after)
            ContentUris.appendId(it, midnight)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID)
        return try {
            context.contentResolver.query(uri, columns, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) == 1 || c.getLong(0) < after || c.getLong(3) == event.eventId) continue
                    return@use c.getLong(0) to c.getString(1).orEmpty().ifEmpty { "(No title)" }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "calendar query failed", e)
            null
        }
    }

    /** An event to count down to: its next start and the day that falls on. */
    data class Upcoming(val eventId: Long, val begin: Long, val title: String, val allDay: Boolean) {
        /** All-day events are stored against UTC midnight; others fall on the local day. */
        val day: LocalDate
            get() = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else java.time.ZoneId.systemDefault()).toLocalDate()
    }

    /** Events from today for a year, each once (a repeating event at its next date), soonest first. */
    fun upcoming(context: Context): List<Upcoming> = instances(context, null).distinctBy { it.eventId }.take(MAX_UPCOMING)

    /** [eventId]'s next date from today, or null when it has none (deleted, or all in the past). */
    fun nextOf(context: Context, eventId: Long): Upcoming? = instances(context, eventId).firstOrNull()

    private fun instances(context: Context, eventId: Long?): List<Upcoming> {
        if (!hasPermission(context)) return emptyList()
        // From the start of today, so an event later today (or all day today) still counts.
        val from = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, from + 366 * 24 * 60 * 60 * 1000L)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
        )
        var selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        if (eventId != null) selection += " AND ${CalendarContract.Instances.EVENT_ID} = $eventId"
        return try {
            context.contentResolver.query(uri, columns, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Upcoming(c.getLong(0), c.getLong(1), c.getString(2).orEmpty().ifEmpty { "(No title)" }, c.getInt(3) == 1))
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "calendar query failed", e)
            emptyList()
        }.filter { it.day >= LocalDate.now() }
    }

    private const val MAX_UPCOMING = 300
}
