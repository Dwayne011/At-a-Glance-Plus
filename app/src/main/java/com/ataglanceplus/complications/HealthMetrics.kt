package com.ataglanceplus.complications

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ataglanceplus.R
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.util.Locale
import kotlin.reflect.KClass

/** How the health metrics are grouped when picking one. */
enum class MetricGroup(val label: String) { HEART("Heart"), ACTIVITY("Activity"), BODY("Body"), VITALS("Vitals") }

/**
 * How a metric behaves: a daily [TOTAL] (calories, distance), [READINGS] taken through the day
 * (heart rate, blood oxygen), or an [OCCASIONAL] measurement (weight, resting heart rate).
 */
enum class MetricShape { TOTAL, READINGS, OCCASIONAL }

/** A health metric a chip can show, read from Health Connect. Its id is the chip's argument. */
enum class HealthMetric(
    val id: String,
    val label: String,
    val group: MetricGroup,
    val shape: MetricShape,
    val record: KClass<out Record>,
    @DrawableRes val icon: Int,
    @DrawableRes val aodIcon: Int,
) {
    HEART_RATE("hr", "Heart rate", MetricGroup.HEART, MetricShape.READINGS, HeartRateRecord::class, R.drawable.ic_comp_heart, R.drawable.ic_comp_heart_aod),
    RESTING_HR("rhr", "Resting heart rate", MetricGroup.HEART, MetricShape.OCCASIONAL, RestingHeartRateRecord::class, R.drawable.ic_comp_heart, R.drawable.ic_comp_heart_aod),
    HRV("hrv", "Heart rate variability", MetricGroup.HEART, MetricShape.OCCASIONAL, HeartRateVariabilityRmssdRecord::class, R.drawable.ic_comp_pulse, R.drawable.ic_comp_pulse_aod),
    ACTIVE_CALORIES("active_cal", "Active calories", MetricGroup.ACTIVITY, MetricShape.TOTAL, ActiveCaloriesBurnedRecord::class, R.drawable.ic_comp_flame, R.drawable.ic_comp_flame_aod),
    TOTAL_CALORIES("total_cal", "Total calories", MetricGroup.ACTIVITY, MetricShape.TOTAL, TotalCaloriesBurnedRecord::class, R.drawable.ic_comp_flame, R.drawable.ic_comp_flame_aod),
    DISTANCE("distance", "Distance", MetricGroup.ACTIVITY, MetricShape.TOTAL, DistanceRecord::class, R.drawable.ic_comp_route, R.drawable.ic_comp_route_aod),
    FLOORS("floors", "Floors climbed", MetricGroup.ACTIVITY, MetricShape.TOTAL, FloorsClimbedRecord::class, R.drawable.ic_comp_stairs, R.drawable.ic_comp_stairs_aod),
    EXERCISE("exercise", "Exercise", MetricGroup.ACTIVITY, MetricShape.TOTAL, ExerciseSessionRecord::class, R.drawable.ic_comp_stopwatch, R.drawable.ic_comp_stopwatch_aod),
    WEIGHT("weight", "Weight", MetricGroup.BODY, MetricShape.OCCASIONAL, WeightRecord::class, R.drawable.ic_comp_scale, R.drawable.ic_comp_scale_aod),
    BODY_FAT("body_fat", "Body fat", MetricGroup.BODY, MetricShape.OCCASIONAL, BodyFatRecord::class, R.drawable.ic_comp_percent, R.drawable.ic_comp_percent_aod),
    BODY_TEMPERATURE("body_temp", "Body temperature", MetricGroup.BODY, MetricShape.READINGS, BodyTemperatureRecord::class, R.drawable.ic_comp_thermometer, R.drawable.ic_comp_thermometer_aod),
    SKIN_TEMPERATURE("skin_temp", "Skin temperature", MetricGroup.BODY, MetricShape.OCCASIONAL, SkinTemperatureRecord::class, R.drawable.ic_comp_thermometer, R.drawable.ic_comp_thermometer_aod),
    OXYGEN("spo2", "Blood oxygen", MetricGroup.VITALS, MetricShape.READINGS, OxygenSaturationRecord::class, R.drawable.ic_comp_droplet, R.drawable.ic_comp_droplet_aod),
    RESPIRATORY_RATE("resp", "Respiratory rate", MetricGroup.VITALS, MetricShape.READINGS, RespiratoryRateRecord::class, R.drawable.ic_comp_wind, R.drawable.ic_comp_wind_aod),
    BLOOD_PRESSURE("bp", "Blood pressure", MetricGroup.VITALS, MetricShape.READINGS, BloodPressureRecord::class, R.drawable.ic_comp_gauge, R.drawable.ic_comp_gauge_aod),
    BLOOD_GLUCOSE("glucose", "Blood glucose", MetricGroup.VITALS, MetricShape.READINGS, BloodGlucoseRecord::class, R.drawable.ic_comp_glucose, R.drawable.ic_comp_glucose_aod),
    VO2_MAX("vo2", "VO2 max", MetricGroup.VITALS, MetricShape.OCCASIONAL, Vo2MaxRecord::class, R.drawable.ic_comp_pulse, R.drawable.ic_comp_pulse_aod),
    HYDRATION("hydration", "Hydration", MetricGroup.VITALS, MetricShape.TOTAL, HydrationRecord::class, R.drawable.ic_comp_glass, R.drawable.ic_comp_glass_aod);

    val permission: String get() = HealthPermission.getReadPermission(record)

    companion object {
        fun fromId(id: String?): HealthMetric? = entries.firstOrNull { it.id == id }
    }
}

