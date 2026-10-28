package com.ataglanceplus.complications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts unread messages per app from the notifications on screen. Used by the unread-texts
 * chip (the default SMS app) and the app-notification chips.
 *
 * A conversation notification counts each message it holds; any other notification counts as
 * one. Group summaries and ongoing notifications (uploads, calls, music) are not counted.
 */
class NotificationCounter : NotificationListenerService() {

    override fun onListenerConnected() = recount()
    override fun onListenerDisconnected() {
        _counts.value = emptyMap()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        _counts.value = active
            .filter { counts(it.notification) }
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { messages(it.notification) } }
    }

    companion object {
        private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        /** Unread count by package. */
        val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

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
