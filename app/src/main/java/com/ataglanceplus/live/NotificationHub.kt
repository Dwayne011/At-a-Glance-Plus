package com.ataglanceplus.live

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A running (or paused) countdown timer from any clock app's notification. */
data class TimerNow(
    val key: String,
    val title: String,
    /** Wall-clock end time while running; null while paused. */
    val endsAt: Long?,
    /** The time left as the app shows it, while paused ("4:59"). */
    val pausedText: String?,
    /** The notification's own pause or resume button, if it has one. */
    val toggle: PendingIntent?,
    val open: PendingIntent?,
)

/** A Live Update (Android 16 promoted ongoing notification) from any app. */
data class LiveNow(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    /** The short status the app gives for the chip ("12 min", "Now"). */
    val chip: String?,
    /** Filled segments out of 4, or null when there is no progress to show. */
    val filled: Int?,
    val icon: Icon?,
    val open: PendingIntent?,
)

/** How the directions are travelling, when their wording says so. */
enum class TravelMode { TRANSIT, BUS, WALK, BIKE, CAR }

/** Turn-by-turn navigation from a maps app's notification. */
data class NavNow(
    val key: String,
    val distance: String?,
    val instruction: String,
    val detail: String,
    /** The maps app's own picture (turn arrow, or the line's roundel on public transport). */
    val icon: Icon?,
    val open: PendingIntent?,
    /** Trip progress out of 1000, from the app's Live Update progress, or null. */
    val progress: Int?,
    /** Colour of the route section the trip is on now (a line's colour), or null. */
    val progressColour: Int?,
    val mode: TravelMode?,
    /** The platform, track or stand to go to ("Platform 3"), when the directions mention one. */
    val platform: String?,
)

/** A message that just arrived, for the message toast. */
data class IncomingMessage(
    val key: String,
    val app: String,
    val pkg: String,
    val colour: Int,
    val sender: String,
    val text: String,
    val open: PendingIntent?,
)

/**
 * Turns the notifications on screen into card data. Fed by the notification listener; the
 * service watches these flows and redraws.
 */
object NotificationHub {

    private val _timer = MutableStateFlow<TimerNow?>(null)
    val timer: StateFlow<TimerNow?> = _timer.asStateFlow()

    private val _live = MutableStateFlow<List<LiveNow>>(emptyList())
    val live: StateFlow<List<LiveNow>> = _live.asStateFlow()

    private val _nav = MutableStateFlow<NavNow?>(null)
    val nav: StateFlow<NavNow?> = _nav.asStateFlow()

    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    /** Latest message time seen per notification, so an update without a new message is not a new message. */
    private val lastMessageAt = HashMap<String, Long>()

    private const val MAPS = "com.google.android.apps.maps"

