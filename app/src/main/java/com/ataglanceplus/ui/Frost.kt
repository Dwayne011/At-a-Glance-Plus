package com.ataglanceplus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The settings' frost look, after Pixel Launcher Plus's settings (its Frost kit): drawn over the
 * wallpaper, see-through group cards with an accent label, rows with 38 dp icon tiles, the
 * launcher's switch, choice rows and a menu of sections with tinted tiles. Unlike the launcher's
 * fixed dark palette, the colours follow the phone: Material You's accents, light or dark with
 * the system theme.
 */

/** The look's colours, for the phone's theme now. */
@Immutable
internal data class FrostColors(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    /** The cards' see-through fill. */
    val fill: Color,
    val border: Color,
    val divider: Color,
    /** Over the wallpaper, under everything. */
    val veil: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
    /** A row's icon tile. */
    val tile: Color,
    val trackOff: Color,
    val thumbOff: Color,
    /** The menu's section tiles: fill and ink, by [Tone]. */
    val tones: Map<Tone, Pair<Color, Color>>,
) {
    val selected: Color get() = accent.copy(alpha = if (dark) 0.18f else 0.14f)
    val selectedBorder: Color get() = accent.copy(alpha = 0.40f)
    val errorTile = Color(0xFFFFD9DD)
    val errorInk = Color(0xFF3A1410)
}

/** A tint per kind of section, so the menu reads at a glance. */
internal enum class Tone { PRIMARY, SECONDARY, TERTIARY, NEUTRAL }

internal val LocalFrost = staticCompositionLocalOf<FrostColors> { error("No FrostTheme") }

/** The frost colours from the wallpaper (Material You), light or dark with the phone. */
@Composable
internal fun frostColors(dark: Boolean): FrostColors {
    @Composable fun c(id: Int) = colorResource(id)
    return if (dark) FrostColors(
        dark = true,
        accent = c(android.R.color.system_accent1_200), onAccent = c(android.R.color.system_accent1_800),
        fill = c(android.R.color.system_neutral1_900).copy(alpha = 0.80f),
        border = Color.White.copy(alpha = 0.10f), divider = Color.White.copy(alpha = 0.06f),
        veil = Color(0xFF06080F).copy(alpha = 0.35f),
        text = c(android.R.color.system_neutral1_50), textSecondary = c(android.R.color.system_neutral1_100),
        textTertiary = c(android.R.color.system_neutral2_300), textMuted = c(android.R.color.system_neutral2_400),
        tile = Color.White.copy(alpha = 0.10f), trackOff = Color.White.copy(alpha = 0.18f), thumbOff = c(android.R.color.system_neutral2_300),
        tones = mapOf(
            Tone.PRIMARY to (c(android.R.color.system_accent1_100).copy(alpha = 0.16f) to c(android.R.color.system_accent1_100)),
            Tone.SECONDARY to (c(android.R.color.system_accent2_100).copy(alpha = 0.16f) to c(android.R.color.system_accent2_100)),
            Tone.TERTIARY to (c(android.R.color.system_accent3_100).copy(alpha = 0.16f) to c(android.R.color.system_accent3_100)),
            Tone.NEUTRAL to (Color.White.copy(alpha = 0.10f) to c(android.R.color.system_neutral1_100)),
        ),
    ) else FrostColors(
        dark = false,
        accent = c(android.R.color.system_accent1_600), onAccent = Color.White,
        fill = c(android.R.color.system_neutral1_10).copy(alpha = 0.86f),
        border = Color.Black.copy(alpha = 0.08f), divider = Color.Black.copy(alpha = 0.07f),
        veil = Color.White.copy(alpha = 0.30f),
        text = c(android.R.color.system_neutral1_900), textSecondary = c(android.R.color.system_neutral1_800),
        textTertiary = c(android.R.color.system_neutral2_600), textMuted = c(android.R.color.system_neutral2_500),
        tile = c(android.R.color.system_accent1_100).copy(alpha = 0.55f), trackOff = Color.Black.copy(alpha = 0.16f), thumbOff = Color.White,
        tones = mapOf(
            Tone.PRIMARY to (c(android.R.color.system_accent1_100) to c(android.R.color.system_accent1_700)),
            Tone.SECONDARY to (c(android.R.color.system_accent2_100) to c(android.R.color.system_accent2_700)),
            Tone.TERTIARY to (c(android.R.color.system_accent3_100) to c(android.R.color.system_accent3_700)),
            Tone.NEUTRAL to (Color.Black.copy(alpha = 0.06f) to c(android.R.color.system_neutral2_700)),
        ),
    )
}

