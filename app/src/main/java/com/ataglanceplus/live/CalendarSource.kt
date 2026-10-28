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
        timed.firstOrNull { it.begin > now }?.let { return it }
        timed.firstOrNull { it.begin <= now }?.let { return it }
        if (!Prefs.calendarAllDay(context)) return null
        // All-day events are stored against UTC midnight.
        val today = LocalDate.now()
        return events.firstOrNull { it.allDay && Instant.ofEpochMilli(it.begin).atZone(ZoneOffset.UTC).toLocalDate() == today }
    }
}
