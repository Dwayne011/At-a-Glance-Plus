package com.ataglanceplus.cards

import android.app.PendingIntent
import android.app.smartspace.SmartspaceTarget
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.weather.Weather
import com.ataglanceplus.weather.WeatherCondition
import com.ataglanceplus.weather.WeatherLook
import com.ataglanceplus.weather.WeatherRepository
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather card (spec 8.1): the pinned base card that replaces Google's date and weather line.
 *
 * Laid out per the A+C hybrid spec: current weather and a chip on the left, and on the right one
 * of three arrangements of the next hours' forecast and chips. The date shows on the home screen
 * only; the lock screen already shows it beside the clock. With no weather yet, the card says
 * why instead of staying blank.
 */
object WeatherCard {

    const val TARGET_ID = "aagp_weather"
    private const val REQUEST_WEATHER = 201
    private const val REQUEST_SETUP = 202
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    /** The hybrid spec's example chips for each variant, used until the user picks their own. */
    private val DEFAULTS = mapOf(
        WeatherLayout.COLUMNS to listOf(Complication.STEPS, Complication.BATTERY, Complication.ALARM),
        WeatherLayout.SPLIT to listOf(Complication.STEPS, Complication.SCREEN_TIME, Complication.ALARM),
        WeatherLayout.GRID to listOf(Complication.STEPS, Complication.MISSED_CALLS, Complication.BATTERY, Complication.SCREEN_TIME, Complication.ALARM),
    ).mapValues { (_, kinds) -> kinds.map { ChipSpec(it) } }

    private class Reading(
        val temp: String,
        val condition: String,
        val detail: String,
        @DrawableRes val icon: Int,
        @DrawableRes val aodIcon: Int,
        @DrawableRes val background: Int,
        val conditions: WeatherCondition,
        val isDay: Boolean,
    )