/** Google Sans where the phone has it (Pixel), the system sans elsewhere. */
internal val FrostFont: FontFamily = runCatching {
    FontFamily(
        Font(DeviceFontFamilyName("google-sans"), FontWeight.Normal),
        Font(DeviceFontFamilyName("google-sans-medium"), FontWeight.SemiBold),
        Font(DeviceFontFamilyName("google-sans-medium"), FontWeight.Bold),
        Font(DeviceFontFamilyName("google-sans-bold"), FontWeight.ExtraBold),
    )
}.getOrDefault(FontFamily.Default)

internal object FrostType {
    val title = TextStyle(fontFamily = FrostFont, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
    val bodyStrong = TextStyle(fontFamily = FrostFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
    val body = TextStyle(fontFamily = FrostFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = FrostFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
    val overline = TextStyle(fontFamily = FrostFont, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 14.sp, letterSpacing = 0.06.em)
}

internal val FrostInner = RoundedCornerShape(18.dp)

/**
 * The settings theme: the frost colours, and Material You (light or dark) for dialogs, buttons
 * and fields, all following the phone's theme.
 */
@Composable
internal fun FrostTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val frost = frostColors(dark)
    CompositionLocalProvider(LocalFrost provides frost) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides frost.text, content = content)
        }
    }
}

/** The launcher's stroke icons on a 24-unit grid (2 dp lines, round caps), and a few more. */
internal object FrostIcons {
    const val Search = "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M20 20l-4-4"
    const val Calendar = "M5 6h14v14H5z M5 10h14 M9 4v4 M15 4v4"
    const val Flag = "M5 21V4 M5 4h12l-2 4 2 4H5"
    const val Ball = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7l4 3-1.5 4.5h-5L8 10z"
    const val ChevronRight = "M9 6l6 6-6 6"
    const val ChevronLeft = "M15 6l-6 6 6 6"
    const val Close = "M6 6l12 12 M18 6L6 18"
    const val Lines = "M4 7h16 M4 12h12 M4 17h8"
    const val Sliders = "M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4"
    const val Apps = "M4 5h4v4H4z M10 5h4v4h-4z M16 5h4v4h-4z M4 11h4v4H4z M10 11h4v4h-4z M16 11h4v4h-4z M4 17h4v2H4z"
    const val Glance = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5l3 2"
    const val Down = "M12 4v14 M6 12l6 6 6-6"
    const val Phone = "M8 3h8v18H8z M11 18h2"
    const val Home = "M4 11l8-7 8 7v9H4z M10 20v-6h4v6"
    const val Info = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 11v5 M12 8h.01"
    const val Refresh = "M4 12a8 8 0 0 1 14-5.3 M20 12a8 8 0 0 1-14 5.3 M18 3v4h-4 M6 21v-4h4"
    const val Plus = "M12 5v14 M5 12h14"
    const val Share = "M12 15V3 M7 8l5-5 5 5 M5 13v7h14v-7"
    const val Lock = "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3"
    const val Weather = "M7 18h10a4 4 0 0 0 0-8a6 6 0 0 0-11.5 2A3 3 0 0 0 7 18z"
    const val Trend = "M4 17l6-6 4 4 6-8 M15 7h5v5"
    const val Ticket = "M4 7h16v4a2 2 0 0 0 0 4v4H4v-4a2 2 0 0 0 0-4z M14 7v12"
    const val Play = "M7 5l12 7-12 7z"
    const val Bell = "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z M10 20h4"
    const val Gauge = "M4 16a8 8 0 1 1 16 0 M12 16l4-5"
    const val Tool = "M14.5 5.5a4 4 0 0 0 5 5L12 18l-3 3-3-3 3-3 7.5-7.5z"
    const val Star = "M12 3l2.6 5.6 6.1.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6L3.3 9.3l6.1-.7z"
    const val Height = "M12 3v18 M8 7l4-4 4 4 M8 17l4 4 4-4"
    const val Pin = "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z M12 8a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"
    const val Gear = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M12 3v3 M12 18v3 M3 12h3 M18 12h3 M5.6 5.6l2.1 2.1 M16.3 16.3l2.1 2.1 M5.6 18.4l2.1-2.1 M16.3 7.7l2.1-2.1"
    const val Alert = "M12 4l9 16H3z M12 10v4 M12 17h.01"
    const val Check = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M8 12l3 3 5-6"
    const val Person = "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M4 21a8 8 0 0 1 16 0"
    const val Trash = "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13"
    const val Cart = "M3 4h3l2 11h11l2-8H7 M9 20h.01 M18 20h.01"
    const val More = "M12 5h.01 M12 12h.01 M12 19h.01"
    const val Bug = "M8 8h8v9a4 4 0 0 1-8 0z M12 4v4 M4 12h4 M16 12h4 M5 18l3-2 M19 18l-3-2"
    const val Parcel = "M4 8l8-4 8 4v8l-8 4-8-4z M4 8l8 4 8-4 M12 12v8"
    const val Account = "M4 4h16v16H4z M12 8a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M7 20a5 5 0 0 1 10 0"
}