    private val TIME_TEXT = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private val DISTANCE = Regex("""^(\d+([.,]\d+)?\s?(m|km|mi|ft|yd|metres|meters|miles|yards|feet))\b\s*[·,-]?\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun update(context: Context, active: List<StatusBarNotification>, posted: StatusBarNotification?) {
        val own = context.packageName
        val usable = active.filter { it.packageName != own && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

        val timer = usable.firstNotNullOfOrNull { timerFrom(it) }
        // Navigation: marked as such, or any ongoing Google Maps notification (public-transport
        // trips may be posted as a plain Live Update).
        val nav = usable.firstOrNull {
            it.isOngoing && (it.notification.category == Notification.CATEGORY_NAVIGATION || it.packageName == MAPS)
        }?.let { navFrom(context, it) }
        val live = usable
            .filter { it.key != timer?.key && it.key != nav?.key && isLiveUpdate(it.notification) }
            .sortedByDescending { it.postTime }
            .map { liveFrom(context, it) }
        _timer.value = timer
        _nav.value = nav
        _live.value = live

        // Notifications already on screen when we start are not new messages.
        active.forEach { if (it.key != posted?.key) lastMessageAt.putIfAbsent(it.key, it.notification.`when`) }
        if (posted != null && posted.packageName != own) messageFrom(context, posted)?.let { _messages.tryEmit(it) }
        lastMessageAt.keys.retainAll(active.map { it.key }.toSet())
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    private fun template(n: Notification) = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    }.getOrDefault(pkg)

    /**
     * A countdown chronometer is a running timer. A clock app's ongoing notification showing a
     * bare time ("4:59") is a paused one.
     */
    private fun timerFrom(sbn: StatusBarNotification): TimerNow? {
        val n = sbn.notification
        val chronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        // A clock app's ticking time that ends in the future is a timer, even without the
        // count-down flag (some post it as a Live Update).
        val countdown = chronometer && (n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) ||
            ("clock" in sbn.packageName && n.`when` > System.currentTimeMillis()))
        val toggle = n.actions?.firstOrNull { action ->
            val label = action.title?.toString()?.lowercase().orEmpty()
            "pause" in label || "resume" in label || "start" in label
        }?.actionIntent
        if (countdown) {
            return TimerNow(sbn.key, title(n).ifEmpty { "Timer" }, n.`when`, null, toggle, n.contentIntent)
        }
        if (sbn.isOngoing && "clock" in sbn.packageName) {
            val shown = listOf(title(n), text(n)).firstOrNull { TIME_TEXT.matches(it) } ?: return null
            val label = listOf(title(n), text(n)).firstOrNull { it != shown && it.isNotEmpty() } ?: "Timer"
            return TimerNow(sbn.key, label, null, shown, toggle, n.contentIntent)
        }
        return null
    }

    private fun isLiveUpdate(n: Notification): Boolean =
        n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 ||
            template(n) == "android.app.Notification\$ProgressStyle"

    private fun liveFrom(context: Context, sbn: StatusBarNotification): LiveNow {
        val n = sbn.notification
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = n.extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val filled = if (max > 0 && !indeterminate) Math.round(4f * progress.coerceIn(0, max) / max) else null
        return LiveNow(
            key = sbn.key,
            app = appLabel(context, sbn.packageName),
            title = title(n),
            text = text(n),
            chip = n.shortCriticalText?.takeIf { it.isNotBlank() },
            filled = filled,
            icon = shrink(context, n.smallIcon),
            open = n.contentIntent,
        )
    }

    /**
     * Notification images can be large, and each card update must fit in one hand-off to the
     * launcher, so anything that is not a small resource icon is redrawn at 96 px.
     */
    private fun shrink(context: Context, icon: Icon?): Icon? {
        if (icon == null || icon.type == Icon.TYPE_RESOURCE) return icon
        return runCatching {
            val drawable = icon.loadDrawable(context) ?: return null
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            Icon.createWithBitmap(bitmap)
        }.getOrNull()
    }

    /** Splits a leading distance ("350 m Turn left…") from the instruction when the app puts both in one line. */
    private fun navFrom(context: Context, sbn: StatusBarNotification): NavNow {
        val n = sbn.notification
        val title = title(n)
        val text = text(n)
        val sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val match = DISTANCE.find(title)
        val (distance, instruction, detail) = when {
            match != null && match.groupValues[4].isNotEmpty() -> Triple(match.groupValues[1], match.groupValues[4], listOf(text, sub))
            match != null -> Triple(match.groupValues[1], text, listOf(sub))
            else -> Triple(null, title, listOf(text, sub))
        }
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = n.extras.getInt(Notification.EXTRA_PROGRESS, 0).coerceIn(0, maxOf(max, 0))
        val words = listOf(title, text, sub) + listOf(
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_TITLE_BIG,
        ).map { n.extras.getCharSequence(it)?.toString().orEmpty() } +
            n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        return NavNow(
            key = sbn.key,
            distance = distance ?: n.shortCriticalText?.takeIf { it.isNotBlank() },
            instruction = instruction.ifEmpty { title },
            detail = detail.filter { it.isNotEmpty() }.joinToString(" · "),
            icon = shrink(context, n.getLargeIcon()),
            open = n.contentIntent,
            progress = if (max > 0 && !n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) (1000L * progress / max).toInt() else null,
            progressColour = segmentColour(context, n, progress),
            mode = travelMode(words),
            platform = platform(words),
        )
    }

    private val PLATFORM = Regex("""\b(platform|plat\.?|track|stand|bay)\s*([A-Za-z]?\d{1,3}[A-Za-z]?|[A-Z])\b""", RegexOption.IGNORE_CASE)

    /** "Platform 3" from wording like "Platform 3", "Plat. 3", "Track 4" or "Stand B". */
    private fun platform(texts: List<String>): String? {
        val match = texts.firstNotNullOfOrNull { PLATFORM.find(it) } ?: return null
        val word = match.groupValues[1].lowercase().trimEnd('.')
        val label = when (word) {
            "plat" -> "Platform"
            else -> word.replaceFirstChar { it.uppercase() }
        }
        return "$label ${match.groupValues[2].uppercase()}"
    }

    /**
     * The colour of the route section the trip is in, from a progress-style notification's
     * segments (Maps colours each public-transport leg with its line's colour).
     */
    private fun segmentColour(context: Context, n: Notification, progress: Int): Int? = runCatching {
        val style = Notification.Builder.recoverBuilder(context, n).style as? Notification.ProgressStyle ?: return null
        var end = 0
        style.progressSegments.firstOrNull { segment ->
            end += segment.length
            progress <= end
        }?.color?.takeIf { it != 0 }?.let { it or 0xFF000000.toInt() }
    }.getOrNull()

    /**
     * The travel mode from the directions' wording, title first ("Ride to…", "Walk to…").
     * Maps does not say the mode outright, so with no clear word there is no badge.
     */
    private fun travelMode(texts: List<String>): TravelMode? {
        val rules = listOf(
            TravelMode.WALK to Regex("""\b(walk|walking|on foot)\b"""),
            TravelMode.BIKE to Regex("""\b(cycle|cycling|bike|biking)\b"""),
            TravelMode.BUS to Regex("""\b(bus|coach)\b"""),
            TravelMode.TRANSIT to Regex("""\b(ride|board|train|rail|tube|underground|subway|metro|tram|platform|stops?)\b"""),
            TravelMode.CAR to Regex("""\b(drive|driving)\b"""),
        )
        texts.map { it.lowercase() }.forEach { text ->
            rules.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
        }
        return null
    }

    /** A new incoming message: a conversation notification whose latest message is newer than before, not sent by the user. */
    private fun messageFrom(context: Context, sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification
        val isConversation = n.category == Notification.CATEGORY_MESSAGE || template(n) == "android.app.Notification\$MessagingStyle"
        if (!isConversation || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val style = runCatching { Notification.Builder.recoverBuilder(context, n).style as? Notification.MessagingStyle }.getOrNull()
        val latest = style?.messages?.lastOrNull()
        val at = latest?.timestamp ?: sbn.postTime
        val previous = lastMessageAt.put(sbn.key, at)
        if (previous != null && at <= previous) return null
        // A message with no sender is the user's own reply.
        if (style != null && latest != null && latest.senderPerson == null) return null
        val sender = latest?.senderPerson?.name?.toString() ?: title(n)
        val body = latest?.text?.toString() ?: text(n)
        if (sender.isEmpty() && body.isEmpty()) return null
        return IncomingMessage(
            key = sbn.key,
            app = appLabel(context, sbn.packageName),
            pkg = sbn.packageName,
            colour = appColour(sbn.packageName, n.color),
            sender = sender,
            text = body,
            open = n.contentIntent,
        )
    }

    /** The spec's badge colours for the apps it names; otherwise the app's own notification colour. */
    private fun appColour(pkg: String, notificationColour: Int): Int = when (pkg) {
        "com.whatsapp" -> 0xFF25D366.toInt()
        "com.google.android.apps.messaging" -> 0xFF38BDF8.toInt()
        "com.Slack" -> 0xFFE879F9.toInt()
        else -> if (notificationColour != 0 && notificationColour != Notification.COLOR_DEFAULT) notificationColour or 0xFF000000.toInt() else 0xFF38BDF8.toInt()
    }
}
