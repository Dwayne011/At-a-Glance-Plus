package com.ataglanceplus.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ataglanceplus.live.GmailMail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MailTint = listOf(Color(0xFFC92A2A), Color(0xFF364FC7), Color(0xFF2B2D42))

private fun openGmail(context: android.content.Context) {
    runCatching { context.packageManager.getLaunchIntentForPackage("com.google.android.gm")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity) }
}

private fun ago(at: Long) = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The Gmail chip's mini inbox: the last few messages (sender, subject, a line), then Gmail. */
@Composable
internal fun InboxDashboard(generation: Int, onLoaded: () -> Unit) {
    val context = LocalContext.current
    val signedIn = com.ataglanceplus.wallet.GoogleAccount.isSignedIn(context)
    val done by produceState(false, generation) {
        withContext(Dispatchers.IO) { runCatching { GmailMail.refreshInbox(context, force = generation > 0) } }
        value = true
        onLoaded()
    }
    val inbox by GmailMail.inbox.collectAsStateWithLifecycle()
    GlassScreen(MailTint, accent = Color(0xFFFFA8A8)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Inbox", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            com.ataglanceplus.wallet.GoogleAccount.email(context)?.let { Text(it, color = GlassSub, fontSize = 13.sp) }
            val list = inbox
            when {
                !signedIn -> Text("Sign in to Google first (Settings › Cards › Wallet): the inbox is read with that sign-in, read-only.", color = GlassSub)
                list == null && !done -> Text("Loading…", color = GlassSub)
                list == null -> Text(GmailMail.problem ?: "Gmail did not answer.", color = GlassSub)
                list.isEmpty() -> Text("Your inbox is empty.", color = GlassSub)
                else -> InboxRows(list)
            }
            OutlinedButton(onClick = { openGmail(context) }) { Text("Open Gmail") }
            Text("Read-only: nothing is marked read, changed or sent.", color = GlassSub, fontSize = 11.5.sp)
        }
    }
}

/** Every parcel: shop, stage (as four steps), when expected, from Gmail or which app's Live Update. */
@Composable
internal fun ParcelsDashboard(generation: Int, onLoaded: () -> Unit) {
    val context = LocalContext.current
    val done by produceState(false, generation) {
        withContext(Dispatchers.IO) { runCatching { GmailMail.refreshParcels(context, force = generation > 0) } }
        value = true
        onLoaded()
    }
    val parcels by GmailMail.parcels.collectAsStateWithLifecycle()
    GlassScreen(listOf(Color(0xFF0B7285), Color(0xFFB5651D), Color(0xFF1B2B3A)), accent = Color(0xFF63E6BE)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parcels", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            when {
                parcels.isEmpty() && !done -> Text("Loading…", color = GlassSub)
                parcels.isEmpty() -> Text(GmailMail.problem ?: "No parcels in the last 14 days.", color = GlassSub)
                else -> parcels.forEach { p ->
                    Glass(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stripe(if (p.stage == GmailMail.Stage.DELIVERED) GlassUp else Color(0xFF63E6BE), 34.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.from, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(p.stage.label, p.expected).joinToString(" · "), color = GlassText, fontSize = 13.sp)
                                Text("${p.source} · ${ago(p.updatedAt)}", color = GlassSub, fontSize = 11.5.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..4).forEach { step ->
                                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (step <= p.stage.step) Color(0xFF63E6BE) else GlassTrack))
                            }
                        }
                    }
                }
            }
            Text("From shipping emails in the last 14 days (read-only) and parcel Live Updates. Delivered parcels stay a day.", color = GlassSub, fontSize = 11.5.sp)
        }
    }
}

/** The mini inbox's messages: an unread dot, sender and time, subject, and a line. Tap: Gmail. */
@Composable
internal fun InboxRows(list: List<GmailMail.Mail>) {
    val context = LocalContext.current
    Glass(Modifier.fillMaxWidth(), padding = 6.dp) {
        list.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { openGmail(context) }.padding(horizontal = 8.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (m.unread) Color(0xFFFFA8A8) else Color.Transparent))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.from, color = Color.White, fontSize = 14.sp, fontWeight = if (m.unread) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(ago(m.at), color = GlassSub, fontSize = 11.5.sp)
                    }
                    Text(m.subject.ifEmpty { "(no subject)" }, color = GlassText, fontSize = 13.sp, fontWeight = if (m.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.snippet, color = GlassSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