/** A Material icon's line-drawn counterpart, where the launcher's set has one. */
internal fun frostGlyph(icon: ImageVector): String? = when (icon.name.substringAfterLast('.')) {
    "LocationOn", "Place" -> FrostIcons.Pin
    "Refresh" -> FrostIcons.Refresh
    "DateRange" -> FrostIcons.Calendar
    "Add" -> FrostIcons.Plus
    "Warning" -> FrostIcons.Alert
    "Info" -> FrostIcons.Info
    "Close" -> FrostIcons.Close
    "CheckCircle" -> FrostIcons.Check
    "Build" -> FrostIcons.Tool
    "List" -> FrostIcons.Lines
    "Star" -> FrostIcons.Star
    "ShoppingCart" -> FrostIcons.Cart
    "Share" -> FrostIcons.Share
    "Settings" -> FrostIcons.Gear
    "Search" -> FrostIcons.Search
    "PlayArrow" -> FrostIcons.Play
    "Delete" -> FrostIcons.Trash
    "AccountBox" -> FrostIcons.Account
    "Person" -> FrostIcons.Person
    "Notifications" -> FrostIcons.Bell
    "MoreVert" -> FrostIcons.More
    "Lock" -> FrostIcons.Lock
    "Home" -> FrostIcons.Home
    else -> null
}

/** Draws one of [FrostIcons] at [size] in [color]. */
@Composable
internal fun FrostIcon(path: String, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    // Dots ("h.01") need a wider stroke to show as dots.
    val width = if (path.contains("h.01")) 3f else 2f
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            drawPath(parsed, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The launcher's switch: 40 × 22 dp track, 16 dp thumb 3 dp in. [onChange] null: the row toggles it. */
@Composable
internal fun FrostToggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val frost = LocalFrost.current
    val track by animateColorAsState(if (checked) frost.accent else frost.trackOff, label = "track")
    val thumb by animateColorAsState(if (checked) frost.onAccent else frost.thumbOff, label = "thumb")
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, label = "x")
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .then(if (!enabled) Modifier.background(Color.Transparent) else Modifier)
            .background(track.copy(alpha = track.alpha * if (enabled) 1f else 0.4f), CircleShape)
            .then(if (onChange != null && enabled) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange) else Modifier),
    ) {
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 3.dp.roundToPx()) }.size(16.dp).background(thumb, CircleShape)
            .then(if (!frost.dark && !checked) Modifier.border(1.dp, Color.Black.copy(alpha = 0.10f), CircleShape) else Modifier))
    }
}

/** A 38 dp icon tile: a line icon, a Material icon or a drawable, in the row's colours. */
@Composable
internal fun IconTile(glyph: String?, icon: ImageVector?, iconRes: Int?, error: Boolean, accent: Boolean) {
    val frost = LocalFrost.current
    val ink = when {
        error -> frost.errorInk
        accent -> frost.accent
        else -> frost.textSecondary
    }
    Box(Modifier.size(38.dp).background(if (error) frost.errorTile else frost.tile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val line = glyph ?: icon?.let(::frostGlyph)
        when {
            line != null -> FrostIcon(line, ink, size = 18.dp)
            icon != null -> androidx.compose.material3.Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            iconRes != null -> androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(iconRes), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** A section of the home menu: its tinted icon tile, name and what's in it; tap opens it. */
@Composable
internal fun MenuRow(glyph: String, title: String, caption: String, tone: Tone, error: Boolean = false, onClick: () -> Unit) {
    val frost = LocalFrost.current
    val (fill, ink) = if (error) frost.errorTile to frost.errorInk else frost.tones.getValue(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(FrostInner).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(fill), contentAlignment = Alignment.Center) { FrostIcon(glyph, ink, size = 19.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = FrostType.bodyStrong, color = frost.text)
            Text(caption, style = FrostType.small, color = frost.textTertiary)
        }
        FrostIcon(FrostIcons.ChevronRight, frost.textMuted, size = 16.dp)
    }
}

/** One choice of several, inline: the chosen one highlighted, with a dot. */
@Composable
internal fun FrostChoiceRow(title: String, caption: String?, selected: Boolean, onClick: () -> Unit) {
    val frost = LocalFrost.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp).clip(FrostInner)
            .then(if (selected) Modifier.background(frost.selected).border(1.dp, frost.selectedBorder, FrostInner) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = FrostType.body, color = frost.text)
            if (caption != null) Text(caption, style = FrostType.small, color = frost.textTertiary)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).background(frost.accent, CircleShape))
        }
    }
}

