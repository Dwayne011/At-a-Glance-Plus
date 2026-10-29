package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ataglanceplus.Prefs
import com.ataglanceplus.live.MediaSource
import com.ataglanceplus.live.SportsRules
import com.ataglanceplus.live.TorchSource

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
            ACTION_TORCH_OFF -> TorchSource.turnOff(context)
            ACTION_TORCH_STEP -> TorchSource.setStep(context, intent.getIntExtra(EXTRA_VALUE, 1))
            ACTION_TORCH_NUDGE -> TorchSource.nudge(context, intent.getIntExtra(EXTRA_VALUE, 0))
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
        const val ACTION_TORCH_OFF = "com.ataglanceplus.action.TORCH_OFF"
        const val ACTION_TORCH_STEP = "com.ataglanceplus.action.TORCH_STEP"
        const val ACTION_TORCH_NUDGE = "com.ataglanceplus.action.TORCH_NUDGE"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_VALUE = "value"

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

        /** A step of the torch card's brightness bar (1 = dimmest). */
        fun torchStep(context: Context, step: Int): PendingIntent = valued(context, ACTION_TORCH_STEP, "step", step)

        /** The torch card's − (−1) and + (+1). */
        fun torchNudge(context: Context, by: Int): PendingIntent = valued(context, ACTION_TORCH_NUDGE, "nudge", by)

        /** The data URI keeps each value's PendingIntent separate. */
        private fun valued(context: Context, action: String, kind: String, value: Int): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.Builder().scheme("aagp").authority("torch").appendPath(kind).appendPath(value.toString()).build())
                .putExtra(EXTRA_VALUE, value)
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
