package com.ataglanceplus.live

import android.content.Context
import androidx.core.content.edit
import com.ataglanceplus.Prefs
import com.ataglanceplus.wallet.GoogleAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gmail, read-only, through the Google sign-in made for Wallet (see [GoogleAccount]): the last
 * few inbox messages for the Gmail chip's mini inbox, and parcels from shipping emails. Nothing is
 * ever changed or sent; mail stays in memory (parcels from Live Updates are kept in settings,
 * the shop and status only, until delivered).
 */
object GmailMail {

    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"

    data class Mail(val id: String, val from: String, val subject: String, val snippet: String, val at: Long, val unread: Boolean)

    /** A parcel's stage, in order. */
    enum class Stage(val label: String, val step: Int) {
        ORDERED("Ordered", 1), SHIPPED("On its way", 2), OUT("Out for delivery", 3), DELIVERED("Delivered", 4)
    }

    data class Parcel(
        val key: String,
        /** The shop or carrier: the sender's name, or the app's. */
        val from: String,
        val stage: Stage,
        /** "Arriving tomorrow", when the email or update says. */
        val expected: String?,
        val updatedAt: Long,
        /** "Gmail" or the app the Live Update came from. */
        val source: String,
    )

    private val _inbox = MutableStateFlow<List<Mail>?>(null)
    val inbox: StateFlow<List<Mail>?> = _inbox.asStateFlow()

    private val _parcels = MutableStateFlow<List<Parcel>>(emptyList())
    /** Parcels from Gmail and Live Updates, newest first; delivered ones for a day after. */
    val parcels: StateFlow<List<Parcel>> = _parcels.asStateFlow()

    @Volatile private var mailParcels: List<Parcel> = emptyList()
    @Volatile private var inboxAt = 0L
    @Volatile private var parcelsAt = 0L
    /** Why Gmail could not be read, for the screens; null when it could. */
    @Volatile var problem: String? = null
        private set

    private fun get(token: String, path: String): JSONObject? {
        val c = (URL("$API/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                401 -> null.also { problem = "Gmail refused the sign-in (HTTP 401)." }
                else -> null.also { problem = "Gmail did not answer (HTTP ${c.responseCode})." }
            }
        } finally { c.disconnect() }
    }

    private fun token(context: Context): String? {
        if (!GoogleAccount.isSignedIn(context)) { problem = "Sign in to Google (Settings › Cards › Wallet) to read Gmail."; return null }
        return GoogleAccount.gmailToken(context) ?: null.also { problem = GoogleAccount.gmailError ?: "Google gave no Gmail access." }
    }

    /** "Name <a@b>" → "Name"; a bare address → its part before @. */
    private fun sender(from: String): String {
        val name = from.substringBefore("<").trim().trim('"')
        return name.ifEmpty { from.substringAfter("<").substringBefore("@").trim() }.ifEmpty { from }
    }