    /** The date in the phone's locale, e.g. "Wed 28 Oct" in the UK, "Wed, Oct 28" in the US. */
    fun dateText(locale: Locale = Locale.getDefault()): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), Date()).toString()

    /** [chips]: the chips this card was given by the deck; null for the user's list or the spec's defaults. */
    fun build(context: Context, surface: String, aod: Boolean, weather: Weather?, status: String, chips: List<ChipSpec>? = null): SmartspaceTarget {
        val reading = weather?.let { live(it) }
            ?: return needed(context, aod, !WeatherRepository.hasLocationPermission(context), status)

        // Line 1 is the date on the home screen; the lock screen shows the date beside the clock,
        // so there it is the condition instead.
        val title = if (surface == AtAGlanceService.SURFACE_HOME) dateText() else reading.condition
        val layout = Prefs.weatherLayout(context)
        val views = RemoteViews(
            context.packageName,
            when (layout) {
                WeatherLayout.SPLIT -> R.layout.card_weather_split
                WeatherLayout.GRID -> R.layout.card_weather_grid
                else -> R.layout.card_weather_columns
            }
        )
        views.setTextViewText(R.id.weather_temp, reading.temp)
        views.setTextViewText(R.id.weather_title, title)
        // Compact high/low/feels so it fits beside the temperature.
        val detail = weather.compactDetail
        views.setTextViewText(R.id.weather_detail, detail)
        views.setViewVisibility(R.id.weather_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
        if (aod) {
            Style.aodBody(views, R.id.weather_temp, R.id.weather_title, R.id.weather_detail)
            views.setImageViewResource(R.id.weather_icon, reading.aodIcon)
        } else {
            views.setImageViewResource(R.id.weather_icon, reading.icon)
            val look = Prefs.weatherLook(context)
            if (look == WeatherLook.STANDARD) {
                views.setInt(R.id.card_body, "setBackgroundResource", reading.background)
            } else {
                scene(context, views, reading, animated = look == WeatherLook.ANIMATED)
            }
        }
        forecast(context, views, weather, if (layout == WeatherLayout.GRID) 2 else 3, aod, layout)

        // Chips: as many as fit sit under the temperature (up to 3 slots); the others fill the
        // right side in the variant's arrangement.
        val shown = if (layout == WeatherLayout.NONE) emptyList()
        else Chips.withValues(chips ?: Prefs.chips(context) ?: DEFAULTS.getValue(layout)).map { it.first }
        val anchors = anchorPlan(context, layout, shown)
        Chips.addPlaced(context, views, anchors, Chips.ANCHOR, aod, R.id.complications_anchor)
        val placedUnder = anchors.map { it.spec }.toSet()
        val right = rightPlan(context, layout, shown.filter { it !in placedUnder })
        when (layout) {
            WeatherLayout.COLUMNS -> Chips.addPlaced(context, views, right[0], Chips.COLUMN, aod, R.id.complications)
            WeatherLayout.SPLIT -> Chips.addPlaced(context, views, right[0], Chips.WIDE, aod, R.id.complications)
            WeatherLayout.GRID -> {
                Chips.addPlaced(context, views, right[0], Chips.GRID, aod, R.id.complications)
                Chips.addPlaced(context, views, right[1], Chips.GRID, aod, R.id.complications_2)
            }
            WeatherLayout.NONE -> views.setViewVisibility(R.id.complications, View.GONE)
        }
        Style.holdBar(views, pinned = true, aod = aod)

        val tap = weatherIntent(context)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, TARGET_ID, views, "${reading.temp} ${reading.condition}", reading.detail, reading.icon, tap, 2f)
    }

    /**
     * A scene behind the card: the sky for the conditions and time of day, and the weather over
     * it, still or moving. Moving layers are added only when used, so a still card loads none.
     */
    private fun scene(context: Context, views: RemoteViews, reading: Reading, animated: Boolean) {
        views.setViewVisibility(R.id.weather_scene, View.VISIBLE)
        views.setInt(R.id.card_body, "setBackgroundResource", R.drawable.card_bg_scene_border)
        views.setImageViewResource(R.id.scene_sky, reading.conditions.sceneSky(reading.isDay))
        val layers = reading.conditions.sceneLayers(reading.isDay)
        if (animated) {
            layers.forEach { views.addView(R.id.scene_anim, RemoteViews(context.packageName, it.animated)) }
        } else {
            val stills = layers.mapNotNull { it.still }
            listOf(R.id.scene_a, R.id.scene_b, R.id.scene_c).forEachIndexed { i, id ->
                stills.getOrNull(i)?.let {
                    views.setImageViewResource(id, it)
                    views.setViewVisibility(id, View.VISIBLE)
                }
            }
        }
    }

    /** Most chips under the temperature. */
    private const val MAX_ANCHORS = 3

    /** Width of each variant's right side, including its margins (see tools/gen_weather_layouts.py). */
    private fun rightWidthDp(layout: WeatherLayout): Int = when (layout) {
        WeatherLayout.COLUMNS -> 6 + 102 + 4 + 72
        WeatherLayout.SPLIT -> 6 + 174
        WeatherLayout.GRID -> 6 + 82 + 6 + 96
        WeatherLayout.NONE -> 6 + 102 + 4
    }

    /** The screen's width in dp, from the display's own pixels. */
    private fun screenDp(context: Context): Float {
        val metrics = context.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
    }

    /**
     * Width of the row under the temperature, in the card's own dp.
     *
     * Pixel Launcher leaves about 19dp each side of the card, and then shrinks the whole card to
     * fit its At a Glance slot: the 88dp-tall card is drawn about 78dp tall (measured on a Pixel,
     * 411dp screen). Everything inside is laid out before that shrinking, so in the card's own
     * units it is wider than it looks: the visible width divided by 78/88. From that come the
     * card's padding (16 + 14) and the right side. The launcher does not report the real width.
     */
    private fun anchorRowDp(context: Context, layout: WeatherLayout): Float =
        cardWidthDp(context) - 30f - rightWidthDp(layout)

    /** The card's width in its own dp (see [anchorRowDp]). */
    fun cardWidthDp(context: Context): Float = (screenDp(context) - LAUNCHER_MARGINS_DP) / LAUNCHER_SCALE

    private const val LAUNCHER_MARGINS_DP = 38f
    private const val LAUNCHER_SCALE = 78f / 88f

    /** The numbers behind the fitting, for the Chips tab, so a wrong guess can be seen and fixed. */
    fun fitReport(context: Context): String {
        val layout = Prefs.weatherLayout(context)
        val pills = Chips.withValues(Prefs.chips(context).orEmpty()).joinToString(", ") { (spec, value) ->
            val text = value.parts.maxByOrNull { it.text.length }?.text ?: value.text
            "${spec.kind.id} ${Chips.ANCHOR.widthDp(context, text).toInt()}"
        }
        return "Fitting: screen ${screenDp(context).toInt()}dp, card ${cardWidthDp(context).toInt()}dp before the launcher shrinks it, " +
            "room under the temperature ${anchorRowDp(context, layout).toInt()}dp " +
            "(${layout.label}), fits ${anchorPlan(context, layout, Prefs.chips(context).orEmpty()).size}. Pill widths: ${pills.ifEmpty { "none" }}."
    }

    /**
     * Which of [specs] (those with something to show, in order) go under the temperature,
     * measured at the phone's text size: up to 3 slots, at least 1 chip when there is any. An
     * extended chip is wide (2 slots), and only goes here when its whole line fits; otherwise it
     * waits for a spot where it can be extended (the right side, another card, or the extra chips
     * card) rather than being shrunk. A chip that does not fit the space left is passed over for
     * a later, smaller one, so no room is wasted; the ones passed over go to the right side.
     */
    fun anchorPlan(context: Context, layout: WeatherLayout, specs: List<ChipSpec>): List<Chips.Placed> {
        if (layout == WeatherLayout.NONE) return emptyList()
        val room = anchorRowDp(context, layout)
        val placed = mutableListOf<Chips.Placed>()
        var used = 0f
        var slots = 0
        for ((spec, value) in Chips.withValues(specs)) {
            if (slots >= MAX_ANCHORS) break
            // The gap only sits between pills, not after the last.
            val gap = if (placed.isEmpty()) 0 else Chips.ANCHOR.gapDp
            val text = value.parts.maxByOrNull { it.text.length }?.text ?: value.text
            val standard = Chips.ANCHOR.widthDp(context, text) + gap
            val wide = Chips.ANCHOR.bigWidthDp(context, value) + gap
            val extended = Chips.grows(context, spec)
            val big = extended && slots + 2 <= MAX_ANCHORS && used + wide <= room
            // Never shrunk, except as the first chip, so the row is not left empty.
            if (extended && !big && placed.isNotEmpty()) continue
            val width = if (big) wide else standard
            if (placed.isNotEmpty() && used + width > room) continue
            used += width
            slots += if (big) 2 else 1
            placed += Chips.Placed(spec, value, big)
        }
        return placed
    }

    /** The right side's chips from [rest]: 2 slots, or 2 rows of 2 in the grid. */
    private fun rightPlan(context: Context, layout: WeatherLayout, rest: List<ChipSpec>): List<List<Chips.Placed>> = when (layout) {
        WeatherLayout.COLUMNS -> listOf(Chips.plan(context, rest, 2, Chips.COLUMN))
        WeatherLayout.SPLIT -> listOf(Chips.plan(context, rest, 2, Chips.WIDE))
        WeatherLayout.GRID -> {
            // A wide chip fills a row.
            val first = Chips.plan(context, rest, 2, Chips.GRID)
            listOf(first, Chips.plan(context, rest.drop(first.size), 2, Chips.GRID))
        }
        WeatherLayout.NONE -> emptyList()
    }

    /** The chips of [specs] the weather card shows, in their order in [specs]. */
    fun chipsTaken(context: Context, layout: WeatherLayout, specs: List<ChipSpec>): List<ChipSpec> {
        val anchors = anchorPlan(context, layout, specs).map { it.spec }.toSet()
        val rest = Chips.withValues(specs).map { it.first }.filter { it !in anchors }
        val right = rightPlan(context, layout, rest).flatten().map { it.spec }.toSet()
        return specs.filter { it in anchors || it in right }
    }

    /** The next hours (3, or 2 in the grid variant); hidden until a reading with hourly data arrives. */
    private fun forecast(context: Context, views: RemoteViews, weather: Weather, count: Int, aod: Boolean, layout: WeatherLayout) {
        val hours = weather.nextHours(count)
        if (hours.size < count) {
            views.setViewVisibility(R.id.forecast, View.GONE)
            if (layout == WeatherLayout.SPLIT) views.setViewVisibility(R.id.forecast_rule, View.GONE)
            return
        }
        val ids = listOf(
            Triple(R.id.f1_time, R.id.f1_icon, R.id.f1_temp),
            Triple(R.id.f2_time, R.id.f2_icon, R.id.f2_temp),
            Triple(R.id.f3_time, R.id.f3_icon, R.id.f3_temp),
        )
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "ha"
        hours.forEachIndexed { i, hour ->
            val (time, icon, temp) = ids[i]
            views.setTextViewText(time, DateFormat.format(pattern, Date(hour.time)).toString().lowercase(Locale.getDefault()))
            views.setTextViewText(temp, "${hour.temperature.roundToInt()}°")
            views.setImageViewResource(icon, if (aod) hour.condition.aodIcon(hour.isDay) else hour.condition.icon(hour.isDay))
            if (aod) {
                views.setTextColor(time, Style.AOD_TEXT)
                views.setTextColor(temp, Style.AOD_TEXT)
            }
        }
        if (aod && layout == WeatherLayout.GRID) views.setInt(R.id.forecast, "setBackgroundResource", R.drawable.aod_outline_r14)
    }

    private fun live(weather: Weather): Reading {
        val condition = weather.condition
        val isDay = weather.isDayAt()
        return Reading(
            temp = "${weather.temperature.roundToInt()}°",
            condition = condition.label,
            detail = weather.detailText,
            icon = condition.icon(isDay),
            aodIcon = condition.aodIcon(isDay),
            background = condition.background(isDay),
            conditions = condition,
            isDay = isDay,
        )
    }

    /** Empty state: location permission missing, or still waiting for the first reading. */
    private fun needed(context: Context, aod: Boolean, permissionMissing: Boolean, status: String): SmartspaceTarget {
        val title = if (permissionMissing) "Weather: location needed" else "Weather"
        val detail = if (permissionMissing) "Tap to enable location access" else status.ifEmpty { "Getting the forecast…" }
        val views = RemoteViews(context.packageName, R.layout.card_weather_needed)
        views.setTextViewText(R.id.needed_title, title)
        views.setTextViewText(R.id.needed_detail, detail)
        if (aod) {
            Style.aodBody(views, R.id.needed_title, R.id.needed_detail)
            views.setInt(R.id.needed_badge, "setBackgroundResource", R.drawable.aod_outline_oval)
            views.setImageViewResource(R.id.needed_icon, R.drawable.ic_weather_location_off_aod)
        }
        Style.holdBar(views, pinned = true, aod = aod)
        val tap = setupIntent(context)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, TARGET_ID, views, title, detail, R.drawable.ic_weather_location_off, tap, 2f)
    }

    /** Pixel Weather if installed, otherwise this app; always behind the unlock gate. */
    private fun weatherIntent(context: Context): PendingIntent =
        if (context.packageManager.getLaunchIntentForPackage(PIXEL_WEATHER) != null) {
            Style.gatedApp(context, PIXEL_WEATHER, REQUEST_WEATHER)
        } else {
            setupIntent(context)
        }

    /** Opens this app (where weather is set up), behind the unlock gate. */
    private fun setupIntent(context: Context): PendingIntent =
        Style.gated(context, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), REQUEST_SETUP)
}
