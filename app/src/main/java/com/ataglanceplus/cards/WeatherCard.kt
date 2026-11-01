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
 * Two panels (layout C): the weather on the left (icon, temperature, the condition with the day's
 * high and low, and the date on the home screen; the lock screen shows the date by the clock, so
 * there it is how warm it feels), and the chips on the right as tiles in two rows; with no chips,
 * the next hours. Colours follow the wallpaper (Standard) or sit over a weather scene (the scene
 * looks). With no weather yet, the card says why instead of staying blank.
 */
object WeatherCard {

    const val TARGET_ID = "aagp_weather"
    private const val REQUEST_WEATHER = 201
    private const val REQUEST_SETUP = 202
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    /** The spec's example chips, used until the user picks their own. */
    private val DEFAULTS = listOf(Complication.STEPS, Complication.GMAIL, Complication.BATTERY).map { ChipSpec(it) }

    class Reading(
        val temp: String,
        val condition: String,
        val detail: String,
        @DrawableRes val icon: Int,
        @DrawableRes val aodIcon: Int,
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
        return finish(context, views(context, surface, aod, weather, reading, chips), reading, aod)
    }

    /** The card's views with the weather [reading] and its chips, before they are wrapped in a target. */
    internal fun views(context: Context, surface: String, aod: Boolean, weather: Weather, reading: Reading, chips: List<ChipSpec>?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.card_weather_material)
        val palette = surface(context, views, reading, aod)
        PanelCards.paint(views, palette, R.id.weather_panel, R.id.chips_panel)
        views.setImageViewResource(R.id.weather_icon, if (aod) reading.aodIcon else reading.icon)
        views.setTextViewText(R.id.weather_temp, reading.temp)
        views.setTextViewText(R.id.weather_title, listOfNotNull(reading.condition, highLow(weather)).joinToString(" · "))
        // Line 2: the date on the home screen; the lock screen shows it by the clock, so there how warm it feels.
        val second = if (surface == AtAGlanceService.SURFACE_HOME) dateText() else weather.feelsLike?.let { "Feels ${it.roundToInt()}°" }
        views.setTextViewText(R.id.weather_detail, second.orEmpty())
        views.setViewVisibility(R.id.weather_detail, if (second == null) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.weather_temp, palette.text)
        views.setTextColor(R.id.weather_title, palette.text)
        views.setTextColor(R.id.weather_detail, palette.secondary)

