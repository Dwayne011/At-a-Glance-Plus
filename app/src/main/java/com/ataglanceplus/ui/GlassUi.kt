package com.ataglanceplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The dashboards' frosted "Bento" look (mockup approved 2026-11-08): a blurred backdrop of the
 * wallpaper's Material You colours tinted for each dashboard (team colours for a game, F1 green,
 * night blues for sleep…), with translucent glass tiles over it. One picture per screen at most,
 * in the top tile; lists mark teams and brands with a thin colour stripe, and players and cars
 * with their numbers.
 */

internal val GlassText = Color(0xFFF1F3F6)
internal val GlassSub = Color(0xFFA7ADB6)
internal val GlassFill = Color.White.copy(alpha = 0.085f)
internal val GlassEdge = Color.White.copy(alpha = 0.14f)
internal val GlassTrack = Color.White.copy(alpha = 0.12f)
internal val GlassUp = Color(0xFF6FE08A)
internal val GlassDown = Color(0xFFFF8A80)

/** Each dashboard's tint, mixed into the wallpaper's colours behind the glass. */
internal object Tints {
    val Money = listOf(Color(0xFF3B5BDB), Color(0xFF7048E8), Color(0xFF0B7285))
    val F1 = listOf(Color(0xFF1A5C48), Color(0xFFFF8000), Color(0xFF0B3D2E))
    val Steps = listOf(Color(0xFF2B8A3E), Color(0xFF0B7285), Color(0xFF1B4332))
    val Sleep = listOf(Color(0xFF4C3FB6), Color(0xFF1C3D8F), Color(0xFF2B2160))
    val Screen = listOf(Color(0xFFB5651D), Color(0xFF7048E8), Color(0xFF3D2A12))
    val Heart = listOf(Color(0xFFC92A2A), Color(0xFF862E9C), Color(0xFF3B1218))
}

/**
 * A dashboard screen: the tinted, blurred backdrop, a slight dark veil, and [content] over it in
 * a dark Material You theme. [tint] gives two or three colours; the wallpaper's primary and
 * tertiary are mixed in, so the screen follows the phone's theme. [accent] colours selected tabs
 * and rings.
 */
@Composable
internal fun GlassScreen(tint: List<Color>, accent: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val wallpaper = remember { runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme() } }
    val colours = listOf(
        tint.getOrElse(0) { wallpaper.primary },
        mix(tint.getOrElse(1) { wallpaper.tertiary }, wallpaper.primary, 0.25f),
        mix(tint.getOrElse(2) { wallpaper.secondary }, wallpaper.tertiary, 0.35f),
        wallpaper.primaryContainer,
    )
    val scheme = darkColorScheme(
        primary = accent ?: wallpaper.primary, onPrimary = Color(0xFF0B1530),
        background = Color(0xFF0C1018), onBackground = GlassText, surface = Color(0xFF0C1018), onSurface = GlassText,
        onSurfaceVariant = GlassSub, surfaceContainerHigh = Color(0xFF1C2230), surfaceContainerHighest = Color(0xFF232A38),
        outline = Color(0x40FFFFFF), outlineVariant = Color(0x24FFFFFF), error = GlassDown,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides GlassText) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0C1018))) {
                // Soft pools of colour, blurred: drawn once, behind everything.
                Canvas(Modifier.fillMaxSize().blur(48.dp)) {
                    val spots = listOf(
                        Offset(size.width * 0.15f, size.height * 0.06f) to size.width * 0.75f,
                        Offset(size.width * 0.95f, size.height * 0.32f) to size.width * 0.7f,
                        Offset(size.width * 0.3f, size.height * 0.95f) to size.width * 0.85f,
                        Offset(size.width * 0.8f, size.height * 0.72f) to size.width * 0.45f,
                    )
                    spots.forEachIndexed { i, (centre, radius) ->
                        drawCircle(Brush.radialGradient(listOf(colours[i].copy(alpha = 0.85f), Color.Transparent), centre, radius), radius, centre)
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(0x590B0E14)))
                content()
            }
        }
    }
}

internal fun mix(a: Color, b: Color, f: Float): Color =
    Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)

/** A glass tile. */
@Composable
internal fun Glass(modifier: Modifier = Modifier, padding: Dp = 12.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier.clip(shape).background(GlassFill).border(1.dp, GlassEdge, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Two tiles side by side, as tall as the taller. */
@Composable
internal fun GlassPair(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A small grey label over a tile's figures. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = GlassSub, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A tile's figure: bold, one line, shrinking to fit rather than cutting off. */
@Composable
internal fun Figure(text: String, modifier: Modifier = Modifier, size: TextUnit = 18.sp, colour: Color = Color.White, align: TextAlign? = null) {
    Text(
        text, color = colour, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = align, modifier = modifier,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = size, stepSize = 0.5.sp),
    )
}

/** A feed's label in sentence case ("ON GOAL" → "On goal"), keeping short codes ("FG", "3PT") as they are. */
internal fun niceLabel(label: String): String =
    if (label.length > 3 && label == label.uppercase() && label.any { it.isLetter() }) label.lowercase().replaceFirstChar { it.uppercase() } else label

/** A team or brand's thin colour stripe, used in lists in place of a logo. */
@Composable
internal fun Stripe(colour: Color, height: Dp = 16.dp) {
    Box(Modifier.width(4.dp).height(height).clip(RoundedCornerShape(2.dp)).background(colour))
}

/** A shirt, jersey or car number in the team's colour, right-aligned so names line up. */
@Composable
internal fun Number(number: String?, colour: Color, width: Dp = 24.dp) {
    Text(number.orEmpty(), color = colour, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End,
        maxLines = 1, modifier = Modifier.width(width))
}

/** A list row: stripe, number, name, then what follows. */
@Composable
internal fun StripeRow(colour: Color, number: String? = null, showNumber: Boolean = number != null, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stripe(colour)
        if (showNumber) Number(number, colour.lift(0.25f))
        content()
    }
}

/** A progress ring with its figure inside. */
@Composable
internal fun Ring(progress: Float, colour: Color, label: String, size: Dp = 80.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 9.dp.toPx()
            drawArc(GlassTrack, 0f, 360f, false, style = Stroke(stroke))
            drawArc(colour, -90f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Tabs as pills; the chosen one filled with [accent]. Scrolls sideways when they don't fit. */
@Composable
internal fun GlassTabs(tabs: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Text(
                t, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                color = if (on) Color(0xFF0B1530) else Color(0xFFD5D9DF),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) accent else Color.White.copy(alpha = 0.07f))
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Two values compared on one bar, each side in its team's colour. */
@Composable
internal fun CompareBar(label: String, left: String, right: String, leftShare: Float, leftColour: Color, rightColour: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(left, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(label, color = GlassSub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(right, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(GlassTrack), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val share = leftShare.coerceIn(0.03f, 0.97f)
            Box(Modifier.weight(share).fillMaxSize().background(leftColour))
            Box(Modifier.weight(1f - share).fillMaxSize().background(rightColour))
        }
    }
}

/** Mixes a colour toward white. */
internal fun Color.lift(fraction: Float): Color = Color(
    red = red + (1 - red) * fraction, green = green + (1 - green) * fraction, blue = blue + (1 - blue) * fraction, alpha = 1f,
)
