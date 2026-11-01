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
import com.ataglanceplus.complications.ChipValues
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
        /** The tiles' colour, for highlights drawn with a tint (today's forecast row). */
        val highlight: Int = 0,
    )

    /** The palette for [look]: wallpaper colours (Standard), see-through over a scene, or outlines (always-on). */
    fun palette(context: Context, look: WeatherLook, aod: Boolean): Palette = when {
        aod -> Palette(0, R.drawable.weather_c_panel_aod, R.drawable.weather_c_panel_aod, R.drawable.chip_bg_aod, Style.AOD_TEXT, Style.AOD_TEXT, aod = true)
        look != WeatherLook.STANDARD -> Palette(
            R.drawable.weather_c_frame_scene, R.drawable.weather_c_panel_main_scene, R.drawable.weather_c_panel_chips_scene, R.drawable.weather_c_tile_scene,
            0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), aod = false, highlight = 0x2EFFFFFF,
        )
        else -> Palette(
            R.drawable.weather_c_frame, R.drawable.weather_c_panel_main, R.drawable.weather_c_panel_chips, R.drawable.weather_c_tile,
            context.getColor(android.R.color.system_accent1_900), context.getColor(android.R.color.system_accent1_700), aod = false,
            highlight = context.getColor(android.R.color.system_accent1_50),
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

    /**
     * How a chip is drawn: half a row (Normal), a whole row (Extended, or alone), a third (only
     * when space is short), or the whole right-hand column of the other cards (Extended there).
     */
    enum class TileSize { FULL, WIDE, COMPACT, TALL }

    class Tile(val placed: Chips.Placed, val size: TileSize) {
        val spec: ChipSpec get() = placed.spec
    }

    /**
     * The chips of [specs] (those with something to show) in at most [maxRows] rows. Each chip
     * takes the size the user chose: Normal half a row, Extended ([always]: any that can be) a
     * whole row. Only when they do not all fit does a row turn compact (three to a row), and the
     * card compacts as few chips as it can, later rows first. A chip alone in its row spans it, and
     * two Normal chips alone get a row each.
     */
    fun rows(context: Context, specs: List<ChipSpec>, maxRows: Int = 2, always: Boolean = false): List<List<Tile>> {
        val items = Chips.withValues(specs).map { (spec, value) -> Chips.Placed(spec, value, Chips.grows(context, spec, always)) }
        if (items.isEmpty()) return emptyList()
        // Which rows are compact, fewest first; among as many, the later rows.
        val masks = (0 until (1 shl maxRows)).sortedWith(compareBy<Int>({ Integer.bitCount(it) }, { -it }))
        var best: List<List<Tile>> = emptyList()
        var bestPlaced = -1
        var bestCompact = Int.MAX_VALUE
        for (mask in masks) {
            val rows = List(maxRows) { mutableListOf<Chips.Placed>() }
            var r = 0
            var used = 0
            for (item in items) {
                while (true) {
                    val compact = mask and (1 shl r) != 0
                    val need = if (!compact && item.big) 2 else 1
                    val room = if (compact) 3 else 2
                    if (used + need <= room) {
                        rows[r] += item
                        used += need
                        break
                    }
                    if (r == maxRows - 1) break // no room left: passed over, a later smaller chip may still fit
                    r++
                    used = 0
                }
            }
            val placed = rows.sumOf { it.size }
            val compacted = rows.withIndex().filter { mask and (1 shl it.index) != 0 }.sumOf { it.value.size }
            if (placed > bestPlaced || (placed == bestPlaced && compacted < bestCompact)) {
                bestPlaced = placed
                bestCompact = compacted
                best = rows.withIndex().filter { it.value.isNotEmpty() }.map { (i, row) ->
                    when {
                        mask and (1 shl i) != 0 -> row.map { Tile(it, TileSize.COMPACT) }
                        row.size == 1 -> listOf(Tile(row[0], TileSize.WIDE))
                        else -> row.map { Tile(it, TileSize.FULL) }
                    }
                }
            }
        }
        if (maxRows >= 2 && best.size == 1 && best[0].size == 2 && best[0].all { it.size == TileSize.FULL }) {
            return best[0].map { listOf(Tile(it.placed, TileSize.WIDE)) }
        }
        return best
    }

    /** The chips' colours on the other cards (alarm, timer, calendar, navigation): a light see-through tile over the card's own colour. */
    private fun glass(aod: Boolean): Palette =
        if (aod) Palette(0, 0, 0, R.drawable.chip_bg_aod, Style.AOD_TEXT, Style.AOD_TEXT, aod = true)
        else Palette(0, 0, 0, R.drawable.weather_c_tile_scene, 0xFFFFFFFF.toInt(), 0xC0FFFFFF.toInt(), aod = false)

    /** The right-hand column's width: two Normal tiles, or one Extended tile. */
    private const val COLUMN_DP = 100f
    private const val COLUMN_TALL_DP = 128f
    /**
     * Space above and below the column (and, with the column's -8dp end margin, after it): the
     * cards' corners are 24dp, so a 12dp tile corner this far in stays inside the card's curve.
     */
    private const val COLUMN_INSET_DP = 10f

    /**
     * The other cards' chips as tiles in their right-hand column [container] (see [Chips.plan]:
     * each chip at the size the user chose, two Normal or one Extended). Returns the column's width
     * in dp, 0 with no chips (the column is hidden so the card's text gets the room).
     */
    fun fillColumn(context: Context, views: RemoteViews, specs: List<ChipSpec>, aod: Boolean, container: Int = R.id.complications): Float {
        val placed = Chips.plan(context, specs, Chips.MAX_RIGHT)
        views.removeAllViews(container)
        views.setViewVisibility(container, if (placed.isEmpty()) View.GONE else View.VISIBLE)
        if (placed.isEmpty()) return 0f
        val dip = TypedValue.COMPLEX_UNIT_DIP
        val palette = glass(aod)
        val width = if (placed.any { it.big }) COLUMN_TALL_DP else COLUMN_DP
        views.setViewLayoutWidth(container, width, dip)
        val height = Prefs.cardHeightDp(context) - 2 * COLUMN_INSET_DP
        val rowHeight = (height - GAP_DP) / 2
        // A lone Normal tile is a row high, centred (the column's padding takes the rest).
        val inset = if (placed.size == 1 && !placed[0].big) COLUMN_INSET_DP + (height - rowHeight) / 2 else COLUMN_INSET_DP
        val px = (inset * context.resources.displayMetrics.density).roundToInt()
        views.setViewPadding(container, 0, px, 0, px)
        val tiles = placed.map { Tile(it, if (it.big) TileSize.TALL else TileSize.FULL) }
        val texts = HashMap<Tile, String>()
        val sizes = mutableMapOf(TileSize.FULL to FULL_SP, TileSize.TALL to WIDE_SP)
        tiles.forEach { tile ->
            val (cap, room) = if (tile.size == TileSize.TALL) WIDE_SP to width - 20f - ICON_DP else FULL_SP to width - PAD_DP - ICON_DP
            val text = valueText(context, tile.placed.value, room, cap)
            texts[tile] = text
            sizes[tile.size] = minOf(sizes.getValue(tile.size), fitSize(context, text, room, cap))
        }
        tiles.forEachIndexed { i, tile ->
            val view = tile(context, tile, texts.getValue(tile), sizes.getValue(tile.size), palette, width, rowHeight >= TWO_LINES_DP, height)
            view.setViewLayoutWidth(R.id.chip_zone, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            if (tile.size == TileSize.TALL) {
                view.setViewLayoutHeight(R.id.chip_zone, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            } else {
                view.setViewLayoutHeight(R.id.chip_zone, rowHeight, dip)
                if (i < tiles.lastIndex) view.setViewLayoutMargin(R.id.chip_zone, RemoteViews.MARGIN_BOTTOM, GAP_DP, dip)
            }
            views.addView(container, view)
        }
        return width
    }

    /** One chip a row, across it (the extra card beside the next hours). */
    fun eachAcross(rows: List<List<Tile>>): List<List<Tile>> = rows.flatten().map { listOf(Tile(it.placed, TileSize.WIDE)) }

    // The tiles' grid (see include_chip_tile): 9dp padding each side (7dp compact), a 15dp icon
    // 5dp from the value (14dp and 4dp compact), and line 2 in small text.
    private const val PAD_DP = 18f
    private const val COMPACT_PAD_DP = 14f
    private const val ICON_DP = 20f
    private const val COMPACT_ICON_DP = 18f
    private const val FULL_SP = 16f
    private const val WIDE_SP = 18f
    private const val COMPACT_SP = 14f
    private const val MIN_SP = 9f
    private const val SMALL_DP = 10.5f
    private const val END_DP = 9.5f
    /** A row shorter than this has room for line 1 only. */
    private const val TWO_LINES_DP = 33f

    /** [text]'s width in dp at [dp] (the tiles size their text in dp, so it fits their fixed lines whatever the font size). */
    private fun widthDp(context: Context, text: String, dp: Float, medium: Boolean = false): Float {
        val density = context.resources.displayMetrics.density
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp * density
            typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        }
        return paint.measureText(text) / density
    }

    /** The largest size up to [cap] (in half steps, at least [MIN_SP]) at which [text] fits [roomDp]. */
    private fun fitSize(context: Context, text: String, roomDp: Float, cap: Float): Float {
        val width = widthDp(context, text, cap, medium = true)
        if (width <= roomDp) return cap
        return (kotlin.math.floor(cap * roomDp / width * 2) / 2).coerceAtLeast(MIN_SP)
    }

    /** A short name for a chip's small line when its value says nothing more ("Steps"). */
    private fun shortLabel(kind: Complication): String = when (kind) {
        Complication.STEPS -> "Steps"
        Complication.GMAIL -> "Gmail"
        Complication.MISSED_CALLS -> "Missed calls"
        Complication.TEXTS -> "Texts"
        Complication.ALARM -> "Alarm"
        Complication.SCREEN_TIME -> "Screen time"
        Complication.SLEEP -> "Sleep"
        Complication.SUNSET -> "Sun"
        Complication.BATTERY -> "Battery"
        Complication.BUDS -> "Bluetooth"
        Complication.COUNTDOWN -> "Countdown"
        Complication.HEALTH -> "Health"
        Complication.ETORO -> "eToro"
        Complication.APP_NOTIFICATIONS -> "Notifications"
        Complication.RAIL_TICKET -> "Ticket"
    }

    /** The value a tile shows on line 1: several values (Bluetooth devices) side by side, else the value or its short form if that is what fits. */
    private fun valueText(context: Context, value: ChipValues.Value, roomDp: Float, cap: Float): String {
        value.parts.takeIf { it.size > 1 }?.let { parts -> return parts.joinToString(" · ") { it.text } }
        val options = listOf(value.text) + listOfNotNull(value.compact)
        return options.firstOrNull { widthDp(context, it, cap, medium = true) <= roomDp } ?: options.last()
    }

    /**
     * Fills the two chip rows ([R.id.chips_row1], [R.id.chips_row2]) of a panel [panelWidthDp]
     * wide (its 6dp padding inside) with [rows]. Each kind of tile shares one text size across the
     * card (the size its longest value needs), so neighbours line up; a card too short for two
     * lines a tile shows the values alone.
     */
    fun fillRows(context: Context, views: RemoteViews, rows: List<List<Tile>>, palette: Palette, panelWidthDp: Float) {
        val inner = panelWidthDp - 12f
        val rowHeight = (Prefs.cardHeightDp(context) - 12f - 12f - GAP_DP) / 2f
        val twoLines = rows.size == 1 || rowHeight >= TWO_LINES_DP
        fun widthOf(row: List<Tile>) = (inner - GAP_DP * (row.size - 1)) / row.size
        // One size per kind of tile on the card.
        val sizes = mutableMapOf(TileSize.FULL to FULL_SP, TileSize.WIDE to WIDE_SP, TileSize.COMPACT to COMPACT_SP)
        val texts = HashMap<Tile, String>()
        rows.forEach { row ->
            val width = widthOf(row)
            row.forEach { tile ->
                val (cap, room) = when (tile.size) {
                    TileSize.FULL -> FULL_SP to width - PAD_DP - ICON_DP
                    TileSize.WIDE, TileSize.TALL -> WIDE_SP to width * 0.45f - ICON_DP
                    TileSize.COMPACT -> COMPACT_SP to width - COMPACT_PAD_DP - COMPACT_ICON_DP
                }
                val text = valueText(context, tile.placed.value, room, cap)
                texts[tile] = text
                sizes[tile.size] = minOf(sizes.getValue(tile.size), fitSize(context, text, room, cap))
            }
        }
        listOf(R.id.chips_row1, R.id.chips_row2).forEachIndexed { i, id ->
            val row = rows.getOrNull(i)
            views.setViewVisibility(id, if (row == null) View.GONE else View.VISIBLE)
            views.removeAllViews(id)
            row ?: return@forEachIndexed
            val width = widthOf(row)
            row.forEachIndexed { j, tile ->
                val view = tile(context, tile, texts.getValue(tile), sizes.getValue(tile.size), palette, width, twoLines)
                if (j < row.lastIndex) view.setViewLayoutMargin(R.id.chip_zone, RemoteViews.MARGIN_END, GAP_DP, TypedValue.COMPLEX_UNIT_DIP)
                views.addView(id, view)
            }
        }
    }

    /** One chip as a tile [widthDp] wide (see [fillRows]): [main] at [valueDp] on line 1, and on line 2 its detail or bar. */
    private fun tile(
        context: Context, tile: Tile, main: String, valueDp: Float, palette: Palette, widthDp: Float, twoLines: Boolean,
        /** A tall tile's height, for which of its lines fit. */
        heightDp: Float = 0f,
    ): RemoteViews {
        val spec = tile.placed.spec
        val value = tile.placed.value
        val dip = TypedValue.COMPLEX_UNIT_DIP
        val tall = tile.size == TileSize.TALL
        val wide = tile.size == TileSize.WIDE || tall
        val compact = tile.size == TileSize.COMPACT
        val layout = when {
            tall -> R.layout.include_chip_tile_tall
            wide -> R.layout.include_chip_tile_wide
            else -> R.layout.include_chip_tile
        }
        val view = RemoteViews(context.packageName, layout)
        view.setInt(R.id.chip_pill, "setBackgroundResource", palette.tile)
        val part = value.parts.singleOrNull()
        view.setImageViewResource(R.id.chip_icon, if (palette.aod) part?.aodIcon ?: spec.kind.aodIcon else part?.icon ?: spec.kind.icon)
        value.appIcon?.let { view.setImageViewIcon(R.id.chip_icon, it) }
        view.setInt(R.id.chip_icon, "setColorFilter", palette.text)
        if (compact) {
            val px = (7 * context.resources.displayMetrics.density).roundToInt()
            view.setViewPadding(R.id.chip_pill, px, 0, px, 0)
            view.setViewLayoutWidth(R.id.chip_icon, 14f, dip)
            view.setViewLayoutHeight(R.id.chip_icon, 14f, dip)
            view.setViewLayoutMargin(R.id.chip_text, RemoteViews.MARGIN_START, 4f, dip)
        }
        view.setTextViewText(R.id.chip_text, main)
        view.setTextViewTextSize(R.id.chip_text, dip, valueDp)
        view.setTextColor(R.id.chip_text, palette.text)
        listOf(R.id.chip_detail, R.id.chip_lo, R.id.chip_hi).forEach {
            view.setTextColor(it, palette.secondary)
            view.setTextViewTextSize(it, dip, if (it == R.id.chip_detail) SMALL_DP else END_DP)
        }

        val inner = widthDp - (if (compact) COMPACT_PAD_DP else if (tall) 20f else PAD_DP)
        val valueWidth = (if (compact) COMPACT_ICON_DP else ICON_DP) + widthDp(context, main, valueDp, medium = true)
        // Wide: the title on line 1 beside the value, line 2 under it. Tall: everything under the value.
        val line2Width = if (wide && !tall) inner - valueWidth - 10f else inner
        var title: String? = null
        if (wide) {
            title = value.label ?: spec.kind.label
            view.setTextViewText(R.id.chip_title, title)
            view.setTextViewTextSize(R.id.chip_title, dip, SMALL_DP)
            view.setTextColor(R.id.chip_title, palette.text)
        }
        // Line 2: a bar when the chip has one (not on the always-on display, where it would be a
        // block of lit pixels), else a line of text.
        val detail: String? = when {
            compact -> value.label ?: shortLabel(spec.kind)
            wide -> (value.parts.takeIf { it.size > 1 }?.mapNotNull { it.name }?.joinToString(" · ")
                ?: value.detail ?: value.lines.getOrNull(1))
                ?.let { d -> title?.let { d.removePrefix("$it · ") } ?: d }?.takeIf { it != title && it.isNotEmpty() }
            else -> value.parts.takeIf { it.size > 1 }?.mapNotNull { it.name }?.joinToString(" · ")?.ifEmpty { null }
                ?: value.detail ?: value.lines.getOrNull(1) ?: value.label ?: shortLabel(spec.kind)
        }
        val drewBar = twoLines && !compact && !palette.aod && value.range?.let { bar(context, view, it, palette, line2Width) } == true
        if (!twoLines || (!drewBar && detail == null)) {
            view.setViewVisibility(R.id.chip_line2, View.GONE)
        } else if (!drewBar) {
            view.setTextViewText(R.id.chip_detail, detail)
        }
        if (tall) {
            // The tall tile's lines (value 21, then title, note and line 2 at 13 with gaps, 67dp
            // in all) with 6dp clear above and below: the note goes first, then the title, when
            // the card is too short for them all.
            val note = value.aside?.takeIf { drewBar && heightDp >= 79f }
            if (note != null) {
                view.setViewVisibility(R.id.chip_note, View.VISIBLE)
                view.setTextViewText(R.id.chip_aside, note)
                view.setTextViewTextSize(R.id.chip_aside, dip, SMALL_DP)
                view.setTextColor(R.id.chip_aside, palette.secondary)
            }
            if (heightDp < 64f) view.setViewVisibility(R.id.chip_title_row, View.GONE)
        }
        // Beside the value on a Normal tile with a bar: a short note ("02:57–07:32"), where it fits.
        val aside = value.aside?.takeIf { drewBar && tile.size == TileSize.FULL }
        if (aside != null && valueWidth + 6f + widthDp(context, aside, SMALL_DP) <= inner) {
            view.setViewVisibility(R.id.chip_aside, View.VISIBLE)
            view.setTextViewText(R.id.chip_aside, aside)
            view.setTextViewTextSize(R.id.chip_aside, dip, SMALL_DP)
            view.setTextColor(R.id.chip_aside, palette.secondary)
        }
        view.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
        view.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + listOfNotNull(main, title, detail).joinToString(", "))
        return view
    }

    /**
     * Draws [range] on line 2 of a tile [widthDp] wide there: the start label, the track with the
     * filled part, the end label. False when there is not room for a useful bar.
     */
    private fun bar(context: Context, view: RemoteViews, range: ChipValues.Range, palette: Palette, widthDp: Float): Boolean {
        val iconDp = 13f
        fun ends(end: String) = widthDp(context, range.start, END_DP) + widthDp(context, end, END_DP) +
            (if (range.startIcon != null) iconDp else 0f) + (if (range.endIcon != null) iconDp else 0f) + 10f
        var end = range.end
        var track = widthDp - ends(end)
        if (track < 30f && range.endShort != null) {
            end = range.endShort
            track = widthDp - ends(end)
        }
        if (track < 16f) return false
        view.setViewVisibility(R.id.chip_detail, View.GONE)
        view.setViewVisibility(R.id.chip_bar, View.VISIBLE)
        view.setTextViewText(R.id.chip_lo, range.start)
        view.setTextViewText(R.id.chip_hi, end)
        range.startIcon?.let { view.setImageViewResource(R.id.chip_lo_icon, it); view.setViewVisibility(R.id.chip_lo_icon, View.VISIBLE) }
        range.endIcon?.let { view.setImageViewResource(R.id.chip_hi_icon, it); view.setViewVisibility(R.id.chip_hi_icon, View.VISIBLE) }
        listOf(R.id.chip_lo_icon, R.id.chip_hi_icon).forEach { view.setInt(it, "setColorFilter", palette.secondary) }
        val dip = TypedValue.COMPLEX_UNIT_DIP
        val from = range.from.coerceIn(0f, 1f) * track
        val length = ((range.to.coerceIn(0f, 1f) - range.from.coerceIn(0f, 1f)) * track).coerceAtLeast(4f).coerceAtMost(track)
        view.setColorStateList(R.id.chip_track, "setBackgroundTintList", ColorStateList.valueOf((palette.secondary and 0x00FFFFFF) or 0x40000000))
        view.setColorStateList(R.id.chip_fill, "setImageTintList", ColorStateList.valueOf(palette.text))
        view.setViewLayoutWidth(R.id.chip_fill, length, dip)
        view.setViewLayoutMargin(R.id.chip_fill, RemoteViews.MARGIN_START, from.coerceAtMost(track - length), dip)
        return true
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

    /**
     * The days as rows into [container] (a panel [panelWidthDp] wide, 6dp padding inside): each
     * with its day, weather, chance of rain (when 10% or more), low, a bar from the low to the high
     * on one scale shared by all the rows, and the high. [today], if given, comes first, highlighted.
     */
    fun addRows(context: Context, views: RemoteViews, container: Int, today: DayForecast?, days: List<DayForecast>, palette: Palette, panelWidthDp: Float) {
        views.removeAllViews(container)
        val all = listOfNotNull(today) + days
        if (all.isEmpty()) return
        val low = all.minOf { it.low }
        val high = all.maxOf { it.high }.let { if (it - low < 1) low + 1 else it }
        // The track's width: the panel less its padding (12), the row's (12), and the fixed parts
        // (day 40, icon 2 + 14, rain 6 + 40, low 24, the track's margins 12, high 26).
        val track = (panelWidthDp - 12f - 12f - (40 + 16 + 46 + 24 + 12 + 26)).coerceAtLeast(16f)
        val locale = Locale.getDefault()
        val dip = TypedValue.COMPLEX_UNIT_DIP
        all.forEachIndexed { i, day ->
            val row = RemoteViews(context.packageName, R.layout.include_forecast_row)
            val isToday = today != null && i == 0
            val name = if (isToday) "Today" else java.time.LocalDate.ofEpochDay(day.day).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            row.setTextViewText(R.id.row_day, name)
            row.setImageViewResource(R.id.row_icon, if (palette.aod) day.condition.aodIcon(true) else day.condition.icon(true))
            row.setTextViewText(R.id.row_rain, day.rainChance?.takeIf { it >= 10 }?.let { "\uD83D\uDCA7$it%" }.orEmpty())
            row.setTextViewText(R.id.row_low, "${day.low.roundToInt()}°")
            row.setTextViewText(R.id.row_high, "${day.high.roundToInt()}°")
            listOf(R.id.row_day, R.id.row_rain, R.id.row_high).forEach { row.setTextColor(it, palette.text) }
            row.setTextColor(R.id.row_low, palette.secondary)
            // The bar: the track in a faint text colour, the fill from the low to the high.
            val start = ((day.low - low) / (high - low) * track).toFloat()
            val length = ((day.high - day.low) / (high - low) * track).toFloat().coerceAtLeast(5f)
            row.setColorStateList(R.id.row_track, "setBackgroundTintList", ColorStateList.valueOf((palette.secondary and 0x00FFFFFF) or 0x40000000))
            row.setColorStateList(R.id.row_fill, "setImageTintList", ColorStateList.valueOf(palette.text))
            row.setViewLayoutWidth(R.id.row_fill, length, dip)
            row.setViewLayoutMargin(R.id.row_fill, RemoteViews.MARGIN_START, start.coerceAtMost(track - length), dip)
            if (isToday && !palette.aod) {
                row.setInt(R.id.row_root, "setBackgroundResource", R.drawable.weather_c_row_now)
                row.setColorStateList(R.id.row_root, "setBackgroundTintList", ColorStateList.valueOf(palette.highlight))
            }
            row.setContentDescription(R.id.row_root, "$name: ${day.condition.label}, low ${day.low.roundToInt()}°, high ${day.high.roundToInt()}°" +
                (day.rainChance?.let { ", rain $it%" } ?: ""))
            views.addView(container, row)
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

    /** Whether the weather card's look draws a scene behind the panels. */
    fun scene(context: Context, aod: Boolean): Boolean = !aod && Prefs.weatherLook(context) != WeatherLook.STANDARD
}