        val shown = Chips.withValues(chips ?: Prefs.chips(context) ?: DEFAULTS).map { it.first }
        val rows = PanelCards.rows(context, shown)
        if (rows.isEmpty()) {
            // No chips: the next hours fill the panel.
            views.setViewVisibility(R.id.chips_row1, View.GONE)
            views.setViewVisibility(R.id.chips_row2, View.GONE)
            val hours = weather.nextHours(4)
            views.setViewVisibility(R.id.chips_hours, if (hours.isEmpty()) View.GONE else View.VISIBLE)
            PanelCards.addHours(context, views, R.id.chips_hours, hours, palette)
            if (hours.isEmpty()) views.setViewVisibility(R.id.chips_panel, View.GONE)
        } else {
            PanelCards.fillRows(context, views, rows, palette, chipsPanelDp(context))
        }
        return views
    }

    /** "H12° L9°", or null without them. */
    private fun highLow(weather: Weather): String? =
        if (weather.high != null && weather.low != null) "H${weather.high.roundToInt()}° L${weather.low.roundToInt()}°" else null

    private fun finish(context: Context, views: RemoteViews, reading: Reading, aod: Boolean): SmartspaceTarget {
        Style.holdBar(views, pinned = true, aod = aod)
        val tap = weatherIntent(context)
        views.setOnClickPendingIntent(R.id.card_root, tap)
        return Style.target(context, TARGET_ID, views, "${reading.temp} ${reading.condition}", reading.detail, reading.icon, tap, 2f)
    }

    /**
     * The card's surface, behind the panels, and its palette: the wallpaper's colours (Standard),
     * or the scene for the conditions and time of day with the weather over it, still or moving
     * (the moving layers are in the layout, hidden; those needed are shown). Used by the extra card too.
     */
    fun surface(context: Context, views: RemoteViews, reading: Reading, aod: Boolean): PanelCards.Palette {
        val look = Prefs.weatherLook(context)
        val palette = PanelCards.palette(context, look, aod)
        if (aod || look == WeatherLook.STANDARD) {
            views.setViewVisibility(R.id.weather_scene, View.GONE)
            return palette
        }
        views.setViewVisibility(R.id.weather_scene, View.VISIBLE)
        views.setImageViewResource(R.id.scene_sky, reading.conditions.sceneSky(reading.isDay))
        val layers = reading.conditions.sceneLayers(reading.isDay)
        if (look == WeatherLook.ANIMATED) {
            layers.forEach { views.setViewVisibility(it.animated, View.VISIBLE) }
        } else {
            val stills = layers.mapNotNull { it.still }
            listOf(R.id.scene_a, R.id.scene_b, R.id.scene_c).forEachIndexed { i, id ->
                stills.getOrNull(i)?.let {
                    views.setImageViewResource(id, it)
                    views.setViewVisibility(id, View.VISIBLE)
                }
            }
        }
        return palette
    }

    /** The screen's width in dp, from the display's own pixels. */
    private fun screenDp(context: Context): Float {
        val metrics = context.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
    }

    /**
     * The card's width in its own dp. Pixel Launcher leaves about 19dp each side of the card and
     * then shrinks the whole card to fit its At a Glance slot (about 78/88), so in the card's own
     * units it is wider than it looks. The launcher does not report the real width.
     */
    fun cardWidthDp(context: Context): Float = (screenDp(context) - LAUNCHER_MARGINS_DP) / LAUNCHER_SCALE

    private const val LAUNCHER_MARGINS_DP = 38f
    private const val LAUNCHER_SCALE = 78f / 88f

    /** Inside the body's 6dp padding, less the 6dp between the panels. */
    private fun panelsDp(context: Context): Float = cardWidthDp(context) - 12f - 6f

    /** The chips panel's width: 63 of the 100 shares. */
    fun chipsPanelDp(context: Context): Float = panelsDp(context) * 0.63f

    /** The extra card's right panel: 66 shares beside the left panel, or all of it alone. */
    fun extraRightDp(context: Context, alone: Boolean): Float = if (alone) cardWidthDp(context) - 12f else panelsDp(context) * 0.66f

    /** The numbers behind the fitting, for the app's Advanced section, so a wrong guess can be seen and fixed. */
    fun fitReport(context: Context): String {
        val chosen = Prefs.chips(context).orEmpty()
        val rows = PanelCards.rows(context, chosen)
        return "Fitting: screen ${screenDp(context).toInt()}dp, card ${cardWidthDp(context).toInt()}dp before the launcher shrinks it, " +
            "chips panel ${chipsPanelDp(context).toInt()}dp: ${rows.joinToString(" / ") { row -> row.joinToString(", ") { it.spec.kind.id + " (" + it.size.name.lowercase() + ")" } }.ifEmpty { "no chips" }}."
    }

    /** The chips of [specs] the weather card shows, in their order in [specs]. */
    fun chipsTaken(context: Context, specs: List<ChipSpec>): List<ChipSpec> {
        val taken = PanelCards.rows(context, specs).flatten().map { it.spec }.toSet()
        return specs.filter { it in taken }
    }

    fun live(weather: Weather): Reading {
        val condition = weather.condition
        val isDay = weather.isDayAt()
        return Reading(
            temp = "${weather.temperature.roundToInt()}°",
            condition = condition.label,
            detail = weather.detailText,
            icon = condition.icon(isDay),
            aodIcon = condition.aodIcon(isDay),
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
    fun weatherIntent(context: Context): PendingIntent =
        if (context.packageManager.getLaunchIntentForPackage(PIXEL_WEATHER) != null) {
            Style.gatedApp(context, PIXEL_WEATHER, REQUEST_WEATHER)
        } else {
            setupIntent(context)
        }

    /** Opens this app (where weather is set up), behind the unlock gate. */
    private fun setupIntent(context: Context): PendingIntent =
        Style.gated(context, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), REQUEST_SETUP)
}
