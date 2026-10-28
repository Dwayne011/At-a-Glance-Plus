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
        _counts.value = active
            .filter { counts(it.notification) }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { messages(it.notification) } }
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

        private fun messages(n: Notification): Int {
            val list = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
            return list?.size?.coerceAtLeast(1) ?: 1
        }
    }
}