/** One reading: when, its value in the units shown, and a second value (blood pressure's diastolic). */
data class Reading(val time: Instant, val value: Double, val second: Double? = null)

/** Reads, totals and formats the health metrics, in the units of the phone's region. */
object HealthMetrics {

    private const val TAG = "HealthMetrics"

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun startOf(day: LocalDate): Instant = day.atStartOfDay(zone).toInstant()

    private val system: LocaleData.MeasurementSystem
        get() = runCatching { LocaleData.getMeasurementSystem(ULocale.getDefault()) }.getOrDefault(LocaleData.MeasurementSystem.SI)
    private val us get() = system == LocaleData.MeasurementSystem.US
    private val miles get() = system != LocaleData.MeasurementSystem.SI

    /** The unit after a value, as shown on a dashboard: "bpm", "km", "kcal". */
    fun unit(metric: HealthMetric): String = when (metric) {
        HealthMetric.HEART_RATE, HealthMetric.RESTING_HR -> "bpm"
        HealthMetric.HRV -> "ms"
        HealthMetric.ACTIVE_CALORIES, HealthMetric.TOTAL_CALORIES -> "kcal"
        HealthMetric.DISTANCE -> if (miles) "mi" else "km"
        HealthMetric.FLOORS -> "floors"
        HealthMetric.EXERCISE -> ""
        HealthMetric.WEIGHT -> if (us) "lb" else "kg"
        HealthMetric.BODY_FAT, HealthMetric.OXYGEN -> "%"
        HealthMetric.BODY_TEMPERATURE, HealthMetric.SKIN_TEMPERATURE -> if (us) "°F" else "°C"
        HealthMetric.RESPIRATORY_RATE -> "breaths/min"
        HealthMetric.BLOOD_PRESSURE -> "mmHg"
        HealthMetric.BLOOD_GLUCOSE -> if (us) "mg/dL" else "mmol/L"
        HealthMetric.VO2_MAX -> "ml/kg/min"
        HealthMetric.HYDRATION -> if (us) "fl oz" else "L"
    }

    /** A value as a chip shows it: short, with only units that fit ("72", "4.2 km", "120/80", "+0.3°"). */
    fun short(metric: HealthMetric, value: Double, second: Double? = null): String = when (metric) {
        HealthMetric.DISTANCE -> "${decimal(value, 1)} ${unit(metric)}"
        HealthMetric.WEIGHT -> "${decimal(value, 1)} ${unit(metric)}"
        HealthMetric.BODY_FAT, HealthMetric.OXYGEN -> "${decimal(value, if (metric == HealthMetric.OXYGEN) 0 else 1)}%"
        HealthMetric.BODY_TEMPERATURE -> "${decimal(value, 1)}°"
        HealthMetric.SKIN_TEMPERATURE -> (if (value >= 0) "+" else "") + "${decimal(value, 1)}°"
        HealthMetric.EXERCISE -> SimpleSources.duration(value.toLong())
        HealthMetric.HYDRATION -> if (us) "${decimal(value, 0)} fl oz" else "${decimal(value, 1)} L"
        HealthMetric.BLOOD_PRESSURE -> "${value.toInt()}/${second?.toInt() ?: "?"}"
        HealthMetric.BLOOD_GLUCOSE -> decimal(value, if (us) 0 else 1)
        else -> decimal(value, 0)
    }