    private fun message(token: String, id: String): Pair<Mail, String>? {
        val m = get(token, "messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject") ?: return null
        val headers = m.optJSONObject("payload")?.optJSONArray("headers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        fun header(n: String) = headers.firstOrNull { it.optString("name").equals(n, true) }?.optString("value").orEmpty()
        val labels = m.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val mail = Mail(
            id, sender(header("From")), header("Subject"), android.text.Html.fromHtml(m.optString("snippet"), 0).toString(),
            m.optString("internalDate").toLongOrNull() ?: 0L, "UNREAD" in labels,
        )
        return mail to m.optString("threadId")
    }

    /** The last [count] inbox messages, newest first; a copy under 2 minutes old unless [force]. Blocking. */
    suspend fun refreshInbox(context: Context, count: Int = 8, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - inboxAt < 2 * 60_000L) return@withContext
        val token = token(context) ?: return@withContext
        val list = get(token, "messages?maxResults=$count&labelIds=INBOX")?.optJSONArray("messages") ?: return@withContext
        _inbox.value = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.optString("id")?.let { message(token, it)?.first } }
            .sortedByDescending { it.at }
        inboxAt = System.currentTimeMillis()
        problem = null
    }

    private val QUERY = "newer_than:14d subject:(delivered OR \"out for delivery\" OR dispatched OR shipped OR \"on its way\" OR \"on the way\" OR \"your order\" OR \"your parcel\" OR \"your package\")"
    private val ORDER_NO = Regex("""(?i)(?:order|parcel|tracking|shipment)\s*(?:number|no\.?|#|:|ref\w*)?\s*#?\s*([A-Z0-9][A-Z0-9-]{5,})""")
    private val EXPECTED = Regex("""(?i)\b(arriv\w*|expected|due|delivery)\s+(today|tomorrow|(?:on\s+)?(?:mon|tues|wednes|thurs|fri|satur|sun)day(?:\s+\d{1,2}\s+\w+)?|by\s+\d{1,2}(?::\d{2})?\s*[ap]m)""")

    private val DELIVERED = Regex("""\b(has been|was|got|been|is now|successfully) delivered\b|\b(parcel|package|order|item|shipment)s? (?:has been |have been |was |were )?delivered\b|^delivered\b|\bdelivered:|\bdelivered today\b""")
    private val WILL_BE = Regex("""\b(will|to|should) be delivered\b|\bbeing delivered\b""")

    /** A stage from an email's or update's words; null when they say nothing about delivery. */
    internal fun stageOf(text: String): Stage? {
        val t = text.lowercase()
        return when {
            DELIVERED.containsMatchIn(t) && !t.contains("out for delivery") && !WILL_BE.containsMatchIn(t) -> Stage.DELIVERED
            t.contains("out for delivery") || t.contains("arriving today") || t.contains("with the driver") || t.contains("with your driver") -> Stage.OUT
            listOf("dispatched", "shipped", "on its way", "on the way", "in transit", "handed over", "collected by").any { it in t } -> Stage.SHIPPED
            listOf("order confirm", "thanks for your order", "thank you for your order", "received your order", "order placed").any { it in t } -> Stage.ORDERED
            else -> null
        }
    }

    internal fun expectedOf(text: String): String? = EXPECTED.find(text)?.let { m ->
        val (verb, whenText) = m.destructured
        (if (verb.lowercase().startsWith("arriv")) "Arriving " else "Expected ") +
            whenText.trim().replace(Regex("^(?i)on\\s+"), "").let { w -> if (w.equals("today", true) || w.equals("tomorrow", true) || w.startsWith("by", true)) w.lowercase() else w }
    }

    /** Parcels from the last 14 days' shipping emails; at most every 15 minutes unless [force]. Blocking. */
    suspend fun refreshParcels(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - parcelsAt < 15 * 60_000L) { publish(context); return@withContext }
        val token = token(context) ?: run { publish(context); return@withContext }
        val list = get(token, "messages?maxResults=30&q=" + URLEncoder.encode(QUERY, "UTF-8"))?.optJSONArray("messages")
        if (list != null) {
            val mails = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.optString("id")?.let { message(token, it) } }
            // One parcel per order (or thread): the latest email's stage wins.
            mailParcels = mails.mapNotNull { (mail, thread) ->
                val text = mail.subject + " " + mail.snippet
                val stage = stageOf(mail.subject) ?: stageOf(text) ?: return@mapNotNull null
                val key = ORDER_NO.find(text)?.groupValues?.get(1)?.takeIf { id -> id.any { it.isDigit() } }?.let { "order:$it" } ?: "thread:$thread"
                Parcel(key, mail.from, stage, expectedOf(text), mail.at, "Gmail")
            }.groupBy { it.key }.map { (_, p) -> p.maxBy { it.updatedAt } }
            parcelsAt = System.currentTimeMillis()
            problem = null
        }
        publish(context)
    }

    // --- Parcels from Live Updates ---

    private const val KEY_LIVE = "parcels_live"

    /** Live Updates that read like a parcel (not food or rides): remembered until delivered. */
    fun noteLive(context: Context, live: List<LiveNow>, tripApps: Set<String>) {
        val saved = liveParcels(context).associateBy { it.key }.toMutableMap()
        var changed = false
        live.forEach { l ->
            val pkg = l.key.substringAfter('|').substringBefore('|')
            if (pkg in tripApps) return@forEach
            val text = l.title + " " + l.text
            if (!Regex("""(?i)\b(parcel|package|courier|delivery|delivered|tracking|dispatched)\b""").containsMatchIn(text)) return@forEach
            val stage = stageOf(text) ?: Stage.SHIPPED
            val key = "live:$pkg"
            val old = saved[key]
            if (old == null || old.stage != stage || old.expected != expectedOf(text)) {
                saved[key] = Parcel(key, l.app, stage, expectedOf(text), System.currentTimeMillis(), l.app)
                changed = true
            }
        }
        if (changed) {
            Prefs.get(context).edit { putString(KEY_LIVE, JSONArray(saved.values.map { it.toJson() }).toString()) }
            publish(context)
        }
    }

    private fun Parcel.toJson(): JSONObject = JSONObject().put("k", key).put("f", from).put("s", stage.name).put("e", expected).put("u", updatedAt).put("o", source)

    private fun liveParcels(context: Context): List<Parcel> = runCatching {
        val a = JSONArray(Prefs.get(context).getString(KEY_LIVE, "[]"))
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o -> Parcel(o.getString("k"), o.getString("f"), Stage.valueOf(o.getString("s")), o.optString("e").takeIf { it.isNotEmpty() && it != "null" }, o.getLong("u"), o.getString("o")) }
        }
    }.getOrDefault(emptyList())

    /**
     * Gmail's and Live Updates' parcels together: delivered ones for a day after, live ones until
     * 3 days without news, others for 14 days.
     */
    fun publish(context: Context) {
        val now = System.currentTimeMillis()
        fun keep(p: Parcel) = when {
            p.stage == Stage.DELIVERED -> now - p.updatedAt < 24 * 3_600_000L
            p.source != "Gmail" -> now - p.updatedAt < 3 * 24 * 3_600_000L
            else -> true
        }
        _parcels.value = (mailParcels + liveParcels(context)).filter(::keep).sortedByDescending { it.updatedAt }
    }

    /**
     * The parcel for the card: out for delivery first, then on its way (with news in the last 7
     * days), with how many more are coming. Ordered-only and delivered ones stay off the card.
     */
    fun forCard(): Pair<Parcel, Int>? {
        val now = System.currentTimeMillis()
        val coming = _parcels.value.filter { it.stage == Stage.OUT || (it.stage == Stage.SHIPPED && now - it.updatedAt < 7 * 24 * 3_600_000L) }
        val first = coming.maxWithOrNull(compareBy<Parcel> { it.stage.step }.thenBy { it.updatedAt }) ?: return null
        return first to coming.size - 1
    }
}
