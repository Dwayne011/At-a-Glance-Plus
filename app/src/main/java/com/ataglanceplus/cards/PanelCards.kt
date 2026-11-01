package com.ataglanceplus.cards

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.weather.DayForecast
import com.ataglanceplus.weather.HourForecast
import com.ataglanceplus.weather.Weather
import com.ataglanceplus.weather.WeatherLook
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The two-panel cards' shared parts (layout C, the weather card and the extra card): their
 * colours, their chips as tiles in two rows, and the hours and days of forecast in a panel.
 *
 * Colours: the Standard look takes the wallpaper's (Material You), like the calendar widget; the
 * scene looks keep the weather scene behind see-through panels, the chips on a deeper panel so they
 * always read; the always-on display draws outlines on black. Every chip is the same style.
 */
object PanelCards {

    /** A card's colours: its frame and panels, and the text and icons on them. */
    class Palette(
        @DrawableRes val frame: Int,
        @DrawableRes val mainPanel: Int,
        @DrawableRes val chipsPanel: Int,
        @DrawableRes val tile: Int,
        val text: Int,
        val secondary: Int,
        val aod: Boolean,
    )

    /** The palette for [look]: wallpaper colours (Standard), see-through over a scene, or outlines (always-on). */
    fun palette(context: Context, look: WeatherLook, aod: Boolean): Palette = when {
        aod -> Palette(0, R.drawable.weather_c_panel_aod, R.drawable.weather_c_panel_aod, R.drawable.chip_bg_aod, Style.AOD_TEXT, Style.AOD_TEXT, aod = true)
        look != WeatherLook.STANDARD -> Palette(
            R.drawable.weather_c_frame_scene, R.drawable.weather_c_panel_main_scene, R.drawable.weather_c_panel_chips_scene, R.drawable.weather_c_tile_scene,
            0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), aod = false,
        )
        else -> Palette(
            R.drawable.weather_c_frame, R.drawable.weather_c_panel_main, R.drawable.weather_c_panel_chips, R.drawable.weather_c_tile,
            context.getColor(android.R.color.system_accent1_900), context.getColor(android.R.color.system_accent1_700), aod = false,
        )
    }

    /** Puts [palette]'s frame and panel backgrounds on a two-panel card. */
    fun paint(views: RemoteViews, palette: Palette, mainPanel: Int, chipsPanel: Int) {
        views.setInt(R.id.card_body, "setBackgroundResource", palette.frame)
        views.setInt(mainPanel, "setBackgroundResource", palette.mainPanel)
        views.setInt(chipsPanel, "setBackgroundResource", palette.chipsPanel)
    }

    /** Space between two tiles in a row, and between the rows. */
    private const val GAP_DP = 5f
    /** A tile's inside: 10dp padding before, 8dp after; the icon 15dp and 6dp from the text. */
    private const val TILE_PAD_DP = 18f
    private const val ICON_DP = 21f
    private const val TEXT_SP = 13f
    private const val DETAIL_SP = 11f

    /**
     * The chips of [specs] (those with something to show) in at most [maxRows] rows of 2 slots:
     * a normal chip takes 1, an extended one ([always]: any that can be) a whole row. An extended
     * chip that does not fit is passed over for a later normal one, as on the old card. Two normal
     * chips alone get a row each, so each tile has the panel's width.
     */
    fun rows(context: Context, specs: List<ChipSpec>, maxRows: Int = 2, always: Boolean = false): List<List<Chips.Placed>> {
        val rows = mutableListOf<MutableList<Chips.Placed>>()
        val used = mutableListOf<Int>()
        for ((spec, value) in Chips.withValues(specs)) {
            val big = Chips.grows(context, spec, always)
            val need = if (big) 2 else 1
            if (rows.isEmpty() || used.last() + need > 2) {
                if (rows.size >= maxRows) {
                    if (big) continue else break
                }
                rows += mutableListOf<Chips.Placed>()
                used += 0
            }
            rows.last() += Chips.Placed(spec, value, big)
            used[used.lastIndex] = used.last() + need
        }
        if (rows.size == 1 && rows[0].size == 2 && maxRows >= 2) return listOf(listOf(rows[0][0]), listOf(rows[0][1]))
        return rows
    }

    /**
     * Fills the two chip rows ([R.id.chips_row1], [R.id.chips_row2]) of a panel [panelWidthDp]
     * wide (its 6dp padding inside). [detailed]: every tile shows its second line when it has one,
     * as on the extra card.
     */
    fun fillRows(context: Context, views: RemoteViews, rows: List<List<Chips.Placed>>, palette: Palette, panelWidthDp: Float, detailed: Boolean) {
        val inner = panelWidthDp - 12f
        listOf(R.id.chips_row1, R.id.chips_row2).forEachIndexed { i, id ->
            val row = rows.getOrNull(i)
            views.setViewVisibility(id, if (row == null) View.GONE else View.VISIBLE)
            views.removeAllViews(id)
            row ?: return@forEachIndexed
            val width = if (row.size == 2) (inner - GAP_DP) / 2 else inner
            row.forEachIndexed { j, placed ->
                val tile = tile(context, placed, palette, width, detailed || placed.big || row.size == 1)
                if (j == 0 && row.size == 2) tile.setViewLayoutMargin(R.id.chip_zone, RemoteViews.MARGIN_END, GAP_DP, TypedValue.COMPLEX_UNIT_DIP)
                views.addView(id, tile)
            }
        }
    }

    /**
     * One chip as a tile [widthDp] wide: its icon and value; with [twoLines], the whole story
     * ("509 of 10,000") and a progress line, or the value over its second line ("2 unread" over
     * "Inbox · Updates"). Text is measured at the phone's text size and shrinks to fit the tile.
     */
    private fun tile(context: Context, placed: Chips.Placed, palette: Palette, widthDp: Float, twoLines: Boolean): RemoteViews {
        val (spec, value) = placed
        val view = RemoteViews(context.packageName, R.layout.include_chip_tile)
        view.setInt(R.id.chip_pill, "setBackgroundResource", palette.tile)
        val part = value.parts.singleOrNull()
        view.setImageViewResource(R.id.chip_icon, if (palette.aod) part?.aodIcon ?: spec.kind.aodIcon else part?.icon ?: spec.kind.icon)
        value.appIcon?.let { view.setImageViewIcon(R.id.chip_icon, it) }
        view.setInt(R.id.chip_icon, "setColorFilter", palette.text)
        view.setTextColor(R.id.chip_text, palette.text)
        view.setTextColor(R.id.chip_detail, palette.secondary)
        val room = widthDp - TILE_PAD_DP - ICON_DP

        // Several values (Bluetooth devices): side by side, shortest form.
        val parts = value.parts.takeIf { it.size > 1 }
        val progress = value.progress?.takeIf { twoLines && !palette.aod }
        val main: String
        var detail: String? = null
        when {
            parts != null -> {
                main = parts.joinToString(" · ") { it.text }
                if (twoLines) detail = parts.mapNotNull { it.name }.joinToString(" · ").ifEmpty { null }
            }
            twoLines && progress != null -> main = fit(context, listOf(value.wide ?: value.text) + value.shorter + value.text, room)
            twoLines -> {
                val lines = value.lines
                main = fit(context, listOf(lines.first()) + listOfNotNull(value.compact), room)
                detail = lines.getOrNull(1) ?: value.wide?.takeIf { it != main }
            }
            else -> main = fit(context, listOf(value.text) + listOfNotNull(value.compact), room)
        }
        view.setTextViewText(R.id.chip_text, main)
        shrink(context, view, R.id.chip_text, main, TEXT_SP, room, 9f)
        if (detail != null) {
            view.setViewVisibility(R.id.chip_detail, View.VISIBLE)
            view.setTextViewText(R.id.chip_detail, detail)
            shrink(context, view, R.id.chip_detail, detail, DETAIL_SP, widthDp - TILE_PAD_DP, 8f)
        }
        if (progress != null) {
            view.setViewVisibility(R.id.chip_progress, View.VISIBLE)
            view.setProgressBar(R.id.chip_progress, 1000, (progress * 1000).toInt().coerceIn(0, 1000), false)
            view.setColorStateList(R.id.chip_progress, "setProgressTintList", ColorStateList.valueOf(palette.text))
            view.setColorStateList(R.id.chip_progress, "setProgressBackgroundTintList", ColorStateList.valueOf(palette.secondary))
        }
        view.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
        view.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + listOfNotNull(main, detail).joinToString(", "))
        return view
    }

    /** The first of [options] that fits [roomDp] at the tile's size, else the first (it will shrink). */
    private fun fit(context: Context, options: List<String>, roomDp: Float): String =
        options.firstOrNull { Chips.textWidthDp(context, it, TEXT_SP) <= roomDp } ?: options.first()

    /** Shrinks [text] in [id] from [sp] until it fits [roomDp], to [minSp] at least. */
    private fun shrink(context: Context, view: RemoteViews, id: Int, text: String, sp: Float, roomDp: Float, minSp: Float) {
        val width = Chips.textWidthDp(context, text, sp)
        if (width <= roomDp) return
        view.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, (sp * roomDp / width).coerceAtLeast(minSp))
    }

    /** The hour in the phone's style: "14:00", or "2pm". */
    fun hourText(context: Context, time: Long): String =
        DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "ha", Date(time)).toString().lowercase(Locale.getDefault())

    /** Adds [hours] as columns (time, weather, temperature) into [container]. */
    fun addHours(context: Context, views: RemoteViews, container: Int, hours: List<HourForecast>, palette: Palette) {
        views.removeAllViews(container)
        hours.forEach { hour ->
            val col = RemoteViews(context.packageName, R.layout.include_forecast_hour)
            col.setTextViewText(R.id.hour_label, hourText(context, hour.time))
            col.setImageViewResource(R.id.hour_icon, if (palette.aod) hour.condition.aodIcon(hour.isDay) else hour.condition.icon(hour.isDay))
            col.setTextViewText(R.id.hour_value, "${hour.temperature.roundToInt()}°")
            col.setTextColor(R.id.hour_label, palette.secondary)
            col.setTextColor(R.id.hour_value, palette.text)
            views.addView(container, col)
        }
    }

    /** Adds [days] as columns (weekday, weather, high and low) into [container]. */
    fun addDays(context: Context, views: RemoteViews, container: Int, days: List<DayForecast>, palette: Palette) {
        views.removeAllViews(container)
        val locale = Locale.getDefault()
        days.forEach { day ->
            val col = RemoteViews(context.packageName, R.layout.include_forecast_day)
            val name = java.time.LocalDate.ofEpochDay(day.day).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            col.setTextViewText(R.id.day_label, name)
            col.setImageViewResource(R.id.day_icon, if (palette.aod) day.condition.aodIcon(true) else day.condition.icon(true))
            col.setTextViewText(R.id.day_value, "${day.high.roundToInt()}° ${day.low.roundToInt()}°")
            col.setTextColor(R.id.day_label, palette.secondary)
            col.setTextColor(R.id.day_value, palette.text)
            views.addView(container, col)
        }
    }

    /** Today in a few words for the extra card: what the weather does next, else the condition with the day's high and low. */
    fun todayText(context: Context, weather: Weather): String {
        val now = System.currentTimeMillis()
        return weather.summary(now) { DateFormat.getTimeFormat(context).format(Date(it)) }
            ?.substringBefore(" · Wind")
            ?: listOfNotNull(
                weather.condition.label,
                if (weather.high != null && weather.low != null) "high ${weather.high.roundToInt()}°, low ${weather.low.roundToInt()}°" else null,
            ).joinToString(", ")
    }

    /** The chance of rain today (the most of the hours left or the day's figure) and the wind, for the extra card. */
    fun todayDetail(weather: Weather): String {
        val today = java.time.LocalDate.now().toEpochDay()
        val rain = weather.days.firstOrNull { it.day == today }?.rainChance
            ?: weather.nextHours(8).mapNotNull { it.rainChance }.maxOrNull()
        // Short, as it shares a narrow panel: "Rain 10% · 9 mph".
        return listOfNotNull(rain?.let { "Rain $it%" }, weather.wind?.let { "${it.roundToInt()} ${weather.windUnit}" }).joinToString(" · ")
    }

    /** Whether the weather card's look draws a scene behind the panels. */
    fun scene(context: Context, aod: Boolean): Boolean = !aod && Prefs.weatherLook(context) != WeatherLook.STANDARD
}
