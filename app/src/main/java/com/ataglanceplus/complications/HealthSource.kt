package com.ataglanceplus.complications

import android.content.Context
import android.util.Log
import com.ataglanceplus.Prefs
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

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
                progress = (steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f),
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

    /** Last night's sleep (see [night]). */
    suspend fun sleep(context: Context, granted: Set<String>): ChipValues.Value? {
        if (SLEEP !in granted) return null
        return try {
            val today = LocalDate.now()
            val sessions = HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(startOf(today).minus(Duration.ofHours(18)), Instant.now()))
            ).records
            val night = night(sessions, today) ?: return null
            val minutes = night.minutes
            if (minutes <= 0) return null
            // For a big chip: from going to sleep to waking.
            val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            fun clock(at: Instant) = android.text.format.DateFormat.format(pattern, java.util.Date(at.toEpochMilli())).toString()
            val span = "${clock(night.start)}–${clock(night.end)}"
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

    private fun startOf(day: LocalDate): Instant = day.atStartOfDay(ZoneId.systemDefault()).toInstant()

    /** A night's sleep: from falling asleep to waking, time asleep, and its stages when recorded. */
    data class Night(val day: LocalDate, val start: Instant, val end: Instant, val minutes: Long, val stages: List<Stage>)

    /** A stretch of one sleep stage. */
    data class Stage(val start: Instant, val end: Instant, val kind: StageKind)

    enum class StageKind(val label: String) { AWAKE("Awake"), REM("REM"), LIGHT("Light"), DEEP("Deep") }

    /**
     * The night ending on [day], as Health Connect's "Today" shows it: sleep ending that day that
     * started before midday (so an evening nap the day before, or one that afternoon, is not
     * added). Sleep recorded by more than one app is counted once, and time marked awake or out
     * of bed is taken off.
     */
    fun night(all: List<SleepSessionRecord>, day: LocalDate): Night? {
        val dayStart = startOf(day)
        val midday = dayStart.plus(Duration.ofHours(12))
        val sessions = all.filter { it.endTime.isAfter(dayStart) && it.startTime.isBefore(midday) }
        if (sessions.isEmpty()) return null
        val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED)
        val asleep = merged(sessions.map { it.startTime to it.endTime })
        val awakeTimes = merged(sessions.flatMap { s -> s.stages.filter { it.stage in awake }.map { it.startTime to it.endTime } })
        val minutes = (total(asleep) - total(awakeTimes).coerceAtMost(total(asleep))).toMinutes()
        if (minutes <= 0) return null
        val stages = sessions.flatMap { s ->
            s.stages.mapNotNull { stage ->
                val kind = when (stage.stage) {
                    in awake -> StageKind.AWAKE
                    SleepSessionRecord.STAGE_TYPE_REM -> StageKind.REM
                    SleepSessionRecord.STAGE_TYPE_DEEP -> StageKind.DEEP
                    SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> StageKind.LIGHT
                    else -> null
                }
                kind?.let { Stage(stage.startTime, stage.endTime, it) }
            }
        }.sortedBy { it.start }
        return Night(day, sessions.minOf { it.startTime }, sessions.maxOf { it.endTime }, minutes, stages)
    }

    /** Sleep for the dashboard: last night in full, and the last 7 nights (oldest first, null when none). */
    suspend fun sleepWeek(context: Context): List<Pair<LocalDate, Night?>> {
        val today = LocalDate.now()
        val sessions = HealthConnectClient.getOrCreate(context).readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(startOf(today.minusDays(7)), Instant.now()))
        ).records
        return (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to night(sessions, it) } }
    }

    /** Steps for the dashboard: today by hour (24 values) and the last 7 days (oldest first). */
    suspend fun stepsWeek(context: Context): Pair<List<Long>, List<Pair<LocalDate, Long>>> {
        val client = HealthConnectClient.getOrCreate(context)
        val today = LocalDate.now()
        val hours = LongArray(24)
        client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOf(today), Instant.now()),
                timeRangeSlicer = Duration.ofHours(1),
            )
        ).forEach { slice ->
            val hour = slice.startTime.atZone(ZoneId.systemDefault()).hour
            hours[hour] += slice.result[StepsRecord.COUNT_TOTAL] ?: 0L
        }
        val days = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(today.minusDays(6).atStartOfDay(), LocalDateTime.now()),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
        return hours.toList() to (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to (days[it] ?: 0L) } }
    }
}