/** Layout ids: a group's label (starts a card), and parts that stand on their own, outside any card. */
internal const val FROST_LABEL = "frost-label"
internal const val FROST_FREE = "frost-free"
/** A part laid out the full width, outside any card (a page header). */
internal const val FROST_FULL = "frost-full"
/** A row that wants no hairline above it (it continues the row before). */
internal const val FROST_JOINED = "frost-joined"

/**
 * The settings pages' column: a [FROST_LABEL] child starts a frost card that holds what follows
 * until the next label, with hairlines between rows; rows before any label share an unlabelled
 * card; a [FROST_FREE] child stands alone. Cards are 14 dp from the sides, 12 dp apart.
 */
@Composable
internal fun FrostColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val frost = LocalFrost.current
    val geometry = remember { FrostGeometry() }
    Layout(
        content = content,
        modifier = modifier.drawBehind {
            val r = CornerRadius(28.dp.toPx())
            geometry.cards.forEach { (top, bottom) ->
                val m = 14.dp.toPx()
                drawRoundRect(frost.fill, Offset(m, top), Size(size.width - 2 * m, bottom - top), r)
                drawRoundRect(frost.border, Offset(m, top), Size(size.width - 2 * m, bottom - top), r, style = Stroke(1.dp.toPx()))
            }
            val inset = (14 + 8 + 10).dp.toPx()
            geometry.dividers.forEach { y -> drawLine(frost.divider, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx()) }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val margin = 14.dp.roundToPx()
        val pad = 8.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val cardTop = 10.dp.roundToPx()
        val cardBottom = 12.dp.roundToPx()
        val inCard = Constraints(maxWidth = (width - 2 * (margin + pad)).coerceAtLeast(0))
        val free = Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0))
        data class Placed(val p: androidx.compose.ui.layout.Placeable, val x: Int, val y: Int)
        val placed = mutableListOf<Placed>()
        val cards = mutableListOf<Pair<Float, Float>>()
        val dividers = mutableListOf<Float>()
        var y = 0
        var cardStart = -1
        var rowsInCard = 0
        fun close() {
            if (cardStart >= 0) {
                y += cardBottom
                cards += cardStart.toFloat() to y.toFloat()
                y += gap
                cardStart = -1
            }
        }
        fun open() {
            if (cardStart < 0) {
                cardStart = y
                y += cardTop
                rowsInCard = 0
            }
        }
        measurables.forEach { m ->
            when (m.layoutId) {
                FROST_FULL -> {
                    close()
                    val p = m.measure(Constraints(maxWidth = width))
                    if (p.height > 0) { placed += Placed(p, 0, y); y += p.height }
                }
                FROST_FREE -> {
                    close()
                    val p = m.measure(free)
                    if (p.height > 0) { placed += Placed(p, margin, y); y += p.height + gap }
                }
                FROST_LABEL -> {
                    close(); open()
                    val p = m.measure(inCard)
                    placed += Placed(p, margin + pad, y); y += p.height
                }
                else -> {
                    val p = m.measure(inCard)
                    if (p.height > 0) {
                        open()
                        if (rowsInCard > 0 && m.layoutId != FROST_JOINED) dividers += y.toFloat()
                        placed += Placed(p, margin + pad, y); y += p.height
                        rowsInCard++
                    }
                }
            }
        }
        close()
        geometry.cards = cards
        geometry.dividers = dividers
        layout(width, (y - gap).coerceAtLeast(0)) { placed.forEach { it.p.place(it.x, it.y) } }
    }
}

/** Where [FrostColumn] drew its cards and hairlines, from its last layout. */
private class FrostGeometry {
    var cards by androidx.compose.runtime.mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    var dividers by androidx.compose.runtime.mutableStateOf<List<Float>>(emptyList())
}

/** A frost card around [content], for parts laid out on their own (lists, previews). */
@Composable
internal fun FrostCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val frost = LocalFrost.current
    Box(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(28.dp)).background(frost.fill)
            .border(1.dp, frost.border, RoundedCornerShape(28.dp)).padding(horizontal = 8.dp, vertical = 10.dp),
    ) { content() }
}

/** A scrolling page of frost cards (for pages that lay out their own header). */
@Composable
internal fun FrostScrollPage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FrostColumn(Modifier.fillMaxWidth()) { content() }
        Spacer(Modifier.height(24.dp))
    }
}

/** Marks a part as standing on its own in a [FrostColumn]. */
internal fun Modifier.frostFree(): Modifier = layoutId(FROST_FREE)
