package com.ataglanceplus.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The settings screens' parts, after Material's settings pattern: a top bar with a back arrow,
 * coloured subheaders over groups, and one row per setting (title, a summary of its value, and a
 * switch or an arrow). A value is changed in a dialog; a list is edited as rows.
 */

@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        else Box(Modifier.width(12.dp))
        Text(
            title, style = if (onBack == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
internal fun Subheader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}

/** A note under a group, in the summary colour. */
@Composable
internal fun SettingNote(text: String, colour: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = colour, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

/**
 * A settings row: an optional leading icon or [lead], the title and summary, and a trailing part.
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
    trailing: (@Composable () -> Unit)? = null,
) {
    val tint = when {
        error -> MaterialTheme.colorScheme.error
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            lead != null -> { lead(); Box(Modifier.width(16.dp)) }
            icon != null -> {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Box(Modifier.width(20.dp))
            }
            iconRes != null -> {
                Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Box(Modifier.width(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    error -> MaterialTheme.colorScheme.error
                    accent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Box(Modifier.width(12.dp)); trailing() }
    }
}

/** A row that opens a page: the arrow says so. */
@Composable
internal fun LinkRow(title: String, summary: String? = null, icon: ImageVector? = null, iconRes: Int? = null, error: Boolean = false, onClick: () -> Unit) {
    SettingRow(title, summary, icon = icon, iconRes = iconRes, error = error, onClick = onClick, trailing = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}

/** A row with a switch; tapping anywhere on it flips the switch. */
@Composable
internal fun SwitchSettingRow(title: String, summary: String? = null, icon: ImageVector? = null, iconRes: Int? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(title, summary, icon = icon, iconRes = iconRes, enabled = enabled, onClick = { onChange(!checked) }, trailing = {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    })
}

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
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                lead != null -> { lead(); Box(Modifier.width(16.dp)) }
                icon != null -> {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Box(Modifier.width(20.dp))
                }
                iconRes != null -> {
                    Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Box(Modifier.width(20.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        VerticalDivider(Modifier.height(32.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { Switch(checked = checked, onCheckedChange = onToggle) }
    }
}

/** One of several values, as a dialog of radio rows; a row's [details] line, if given, explains it. */
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
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                            details?.invoke(option)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
