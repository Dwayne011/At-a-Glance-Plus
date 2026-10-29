package com.ataglanceplus.complications

import android.content.Context
import android.util.Log
import com.ataglanceplus.Prefs
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant

/**
 * Steps today and sleep last night, from Health Connect. Apps such as Google Health and Fitbit
 * write their data there once their Health Connect sync is on; this app only reads it.
 */
object HealthSource {

    private const val TAG = "HealthSource"

    val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    /** Needed because the cards are drawn while this app is not on screen. */
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    val ALL = setOf(STEPS, SLEEP, BACKGROUND)

    fun available(context: Context) =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(context: Context): Set<String> =
        if (!available(context)) emptySet()
        else runCatching { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun steps(context: Context, granted: Set<String>): ChipValues.Value? {
        if (STEPS !in granted) return null
        return try {
            val result = HealthConnectClient.getOrCreate(context).aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(SimpleSources.startOfToday()), Instant.now())
                )
            )
            val steps = result[StepsRecord.COUNT_TOTAL] ?: return null
            if (steps <= 0) return null
            // For a big chip: against the daily goal.
            val goal = Prefs.stepGoal(context)
            val format = NumberFormat.getIntegerInstance()
            val percent = (100 * steps / goal.coerceAtLeast(1)).toInt()
            ChipValues.Value(
                stepsText(steps),
                wide = "${format.format(steps)} of ${format.format(goal)}",
                detail = "$percent% of ${format.format(goal)}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "steps read failed", e)
            null
        }
    }

    /** "6,214", or "12.3k" from 10,000 on, so the chip stays within its 80dp cap (spec 7.2). */
    fun stepsText(steps: Long): String =
        if (steps < 10_000) NumberFormat.getIntegerInstance().format(steps)
        else String.format(java.util.Locale.getDefault(), "%.1fk", steps / 1000.0)

    /**
     * Last night's sleep, as Health Connect's "Today" shows it: sleep ending today that started
     * before midday (so an evening nap yesterday, or one this afternoon, is not added). Sleep
     * recorded by more than one app is counted once, and time marked awake or out of bed is
     * taken off.
     */
    suspend fun sleep(context: Context, granted: Set<String>): ChipValues.Value? {
        if (SLEEP !in granted) return null
        return try {
            val today = Instant.ofEpochMilli(SimpleSources.startOfToday())
            val midday = today.plus(Duration.ofHours(12))
            val sessions = HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(today.minus(Duration.ofHours(18)), Instant.now()))
            ).records.filter { it.endTime.isAfter(today) && it.startTime.isBefore(midday) }
            if (sessions.isEmpty()) return null
            val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
            val asleep = merged(sessions.map { it.startTime to it.endTime })
            val awakeTimes = merged(sessions.flatMap { s -> s.stages.filter { it.stage in awake }.map { it.startTime to it.endTime } })
            val minutes = (total(asleep) - total(awakeTimes).coerceAtMost(total(asleep))).toMinutes()
            if (minutes <= 0) return null
            // For a big chip: from going to sleep to waking.
            val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            fun clock(at: Instant) = android.text.format.DateFormat.format(pattern, java.util.Date(at.toEpochMilli())).toString()
            val span = "${clock(sessions.minOf { it.startTime })}–${clock(sessions.maxOf { it.endTime })}"
            val text = "${minutes / 60}h ${minutes % 60}m"
            ChipValues.Value(text, wide = "$text · $span", detail = span)
        } catch (e: Exception) {
            Log.w(TAG, "sleep read failed", e)
            null
        }
    }

    /** Overlapping spans joined into one, so time recorded twice counts once. */
    private fun merged(spans: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> =
        spans.sortedBy { it.first }.fold(mutableListOf()) { out, (start, end) ->
            val last = out.lastOrNull()
            if (last != null && !start.isAfter(last.second)) out[out.size - 1] = last.first to maxOf(last.second, end)
            else out += start to end
            out
        }

    private fun total(spans: List<Pair<Instant, Instant>>): Duration =
        spans.fold(Duration.ZERO) { sum, (start, end) -> sum + Duration.between(start, end) }
}
