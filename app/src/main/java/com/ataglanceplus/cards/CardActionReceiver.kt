package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ataglanceplus.Prefs
import com.ataglanceplus.live.MediaSource
import com.ataglanceplus.live.SportsRules

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
            ACTION_SPORTS_STEP -> intent.getStringExtra(EXTRA_EVENT)?.let { Prefs.stepSportsRace(context, it, SportsRules.STEP_LASTS) }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.ataglanceplus.action.DISMISS"
        const val ACTION_MEDIA_TOGGLE = "com.ataglanceplus.action.MEDIA_TOGGLE"
        const val ACTION_MEDIA_PREV = "com.ataglanceplus.action.MEDIA_PREV"
        const val ACTION_MEDIA_NEXT = "com.ataglanceplus.action.MEDIA_NEXT"
        const val ACTION_SPORTS_STEP = "com.ataglanceplus.action.SPORTS_STEP"
        private const val EXTRA_CARD = "card"
        private const val EXTRA_EVENT = "event"

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

        /** Next on a race card: shows the weekend's following session. */
        fun sportsStep(context: Context, eventId: String): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java)
                .setAction(ACTION_SPORTS_STEP)
                .setData(Uri.Builder().scheme("aagp").authority("sports-step").appendPath(eventId).build())
                .putExtra(EXTRA_EVENT, eventId)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
