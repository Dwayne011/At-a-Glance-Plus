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
 * The date is in the title on the home screen only; the lock screen already shows it beside the
 * clock. With no weather yet, the card says why instead of staying blank.
 */
object WeatherCard {

    const val TARGET_ID = "aagp_weather"
    private const val REQUEST_WEATHER = 201
    private const val REQUEST_SETUP = 202
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    /** The spec's default chips for each layout (8.1), used until the user picks their own. */
    private val DEFAULT_RIGHT = listOf(Complication.GMAIL, Complication.ALARM).map { ChipSpec(it) }
    private val DEFAULT_BOTTOM = listOf(Complication.GMAIL, Complication.MISSED_CALLS, Complication.ALARM, Complication.BATTERY).map { ChipSpec(it) }

    private class Reading(
        val temp: String,
        val condition: String,
        val detail: String,
        @DrawableRes val icon: Int,
        @DrawableRes val aodIcon: Int,
        @DrawableRes val background: Int,
        val peek: String?,
    )

    /** The date in the phone's locale, e.g. "Wed 28 Oct" in the UK, "Wed, Oct 28" in the US. */
    fun dateText(locale: Locale = Locale.getDefault()): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), Date()).toString()

    /** [chips]: the chips this card was given by the deck; null for the user's list or the spec's defaults. */
    fun build(context: Context, surface: String, aod: Boolean, weather: Weather?, status: String, chips: List<ChipSpec>? = null): SmartspaceTarget {
        val reading = weather?.let { live(it) }
            ?: return needed(context, aod, !WeatherRepository.hasLocationPermission(context), status)

        val showDate = surface == AtAGlanceService.SURFACE_HOME
        val title = if (showDate) "${dateText()} · ${reading.condition}" else reading.condition
        val layout = Prefs.complicationLayout(context)
        val chosen = chips ?: Prefs.chips(context)
        val shown = when (layout) {
            ComplicationLayout.RIGHT -> chosen ?: DEFAULT_RIGHT
            ComplicationLayout.BOTTOM -> chosen ?: DEFAULT_BOTTOM
            ComplicationLayout.NONE -> emptyList()
        }
        // The bottom strip is only used when there is something to put in it (spec 7.1).
        val bottom = layout == ComplicationLayout.BOTTOM && Chips.withValues(shown).isNotEmpty()
        val views = RemoteViews(context.packageName, if (bottom) R.layout.card_weather_bottom else R.layout.card_weather_right)

        views.setTextViewText(R.id.weather_temp, reading.temp)
        views.setTextViewText(R.id.weather_title, title)
        if (bottom) {
            val detail = listOfNotNull(reading.detail.ifEmpty { null }, reading.peek?.replace(" at ", " ")).joinToString(" · ")
            views.setTextViewText(R.id.weather_detail, detail)
        } else {
            views.setTextViewText(R.id.weather_detail, reading.detail)
            views.setViewVisibility(R.id.weather_detail, if (reading.detail.isEmpty()) View.GONE else View.VISIBLE)
            if (reading.peek != null) {
                views.setTextViewText(R.id.weather_peek, reading.peek)
                views.setViewVisibility(R.id.weather_peek, View.VISIBLE)
            }
        }
        if (aod) {
            Style.aodBody(views, R.id.weather_temp, R.id.weather_title, R.id.weather_detail, R.id.weather_peek)
            views.setImageViewResource(R.id.weather_icon, reading.aodIcon)
        } else {
            views.setInt(R.id.card_body, "setBackgroundResource", reading.background)
            views.setImageViewResource(R.id.weather_icon, reading.icon)
        }
        Chips.add(context, views, shown, if (bottom) ComplicationLayout.BOTTOM else ComplicationLayout.RIGHT, aod)
        Style.holdBar(views, pinned = true, aod = aod)

        val tap = weatherIntent(context)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, TARGET_ID, views, "${reading.temp} $title", reading.detail, reading.icon, tap, 2f)
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
            peek = null,
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
