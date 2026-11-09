package com.ataglanceplus.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ataglanceplus.cards.CardRules
import com.ataglanceplus.cards.CardType
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The cards a rule can name: every card but the weather card, which always stays. */
private val RULE_CARDS get() = CardType.entries

private fun cardLabel(id: String) = CardType.entries.firstOrNull { it.id == id }?.label ?: id

/** Settings › Display › Card order: the master switch, the built-in rules and your own windows. */
@Composable
internal fun CardOrderPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    var adding by remember { mutableStateOf(false) }
    val on = CardRules.orderOn(context)
    SettingsPage("Card order", onBack) {
        Subheader("Smarter order")
        SwitchSettingRow(
            "Reorder cards through the day",
            if (on) "On — the rules below bring cards forward" else "Off — the usual order: the most time-sensitive first",
            glyph = FrostIcons.Lines, checked = on,
        ) { CardRules.setOrderOn(context, it) }
        if (on) {
            Subheader("Your windows")
            val windows = CardRules.orderWindows(context)
            windows.forEachIndexed { i, w ->
                SettingRow(
                    CardRules.describe(w), "First: " + w.cards.joinToString(", ") { cardLabel(it) }, glyph = FrostIcons.Calendar,
                    trailing = { TextButton(onClick = { CardRules.setOrderWindows(context, windows.filterIndexed { j, _ -> j != i }) }) { Text("Remove") } },
                )
            }
            SettingRow("Add a window", "Cards to bring first at set times", glyph = FrostIcons.Plus, accent = true, onClick = { adding = true })
            SettingNote("Your windows come before the built-in rules; within a window, cards go in the order you ticked them.")

            Subheader("Built-in rules")
            CardRules.Builtin.entries.forEach { b ->
                SwitchSettingRow(b.label, b.detail, glyph = when (b) {
                    CardRules.Builtin.MORNING_CALENDAR -> FrostIcons.Calendar
                    CardRules.Builtin.SPORTS_KICKOFF -> FrostIcons.Ball
                    CardRules.Builtin.RAIN_COMING -> FrostIcons.Weather
                    CardRules.Builtin.COMMUTE -> FrostIcons.Home
                }, checked = CardRules.builtinOn(context, b)) { CardRules.setBuiltinOn(context, b, it) }
            }
            SettingNote("A new message always shows first. Cards no rule names keep the usual order.")
        }
    }
    if (adding) RuleDialog("Bring first", ordered = true, onDismiss = { adding = false }) {
        CardRules.setOrderWindows(context, CardRules.orderWindows(context) + it)
        adding = false
    }
}

/** Settings › Display › Quiet hours: windows that hide chosen cards. */
@Composable
internal fun QuietHoursPage(version: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") version
    var adding by remember { mutableStateOf(false) }
    SettingsPage("Quiet hours", onBack) {
        Subheader("Quiet hours")
        val rules = CardRules.quietRules(context)
        if (rules.isEmpty()) SettingNote("None yet. Hide cards overnight or at work: add a window and the cards it hides.")
        rules.forEachIndexed { i, r ->
            SettingRow(
                CardRules.describe(r), "Hides " + r.cards.joinToString(", ") { cardLabel(it) }, glyph = FrostIcons.Lock,
                trailing = { TextButton(onClick = { CardRules.setQuietRules(context, rules.filterIndexed { j, _ -> j != i }) }) { Text("Remove") } },
            )
        }
        SettingRow("Add quiet hours", "A window and the cards to hide in it", glyph = FrostIcons.Plus, accent = true, onClick = { adding = true })
        SettingNote("The weather card always stays. A hidden card comes back when its window ends.")
    }
    if (adding) RuleDialog("Hide", ordered = false, onDismiss = { adding = false }) {
        CardRules.setQuietRules(context, CardRules.quietRules(context) + it)
        adding = false
    }
}

/**
 * A new rule: from and until (Android's time pickers), the days, and the cards ([ordered]: the
 * order they are ticked in counts, shown as numbers).
 */
@Composable
private fun RuleDialog(cardsTitle: String, ordered: Boolean, onDismiss: () -> Unit, onSave: (CardRules.Rule) -> Unit) {
    val context = LocalContext.current
    val frost = LocalFrost.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var start by remember { mutableIntStateOf(7 * 60) }
    var end by remember { mutableIntStateOf(9 * 60) }
    var days by remember { mutableStateOf((1..7).toSet()) }
    var cards by remember { mutableStateOf(listOf<String>()) }
    fun hm(v: Int) = String.format(Locale.ROOT, "%02d:%02d", v / 60, v % 60)
    fun pick(initial: Int, title: String, set: (Int) -> Unit) =
        android.app.TimePickerDialog(context, { _, h, m -> set(h * 60 + m) }, initial / 60, initial % 60, true).apply { setTitle(title) }.show()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New window", fontFamily = FrostFont) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pick(start, "From") { start = it } }) { Text("From ${hm(start)}") }
                    TextButton(onClick = { pick(end, "Until") { end = it } }) { Text("Until ${hm(end)}") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (on) frost.accent else frost.tile)
                                .clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, locale), color = if (on) frost.onAccent else frost.text, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                Text(cardsTitle, style = FrostType.overline, color = frost.accent, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                RULE_CARDS.forEach { type ->
                    val at = cards.indexOf(type.id)
                    Row(
                        Modifier.fillMaxWidth().clip(FrostInner).clickable { cards = if (at >= 0) cards - type.id else cards + type.id }.padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(if (at >= 0) frost.accent else androidx.compose.ui.graphics.Color.Transparent)
                                .border(1.5.dp, if (at >= 0) frost.accent else frost.textMuted, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (at >= 0) Text(if (ordered) "${at + 1}" else "✓", color = frost.onAccent, fontSize = 11.sp)
                        }
                        Text(type.label, style = FrostType.body, color = frost.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = cards.isNotEmpty() && days.isNotEmpty() && start != end, onClick = { onSave(CardRules.Rule(start, end, days, cards)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A time window's from, until and days (no cards), for the commute's windows. */
@Composable
internal fun WindowDialog(title: String, initial: CardRules.Rule, onDismiss: () -> Unit, onSave: (CardRules.Rule) -> Unit) {
    val context = LocalContext.current
    val frost = LocalFrost.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var start by remember { mutableIntStateOf(initial.start) }
    var end by remember { mutableIntStateOf(initial.end) }
    var days by remember { mutableStateOf(initial.days) }
    fun hm(v: Int) = String.format(Locale.ROOT, "%02d:%02d", v / 60, v % 60)
    fun pick(at: Int, label: String, set: (Int) -> Unit) =
        android.app.TimePickerDialog(context, { _, h, m -> set(h * 60 + m) }, at / 60, at % 60, true).apply { setTitle(label) }.show()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FrostFont) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pick(start, "From") { start = it } }) { Text("From ${hm(start)}") }
                    TextButton(onClick = { pick(end, "Until") { end = it } }) { Text("Until ${hm(end)}") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (on) frost.accent else frost.tile).clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) { Text(DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, locale), color = if (on) frost.onAccent else frost.text, fontSize = 13.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = days.isNotEmpty() && start != end, onClick = { onSave(CardRules.Rule(start, end, days, emptyList())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
