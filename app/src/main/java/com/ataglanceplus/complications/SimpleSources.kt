package com.ataglanceplus.complications

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Process
import android.provider.CallLog
import android.text.format.DateFormat
import com.ataglanceplus.Prefs
import com.ataglanceplus.weather.WeatherRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Chip values that need nothing beyond a system service or one runtime permission. */
object SimpleSources {

    fun battery(context: Context): ChipValues.Value? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        return ChipValues.Value("${level * 100 / scale}%")
    }

    /** The next alarm's time when it is within a day, otherwise its weekday ("Fri"). */
    fun alarm(context: Context): ChipValues.Value? {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val pattern = when {
            next.triggerTime - System.currentTimeMillis() > DAY_MS -> "EEE"
            DateFormat.is24HourFormat(context) -> "HH:mm"
            else -> "h:mm"
        }
        return ChipValues.Value(DateFormat.format(pattern, Date(next.triggerTime)).toString())
    }

    fun hasCallLog(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Missed calls not yet seen in the Phone app; shown in the needs-attention style. */
    fun missedCalls(context: Context): ChipValues.Value? {
        if (!hasCallLog(context)) return null
        val count = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()), null
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
        return if (count > 0) ChipValues.Value(cap(count), attention = true) else null
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /**
     * Time apps were on screen today, summed from activity resume and pause events. The home
     * screen and system UI are not counted. Close to, but not exactly, Digital Wellbeing's figure.
     */
    fun screenTime(context: Context): ChipValues.Value? {
        if (!hasUsageAccess(context)) return null
        val now = System.currentTimeMillis()
        val start = startOfToday()
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val ignored = setOfNotNull(home, "com.android.systemui")
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, now) ?: return null
        val open = HashMap<String, Long>()
        var total = 0L
        // Per app too, for the busiest app on a big chip.
        val byApp = HashMap<String, Long>()
        fun count(key: String, ms: Long) {
            total += ms
            val pkg = key.substringBefore('/')
            byApp[pkg] = (byApp[pkg] ?: 0L) + ms
        }
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName in ignored) continue
            val key = event.packageName + "/" + event.className
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[key] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(key)?.let { count(key, event.timeStamp - it) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    open.forEach { (key, from) -> count(key, event.timeStamp - from) }
                    open.clear()
                }
            }
        }
        open.forEach { (key, from) -> count(key, now - from) }
        val minutes = total / 60_000
        if (minutes <= 0) return null
        val text = duration(minutes)
        val top = byApp.maxByOrNull { it.value }?.takeIf { it.value >= 60_000 }?.let { (pkg, ms) ->
            val name = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
            "$name ${duration(ms / 60_000)}"
        }
        return ChipValues.Value(text, wide = top?.let { "$text · $it" }, detail = top)
    }

    /** "1h 20m", or "35m" under an hour. */
    fun duration(minutes: Long): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    /** The next sunrise or sunset at the weather location. */
    fun sunEvent(context: Context): ChipValues.Value? {
        val weather = WeatherRepository.weather.value ?: return null
        val (time, sunrise) = weather.nextSunEvent() ?: return null
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        fun clock(at: Long) = DateFormat.format(pattern, Date(at)).toString()
        fun name(rise: Boolean) = if (rise) "Sunrise" else "Sunset"
        // The one after, for a big chip: sunset, then tomorrow's sunrise.
        val after = weather.nextSunEvent(time)?.let { (at, rise) -> "${name(rise)} ${clock(at)}" }
        return ChipValues.Value(
            clock(time),
            wide = listOfNotNull("${name(sunrise)} ${clock(time)}", after).joinToString(" · "),
            detail = after,
        )
    }

    /** Days to the chosen date: "12d", "Today" on the day, nothing once it has passed. */
    fun countdown(context: Context): ChipValues.Value? {
        // A followed calendar event: its next date, wherever it has moved. If it has gone, the
        // last date seen stands.
        Prefs.countdownEvent(context)?.let { eventId ->
            com.ataglanceplus.live.CalendarSource.nextOf(context, eventId)?.day?.toEpochDay()?.let { day ->
                if (day != Prefs.countdownDay(context)) Prefs.moveCountdownDay(context, day)
            }
        }
        val target = Prefs.countdownDay(context) ?: return null
        val days = target - LocalDate.now().toEpochDay()
        val text = when {
            days > 0 -> "${days}d"
            days == 0L -> "Today"
            else -> return null
        }
        // For a big chip: what it is to, and the date ("Holiday · Mon 2 Nov").
        val date = LocalDate.ofEpochDay(target).format(
            java.time.format.DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM"))
        )
        val detail = listOf(Prefs.countdownName(context), date).filter { it.isNotEmpty() }.joinToString(" · ")
        return ChipValues.Value(text, wide = "$text · $detail", detail = detail)
    }

    /** Counts above 99 show as "99+" (spec 7.3, large number state). */
    fun cap(count: Int): String = if (count > 99) "99+" else count.toString()

    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
