package com.ataglanceplus.complications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ataglanceplus.live.NotificationHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's notification listener. It reads the notifications on screen for:
 * - unread counts per app (texts and app-notification chips),
 * - the timer, live-update and navigation cards, and new messages for the message toast
 *   (see [NotificationHub]).
 *
 * Its access also lets the media card find the playing app's media session.
 */
class NotificationCounter : NotificationListenerService() {

    override fun onListenerConnected() {
        _connected.value = true
        recount(null)
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        _counts.value = emptyMap()
        NotificationHub.update(this, emptyList(), null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount(null)

    private fun recount(posted: StatusBarNotification?) {
        val active = runCatching { activeNotifications }.getOrNull()?.toList() ?: return
        // Each app's status-bar icon, from its newest notification. The same icon keeps the same
        // object, so chip values only change when the icon really does.
        val previous = _icons.value
        _icons.value = active.groupBy { it.packageName }.mapNotNull { (pkg, list) ->
            val icon = list.maxByOrNull { it.postTime }?.notification?.smallIcon ?: return@mapNotNull null
            val old = previous[pkg]
            pkg to if (old != null && sameIcon(old, icon)) old else icon
        }.toMap()
        // Unread per app: every message in each conversation's notification, or, when the app's
        // summary says more ("12 messages from 3 chats"), that. Apps keep only recent messages in
        // a notification, so the summary can count further back.
        val summaries = active.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.mapNotNull { summaryCount(it.notification) }.maxOrNull() ?: 0 }
        val counted = active
            .filter { counts(it.notification) }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { messages(it.notification) } }
        _counts.value = counted.mapValues { (pkg, count) -> maxOf(count, summaries[pkg] ?: 0) }
        try {
            NotificationHub.update(this, active, posted)
        } catch (e: Exception) {
            com.ataglanceplus.CrashLog.record(this, "Reading notifications", e)
        }
    }

    companion object {
        private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        /** Unread count by package. */
        val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

        private val _icons = MutableStateFlow<Map<String, android.graphics.drawable.Icon>>(emptyMap())
        /** Status-bar icon by package, for the app-notification chips. */
        val icons: StateFlow<Map<String, android.graphics.drawable.Icon>> = _icons.asStateFlow()

        private fun sameIcon(a: android.graphics.drawable.Icon, b: android.graphics.drawable.Icon): Boolean =
            a.type == android.graphics.drawable.Icon.TYPE_RESOURCE && b.type == android.graphics.drawable.Icon.TYPE_RESOURCE &&
                a.resPackage == b.resPackage && a.resId == b.resId

        private val _connected = MutableStateFlow(false)
        /** True while the system has the listener bound (access granted and running). */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun component(context: Context) = ComponentName(context, NotificationCounter::class.java)

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

        private fun counts(n: Notification): Boolean =
            n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                n.flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                n.flags and Notification.FLAG_FOREGROUND_SERVICE == 0

        /** Messages in one notification: its message list, or the count the app gives it, whichever is more. */
        private fun messages(n: Notification): Int {
            val list = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
            return maxOf(list?.size ?: 1, n.number, 1)
        }

        private val COUNT = Regex("""\d+""")

        /**
         * The message count in a messaging app's summary ("12 messages from 3 chats"): the first
         * number in its summary line, only when the summary is about messages.
         */
        private fun summaryCount(n: Notification): Int? {
            if (n.category != Notification.CATEGORY_MESSAGE && n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) == null &&
                n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java) == null
            ) return null
            val line = listOf(Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEXT)
                .firstNotNullOfOrNull { key -> n.extras.getCharSequence(key)?.toString()?.takeIf { COUNT.containsMatchIn(it) } }
            return line?.let { COUNT.find(it)?.value?.toIntOrNull() }?.takeIf { it in 1..9999 } ?: n.number.takeIf { it > 0 }
        }
    }
}
