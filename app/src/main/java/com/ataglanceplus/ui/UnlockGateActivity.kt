package com.ataglanceplus.ui

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Every tap on a card goes through here, so nothing opens from the lock screen without the phone
 * being unlocked first.
 *
 * The gate is allowed to appear over the lock screen (it is invisible) only so it can ask Android
 * to show the unlock prompt. The real target is sent only after the unlock succeeds; if the user
 * cancels, nothing opens.
 */
class UnlockGateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getParcelableExtra(EXTRA_TARGET, PendingIntent::class.java)
        if (target == null) {
            finish()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            open(target)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open(target)
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun open(target: PendingIntent) {
        try {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            target.send(this, 0, null, null, null, null, options.toBundle())
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "target no longer valid", e)
        }
        finish()
    }

    companion object {
        private const val TAG = "UnlockGate"
        private const val EXTRA_TARGET = "target"

        /**
         * Wraps [target] so that tapping it from the lock screen requires an unlock first.
         * [requestCode] must be unique per distinct target so PendingIntents do not overwrite each other.
         */
        fun wrap(context: Context, target: PendingIntent, requestCode: Int): PendingIntent {
            val intent = Intent(context, UnlockGateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_TARGET, target)
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
