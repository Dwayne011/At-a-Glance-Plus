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
            views.setInt(R.id.card_body, "setBackgroundResource", reading.background)
            views.setImageViewResource(R.id.weather_icon, reading.icon)
        }
        forecast(context, views, weather, if (layout == WeatherLayout.GRID) 2 else 3, aod, layout)

        // Chips: as many as fit sit under the temperature (up to 3); the next fill the right
        // side in the variant's arrangement.
        val shown = if (layout == WeatherLayout.NONE) emptyList()
        else Chips.withValues(chips ?: Prefs.chips(context) ?: DEFAULTS.getValue(layout)).map { it.first }
        val anchors = anchorCount(context, layout, shown)
        Chips.addStyled(context, views, shown.take(anchors), anchors, Chips.ANCHOR, aod, R.id.complications_anchor)
        val rest = shown.drop(anchors)
        when (layout) {
            WeatherLayout.COLUMNS -> Chips.addStyled(context, views, rest, 2, Chips.COLUMN, aod, R.id.complications)
            WeatherLayout.SPLIT -> Chips.addStyled(context, views, rest, 2, Chips.WIDE, aod, R.id.complications)
            WeatherLayout.GRID -> {
                Chips.addStyled(context, views, rest.take(2), 2, Chips.GRID, aod, R.id.complications)
                Chips.addStyled(context, views, rest.drop(2), 2, Chips.GRID, aod, R.id.complications_2)
            }
            WeatherLayout.NONE -> views.setViewVisibility(R.id.complications, View.GONE)
        }
        Style.holdBar(views, pinned = true, aod = aod)

        val tap = weatherIntent(context)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, TARGET_ID, views, "${reading.temp} ${reading.condition}", reading.detail, reading.icon, tap, 2f)
    }

    /** Most chips under the temperature. */
    private const val MAX_ANCHORS = 3

    /** Chips on the right side of each variant. */
    private fun rightRoom(layout: WeatherLayout): Int = when (layout) {
        WeatherLayout.COLUMNS, WeatherLayout.SPLIT -> 2
        WeatherLayout.GRID -> 4
        WeatherLayout.NONE -> 0
    }

    /** Width of each variant's right side, including its margins (see tools/gen_weather_layouts.py). */
    private fun rightWidthDp(layout: WeatherLayout): Int = when (layout) {
        WeatherLayout.COLUMNS -> 6 + 102 + 4 + 72
        WeatherLayout.SPLIT -> 6 + 174
        WeatherLayout.GRID -> 6 + 82 + 6 + 96
        WeatherLayout.NONE -> 6 + 102 + 4
    }

    /**
     * Width of the row under the temperature, in dp. The card is the screen's width less the
     * launcher's margins (about 21dp each side on Pixel Launcher, measured on a 448dp-wide
     * screen), less the card's padding (16 + 14) and the right side, with 2dp kept spare.
     */
    private fun anchorRowDp(context: Context, layout: WeatherLayout): Float =
        context.resources.configuration.screenWidthDp - 42f - 30f - rightWidthDp(layout) - 2f

    /**
     * How many of [specs] (those with something to show, in order) fit under the temperature,
     * measured at the phone's text size: at least 1 when there is any, at most 3.
     */
    fun anchorCount(context: Context, layout: WeatherLayout, specs: List<ChipSpec>): Int {
        if (layout == WeatherLayout.NONE) return 0
        val room = anchorRowDp(context, layout)
        var used = 0f
        var count = 0
        for ((_, value) in Chips.withValues(specs)) {
            if (count == MAX_ANCHORS) break
            val text = value.parts.maxByOrNull { it.text.length }?.text ?: value.text
            // The gap only sits between pills, not after the last.
            val width = Chips.ANCHOR.widthDp(context, text) + if (count > 0) Chips.ANCHOR.gapDp else 0
            if (count > 0 && used + width > room) break
            used += width
            count++
        }
        return count
    }

    /** Chips the weather card has room for from [specs]: those that fit under the temperature plus the right side. */
    fun room(context: Context, layout: WeatherLayout, specs: List<ChipSpec>): Int =
        if (layout == WeatherLayout.NONE) 0 else anchorCount(context, layout, specs) + rightRoom(layout)

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
