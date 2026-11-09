package com.ataglanceplus.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ataglanceplus.live.FinanceAlerts
import com.ataglanceplus.live.StockInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The stock screen below the chart: the next earnings date and the dividend (US listings), the
 * user's price alerts for this stock, and the latest headlines.
 */
@Composable
internal fun StockExtras(symbol: String, price: Double, money: (Double) -> String) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val earnings by produceState<StockInfo.Earnings?>(null, symbol) { value = withContext(Dispatchers.IO) { StockInfo.earnings(symbol) } }
    val dividend by produceState<StockInfo.Dividend?>(null, symbol) { value = withContext(Dispatchers.IO) { StockInfo.dividend(symbol) } }
    val news by produceState(emptyList<StockInfo.News>(), symbol) { value = withContext(Dispatchers.IO) { StockInfo.news(symbol) } }
    val day = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale) }
    val short = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }

    val e = earnings
    val d = dividend
    if (e != null || d != null) GlassPair {
        if (e != null) Glass(Modifier.weight(1f).fillMaxHeight()) {
            Label("Next earnings")
            Figure(day.format(e.date), size = 18.sp)
            val days = ChronoUnit.DAYS.between(LocalDate.now(), e.date)
            Text(
                (when (days) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "In $days days" }) + (e.timing?.let { ", $it" } ?: ""),
                color = GlassSub, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        if (d != null) Glass(Modifier.weight(1f).fillMaxHeight()) {
            Label("Dividend")
            Figure(d.amount ?: d.annual?.let { "$it a year" } ?: "—", size = 18.sp)
            val lines = listOfNotNull(
                d.exDate?.let { (if (it.isBefore(LocalDate.now())) "Last ex-date " else "Ex-date ") + short.format(it) },
                d.payDate?.let { (if (it.isBefore(LocalDate.now())) "Paid " else "Pays ") + short.format(it) },
                d.yield?.let { "Yield $it" },
            )
            lines.forEach { Text(it, color = GlassSub, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }

    PriceAlertsTile(symbol, price, money)

    if (news.isNotEmpty()) Glass(Modifier.fillMaxWidth()) {
        Label("News")
        news.forEach { n ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, n.link.toUri())) } }
                    .padding(vertical = 6.dp),
            ) {
                Text(n.title, color = GlassText, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val at = n.at?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
                Text(listOfNotNull(n.source.takeIf { it.isNotEmpty() }, at).joinToString(" · "), color = GlassSub, fontSize = 11.5.sp)
            }
        }
    }
}

/** This stock's price levels, with a way to add and remove them; and a way to turn alerts on. */
@Composable
private fun PriceAlertsTile(symbol: String, price: Double, money: (Double) -> String) {
    val context = LocalContext.current
    // Redrawn when a level is added or removed, or alerts are turned on.
    var version by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    @Suppress("UNUSED_EXPRESSION") version
    val on = FinanceAlerts.enabled(context)
    val levels = FinanceAlerts.levels(context).filter { it.symbol.equals(symbol, true) }
    val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Price alerts", Modifier.weight(1f))
            if (on) TextButton(onClick = { adding = true }) { Text("Add a level") }
        }
        when {
            !on -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Off. A notification when a stock crosses a level you set, or moves a lot in a day.", color = GlassSub, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    FinanceAlerts.setEnabled(context, true)
                    if (!allowed) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    version++
                }) { Text("Turn on") }
            }
            else -> {
                levels.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stripe(if (l.above) GlassUp else GlassDown)
                        Text("${if (l.above) "Above" else "Below"} ${money(l.price)}", color = GlassText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { FinanceAlerts.removeLevel(context, l); version++ }) { Text("Remove") }
                    }
                }
                val move = FinanceAlerts.movePercent(context)
                Text(
                    (if (levels.isEmpty()) "No levels for $symbol. " else "") +
                        (if (move > 0) "Also alerts on a move of $move% or more in a day." else "Day-move alerts are off.") +
                        if (!allowed) " Notifications are blocked for this app." else "",
                    color = GlassSub, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
    if (adding) AddLevelDialog(symbol, price, money, onDismiss = { adding = false }) {
        FinanceAlerts.addLevel(context, it)
        adding = false
        version++
    }
}

@Composable
private fun AddLevelDialog(symbol: String, price: Double, money: (Double) -> String, onDismiss: () -> Unit, onAdd: (FinanceAlerts.Level) -> Unit) {
    var text by remember { mutableStateOf(String.format(java.util.Locale.ROOT, "%.2f", price)) }
    val level = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it != price }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert for $symbol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    when {
                        level == null -> "Now ${money(price)}. Enter a different price."
                        level > price -> "Alerts once when the price goes above ${money(level)} (now ${money(price)})."
                        else -> "Alerts once when the price goes below ${money(level)} (now ${money(price)})."
                    },
                )
            }
        },
        confirmButton = { TextButton(enabled = level != null, onClick = { level?.let { onAdd(FinanceAlerts.Level(symbol, it, it > price)) } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
