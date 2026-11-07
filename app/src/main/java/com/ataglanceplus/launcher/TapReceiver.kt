package com.ataglanceplus.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires a card's pending intent when a launcher reports a tap on it. */
class TapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val cardId = intent.getStringExtra(EXTRA_CARD) ?: return
        val tap = LauncherCards.tap(cardId) ?: return
        try {
            tap.send()
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Card $cardId tap was cancelled", e)
        }
    }

    companion object {
        private const val TAG = "TapReceiver"
        const val ACTION_TAP = "com.ataglanceplus.action.LAUNCHER_TAP"
        const val EXTRA_CARD = "card"
    }
}
