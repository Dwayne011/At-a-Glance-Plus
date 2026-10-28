package com.ataglanceplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.Chips
import com.ataglanceplus.cards.Complication
import com.ataglanceplus.cards.ComplicationLayout
import com.ataglanceplus.cards.PreviewCard
import com.ataglanceplus.cards.PreviewCards
import com.ataglanceplus.cards.WeatherCard

/**
 * Settings for the card preview. Every change is written to settings, and the service redraws
 * the connected surfaces straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardPreviewSection(maxCards: Int) {
    val context = LocalContext.current
    // Re-read whenever any setting changes, including from the card buttons.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val prefs = Prefs.get(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (version < 0) return

    val layout = Prefs.complicationLayout(context)
    val enabled = Prefs.previewCards(context)
    val dismissed = Prefs.dismissed(context)
    val chips = Prefs.chips(context)

    Section("Cards") {
        Text(
            "Weather uses live data. The other cards show the design's sample content until their data sources are built. Buttons on the cards work.",
            style = MaterialTheme.typography.bodySmall
        )
        if (maxCards > 0) {
            Text(
                "At a Glance asks for up to $maxCards cards; the weather card counts as one, and later cards are left out.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Weather", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sample = Prefs.weatherSample(context)
            WeatherCard.SAMPLES.forEach { (id, label) ->
                FilterChip(selected = sample == id, onClick = { Prefs.setWeatherSample(context, id) }, label = { Text(label) })
            }
        }

        HorizontalDivider()
        Text("Complications", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ComplicationLayout.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = layout == option,
                    onClick = { Prefs.setComplicationLayout(context, option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ComplicationLayout.entries.size)
                ) {
                    Text(
                        when (option) {
                            ComplicationLayout.RIGHT -> "Right"
                            ComplicationLayout.BOTTOM -> "Bottom"
                            ComplicationLayout.NONE -> "None"
                        }
                    )
                }
            }
        }
        val max = if (layout == ComplicationLayout.BOTTOM) Chips.MAX_BOTTOM else Chips.MAX_RIGHT
        Text(
            if (chips == null) "Weather card chips: the design's defaults. Pick your own below (up to $max shown, in the order picked)."
            else "Weather card chips, in the order picked (up to $max shown):",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Complication.entries.filter { it.pickable }.forEach { chip ->
                val position = chips?.indexOf(chip) ?: -1
                FilterChip(
                    selected = position >= 0,
                    onClick = {
                        val now = (chips ?: emptyList()).toMutableList()
                        if (position >= 0) now.remove(chip) else if (now.size < Chips.MAX_BOTTOM) now.add(chip)
                        Prefs.setChips(context, now)
                    },
                    label = { Text(if (position >= 0) "${position + 1}. ${chip.label}" else chip.label) }
                )
            }
        }
        Text("Other cards use the design's chips for each card.", style = MaterialTheme.typography.bodySmall)

        PreviewCard.entries.forEach { card ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(card.label, style = MaterialTheme.typography.labelLarge)
                    if (card.id in dismissed) Text("Dismissed", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = card in enabled, onCheckedChange = { Prefs.setPreviewCard(context, card, it) })
            }
            if (card in enabled) {
                val state = Prefs.previewState(context, card)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.states.forEach { s ->
                        FilterChip(selected = state == s.id, onClick = { Prefs.setPreviewState(context, card, s.id) }, label = { Text(s.label) })
                    }
                }
            }
        }

        HorizontalDivider()
        SwitchRow("Long text", "The longest realistic titles, to check truncation and scrolling.", Prefs.longText(context)) {
            Prefs.setLongText(context, it)
        }
        SwitchRow("Always-on look on the lock screen", "Shows the always-on display style with the screen on. It switches by itself when the screen turns off.", Prefs.aodPreview(context)) {
            Prefs.setAodPreview(context, it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { Prefs.startIsland(context, PreviewCards.ISLAND_MS) }) { Text("Simulate message") }
            OutlinedButton(enabled = dismissed.isNotEmpty(), onClick = { Prefs.restoreDismissed(context) }) { Text("Restore dismissed") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
