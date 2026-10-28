package com.ataglanceplus.complications

import android.content.Context
import android.util.Log
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
            if (steps > 0) ChipValues.Value(stepsText(steps)) else null
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
     * Sleep that ended after 18:00 yesterday, minus time marked awake or out of bed.
     */
    suspend fun sleep(context: Context, granted: Set<String>): ChipValues.Value? {
        if (SLEEP !in granted) return null
        return try {
            val from = Instant.ofEpochMilli(SimpleSources.startOfToday()).minus(Duration.ofHours(6))
            val sessions = HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from.minus(Duration.ofHours(18)), Instant.now()))
            ).records.filter { it.endTime.isAfter(from) }
            val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
            val minutes = sessions.sumOf { session ->
                val total = Duration.between(session.startTime, session.endTime)
                val awakeTime = session.stages.filter { it.stage in awake }
                    .fold(Duration.ZERO) { sum, stage -> sum + Duration.between(stage.startTime, stage.endTime) }
                (total - awakeTime).toMinutes()
            }
            if (minutes > 0) ChipValues.Value("${minutes / 60}h ${minutes % 60}m") else null
        } catch (e: Exception) {
            Log.w(TAG, "sleep read failed", e)
            null
        }
    }
}
