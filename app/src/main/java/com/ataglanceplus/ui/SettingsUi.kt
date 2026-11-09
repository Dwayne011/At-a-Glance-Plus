package com.ataglanceplus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The settings screens' parts, in the frost look (see Frost.kt), after Pixel Launcher Plus's
 * settings: a header with a round back button, an overline and the title; a [Subheader] starts a
 * frost card holding the rows after it; one row per setting (icon tile, title, a summary of its
 * value, and a switch or an arrow). A value is changed in a dialog or picked inline; a list is
 * edited as rows.
 */

/** The page header: a round frost back button (none on Home), the [overline] and the title. */
@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)?, overline: String = "Settings", actions: @Composable () -> Unit = {}) {
    val frost = LocalFrost.current
    Row(
        Modifier.layoutId(FROST_FULL).fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(frost.fill).border(1.dp, frost.border, CircleShape)
                    .clickable(onClick = onBack).semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { FrostIcon(FrostIcons.ChevronLeft, frost.textSecondary, size = 20.dp) }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp)) {
            Text(overline.uppercase(), style = FrostType.overline, color = frost.textTertiary, maxLines = 1)
            Text(title, style = FrostType.title, color = frost.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** A group's label: starts a frost card (in a [FrostColumn]); in capitals, in the accent. */
@Composable
internal fun Subheader(text: String) {
    Text(text.uppercase(), style = FrostType.overline, color = LocalFrost.current.accent,
        modifier = Modifier.layoutId(FROST_LABEL).padding(horizontal = 8.dp, vertical = 4.dp))
}

/** A line of help in a group. [colour] given: an accent note (the accent colour) or a warning. */
@Composable
internal fun SettingNote(text: String, colour: Color = Color.Unspecified) {
    val frost = LocalFrost.current
    val shown = when {
        colour == Color.Unspecified -> frost.textTertiary
        colour == MaterialTheme.colorScheme.primary -> frost.accent
        else -> colour
    }
    Text(text, style = FrostType.small, color = shown, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
}

/**
 * A settings row: a 38 dp icon tile (or [lead]), the title and summary, and a trailing part.
 * [accent] colours it as an action ("Add a stock"); [error] as a problem to fix.
 */
@Composable
internal fun SettingRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    lead: (@Composable () -> Unit)? = null,
    accent: Boolean = false,
    error: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    glyph: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val frost = LocalFrost.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(FrostInner)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            lead != null -> Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { lead() }
            glyph != null || icon != null || iconRes != null -> IconTile(glyph, icon, iconRes, error, accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = FrostType.body, color = if (accent) frost.accent else frost.text)
            if (summary != null) Text(summary, style = FrostType.small, color = frost.textTertiary)
        }
        if (trailing != null) trailing()
    }
}

/** A row that opens a page: the chevron says so. */
@Composable
internal fun LinkRow(title: String, summary: String? = null, icon: ImageVector? = null, iconRes: Int? = null, error: Boolean = false, glyph: String? = null, onClick: () -> Unit) {
    SettingRow(title, summary, icon = icon, iconRes = iconRes, error = error, glyph = glyph, onClick = onClick, trailing = {
        FrostIcon(FrostIcons.ChevronRight, LocalFrost.current.textMuted, size = 16.dp)
    })
}

/** A row with the frost switch; tapping anywhere on it flips the switch. */
@Composable
internal fun SwitchSettingRow(title: String, summary: String? = null, icon: ImageVector? = null, iconRes: Int? = null, checked: Boolean, enabled: Boolean = true, glyph: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(title, summary, icon = icon, iconRes = iconRes, glyph = glyph, enabled = enabled, onClick = { onChange(!checked) }, trailing = {
        FrostToggle(checked, null, enabled)
    })
}

/** The frost switch where a Material one was: same arguments. */
@Composable
internal fun FrostSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) =
    FrostToggle(checked, onCheckedChange, enabled)

/** A row whose text opens something and whose switch toggles it, split by a short line. */
@Composable
internal fun SplitSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onOpen: () -> Unit,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    lead: (@Composable () -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    val frost = LocalFrost.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(FrostInner).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                lead != null -> Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { lead() }
                icon != null || iconRes != null -> IconTile(null, icon, iconRes, error = false, accent = false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = FrostType.body, color = frost.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = FrostType.small, color = frost.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(1.dp).height(32.dp).background(frost.divider))
        Box(Modifier.padding(horizontal = 14.dp)) { FrostToggle(checked, onToggle) }
    }
}

/** One of several values, as a dialog of frost choice rows; a row's [details] line, if given, explains it. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    details: ((T) -> String?)? = null,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FrostFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option -> FrostChoiceRow(label(option), details?.invoke(option), option == selected) { onPick(option) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
