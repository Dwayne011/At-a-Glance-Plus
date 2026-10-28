package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ataglanceplus.Prefs

/**
 * In-card buttons that change only the card itself: dismiss, play/pause, previous/next, the timer
 * button and the message toast's Reply. They open nothing, so they work without an unlock, like
 * media controls on the lock screen. Anything that opens an app goes through the unlock gate.
 *
 * Each handler writes a setting; the service sees the change and redraws the cards.
 */
class CardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> intent.getStringExtra(EXTRA_CARD)?.let { Prefs.dismiss(context, it) }
            ACTION_MEDIA_TOGGLE -> Prefs.setMediaPlaying(context, !Prefs.mediaPlaying(context))
            ACTION_MEDIA_PREV, ACTION_MEDIA_NEXT -> Prefs.setMediaRestarted(context, !Prefs.mediaRestarted(context))
            ACTION_TIMER_TOGGLE -> toggleTimer(context)
            ACTION_ISLAND_DISMISS -> Prefs.endIsland(context)
        }
    }

    private fun toggleTimer(context: Context) {
        val now = System.currentTimeMillis()
        val end = Prefs.timerEnd(context)
        when {
            end > now -> Prefs.setTimer(context, 0L, end - now) // running: pause
            end > 0L || Prefs.timerLeft(context) == 0L -> Prefs.setTimer(context, now + Prefs.TIMER_SAMPLE_MS, 0L) // finished: restart
            else -> Prefs.setTimer(context, now + Prefs.timerLeft(context), 0L) // paused: resume
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.ataglanceplus.action.DISMISS"
        const val ACTION_MEDIA_TOGGLE = "com.ataglanceplus.action.MEDIA_TOGGLE"
        const val ACTION_MEDIA_PREV = "com.ataglanceplus.action.MEDIA_PREV"
        const val ACTION_MEDIA_NEXT = "com.ataglanceplus.action.MEDIA_NEXT"
        const val ACTION_TIMER_TOGGLE = "com.ataglanceplus.action.TIMER_TOGGLE"
        const val ACTION_ISLAND_DISMISS = "com.ataglanceplus.action.ISLAND_DISMISS"
        private const val EXTRA_CARD = "card"

        fun action(context: Context, action: String): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun dismiss(context: Context, card: PreviewCard): PendingIntent {
            val intent = Intent(context, CardActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(android.net.Uri.parse("aagp://dismiss/${card.id}"))
                .putExtra(EXTRA_CARD, card.id)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
