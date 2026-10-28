package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ataglanceplus.Prefs
import com.ataglanceplus.live.MediaSource

/**
 * In-card buttons that do not open anything: dismiss, and the media controls. They work without
 * an unlock, like media controls on the lock screen. Anything that opens an app goes through the
 * unlock gate.
 */
class CardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> intent.getStringExtra(EXTRA_CARD)?.let { Prefs.dismiss(context, it) }
            ACTION_MEDIA_TOGGLE -> MediaSource.togglePlay()
            ACTION_MEDIA_PREV -> MediaSource.previous()
            ACTION_MEDIA_NEXT -> MediaSource.next()
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.ataglanceplus.action.DISMISS"
        const val ACTION_MEDIA_TOGGLE = "com.ataglanceplus.action.MEDIA_TOGGLE"
        const val ACTION_MEDIA_PREV = "com.ataglanceplus.action.MEDIA_PREV"
        const val ACTION_MEDIA_NEXT = "com.ataglanceplus.action.MEDIA_NEXT"
        private const val EXTRA_CARD = "card"

        fun action(context: Context, action: String): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Dismisses one card instance. The data URI keeps each instance's PendingIntent separate. */
        fun dismiss(context: Context, instanceKey: String): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(Uri.Builder().scheme("aagp").authority("dismiss").appendPath(instanceKey).build())
                .putExtra(EXTRA_CARD, instanceKey)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