    /** A value in full, with its unit ("72 bpm", "120/80 mmHg", "312 kcal"). */
    fun full(metric: HealthMetric, value: Double, second: Double? = null): String = when (metric) {
        HealthMetric.DISTANCE, HealthMetric.WEIGHT, HealthMetric.HYDRATION, HealthMetric.EXERCISE,
        HealthMetric.BODY_FAT, HealthMetric.OXYGEN -> short(metric, value, second)
        HealthMetric.BODY_TEMPERATURE, HealthMetric.SKIN_TEMPERATURE -> short(metric, value, second).dropLast(1) + unit(metric)
        else -> "${short(metric, value, second)} ${unit(metric)}"
    }

    private fun decimal(value: Double, places: Int): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = places
        maximumFractionDigits = places
    }.format(value)

    // --- Daily totals ---

    /** The aggregate for a [MetricShape.TOTAL] metric, read out in the units shown. */
    private fun totalOf(metric: HealthMetric, result: AggregationResult): Double? = when (metric) {
        HealthMetric.ACTIVE_CALORIES -> result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        HealthMetric.TOTAL_CALORIES -> result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        HealthMetric.DISTANCE -> result[DistanceRecord.DISTANCE_TOTAL]?.let { if (miles) it.inMiles else it.inKilometers }
        HealthMetric.FLOORS -> result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
        HealthMetric.EXERCISE -> result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toDouble()
        HealthMetric.HYDRATION -> result[HydrationRecord.VOLUME_TOTAL]?.let { if (us) it.inFluidOuncesUs else it.inLiters }
        else -> null
    }

    private fun aggregateOf(metric: HealthMetric): AggregateMetric<*> = when (metric) {
        HealthMetric.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        HealthMetric.TOTAL_CALORIES -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
        HealthMetric.DISTANCE -> DistanceRecord.DISTANCE_TOTAL
        HealthMetric.FLOORS -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        HealthMetric.EXERCISE -> ExerciseSessionRecord.EXERCISE_DURATION_TOTAL
        HealthMetric.HYDRATION -> HydrationRecord.VOLUME_TOTAL
        else -> error("${metric.id} is not a daily total")
    }

    suspend fun totalToday(context: Context, metric: HealthMetric): Double {
        val result = HealthConnectClient.getOrCreate(context).aggregate(
            AggregateRequest(setOf(aggregateOf(metric)), TimeRangeFilter.between(startOf(LocalDate.now()), Instant.now()))
        )
        return totalOf(metric, result) ?: 0.0
    }

    /** Today by hour (24 values). */
    suspend fun totalsByHour(context: Context, metric: HealthMetric): List<Double> {
        val hours = DoubleArray(24)
        HealthConnectClient.getOrCreate(context).aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = setOf(aggregateOf(metric)),
                timeRangeFilter = TimeRangeFilter.between(startOf(LocalDate.now()), Instant.now()),
                timeRangeSlicer = Duration.ofHours(1),
            )
        ).forEach { slice -> hours[slice.startTime.atZone(zone).hour] += totalOf(metric, slice.result) ?: 0.0 }
        return hours.toList()
    }

    /** The last [days] days, oldest first. */
    suspend fun totalsByDay(context: Context, metric: HealthMetric, days: Int = 7): List<Pair<LocalDate, Double>> {
        val today = LocalDate.now()
        val byDay = HealthConnectClient.getOrCreate(context).aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(aggregateOf(metric)),
                timeRangeFilter = TimeRangeFilter.between(today.minusDays(days - 1L).atStartOfDay(), LocalDateTime.now()),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate { it.startTime.toLocalDate() to (totalOf(metric, it.result) ?: 0.0) }
        return (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to (byDay[it] ?: 0.0) } }
    }

    // --- Readings ---

    /** Every reading of [metric] from [from] until now, oldest first, in the units shown. */
    suspend fun readings(context: Context, metric: HealthMetric, from: Instant): List<Reading> {
        val client = HealthConnectClient.getOrCreate(context)
        val readings = mutableListOf<Reading>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(metric.record, TimeRangeFilter.between(from, Instant.now()), pageToken = page))
            response.records.forEach { readings += toReadings(metric, it) }
            page = response.pageToken
        } while (page != null)
        return readings.sortedBy { it.time }
    }

    private fun toReadings(metric: HealthMetric, record: Record): List<Reading> = when (record) {
        is HeartRateRecord -> record.samples.map { Reading(it.time, it.beatsPerMinute.toDouble()) }
        is RestingHeartRateRecord -> listOf(Reading(record.time, record.beatsPerMinute.toDouble()))
        is HeartRateVariabilityRmssdRecord -> listOf(Reading(record.time, record.heartRateVariabilityMillis))
        is WeightRecord -> listOf(Reading(record.time, if (us) record.weight.inPounds else record.weight.inKilograms))
        is BodyFatRecord -> listOf(Reading(record.time, record.percentage.value))
        is BodyTemperatureRecord -> listOf(Reading(record.time, if (us) record.temperature.inFahrenheit else record.temperature.inCelsius))
        // Skin temperature is a night's change from the person's baseline.
        is SkinTemperatureRecord -> record.deltas.map { Reading(it.time, if (us) it.delta.inCelsius * 9 / 5 else it.delta.inCelsius) }
        is OxygenSaturationRecord -> listOf(Reading(record.time, record.percentage.value))
        is RespiratoryRateRecord -> listOf(Reading(record.time, record.rate))
        is BloodPressureRecord -> listOf(Reading(record.time, record.systolic.inMillimetersOfMercury, record.diastolic.inMillimetersOfMercury))
        is BloodGlucoseRecord -> listOf(Reading(record.time, if (us) record.level.inMilligramsPerDeciliter else record.level.inMillimolesPerLiter))
        is Vo2MaxRecord -> listOf(Reading(record.time, record.vo2MillilitersPerMinuteKilogram))
        else -> emptyList<Reading>().also { Log.w(TAG, "no readings for ${metric.id}") }
    }

    /** How far back a chip looks for a reading: a day for readings, a month for occasional ones. */
    private fun lookBack(metric: HealthMetric): Duration = if (metric.shape == MetricShape.READINGS) Duration.ofDays(1) else Duration.ofDays(30)

    /** When a reading was taken, briefly: "now", "12 min ago", "08:14", "Tue", "3 Oct". */
    fun ago(context: Context, time: Instant): String {
        val minutes = Duration.between(time, Instant.now()).toMinutes()
        val day = time.atZone(zone).toLocalDate()
        val today = LocalDate.now()
        return when {
            minutes < 2 -> "now"
            minutes < 60 -> "$minutes min ago"
            day == today -> android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time.toEpochMilli()))
            day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            else -> day.format(java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")))
        }
    }

    // --- The chip ---

    /**
     * The chip's value: today's total, or the latest reading (within a day, or a month for
     * occasional measurements), with the metric's own icon. Null with nothing to show.
     */
    suspend fun value(context: Context, metric: HealthMetric, granted: Set<String>): ChipValues.Value? {
        if (metric.permission !in granted) return null
        return try {
            when (metric.shape) {
                MetricShape.TOTAL -> {
                    val total = totalToday(context, metric).takeIf { it > 0 } ?: return null
                    val text = short(metric, total)
                    ChipValues.Value(
                        text,
                        parts = listOf(ChipValues.Part(metric.icon, metric.aodIcon, text)),
                        wide = "${full(metric, total)} today",
                        detail = "${metric.label.lowercase()} today",
                        stacked = listOf(full(metric, total), "${metric.label.lowercase()} today"),
                    )
                }
                else -> {
                    val latest = readings(context, metric, Instant.now().minus(lookBack(metric))).lastOrNull() ?: return null
                    val text = short(metric, latest.value, latest.second)
                    val `when` = ago(context, latest.time)
                    ChipValues.Value(
                        text,
                        parts = listOf(ChipValues.Part(metric.icon, metric.aodIcon, text)),
                        wide = "${full(metric, latest.value, latest.second)} · $`when`",
                        detail = `when`,
                        stacked = listOf(full(metric, latest.value, latest.second), `when`),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${metric.id} read failed", e)
            null
        }
    }
}
