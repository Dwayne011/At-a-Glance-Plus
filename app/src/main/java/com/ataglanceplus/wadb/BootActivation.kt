package com.ataglanceplus.wadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After a reboot the phone is back on Google's provider. If the app is paired and may switch
 * wireless debugging on by itself, re-run the activation once Wi-Fi is up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.activateOnBoot(context) || !Prefs.isPaired(context)) return
        if (!Wadb.canWriteSecureSettings(context)) {
            Log.w(TAG, "cannot activate on boot: secure settings permission missing")
            return
        }
        context.startForegroundService(Intent(context, BootActivationService::class.java))
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/** Foreground service so the boot-time activation is not killed while it waits for Wi-Fi. */
class BootActivationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.boot_channel), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(getString(R.string.boot_waiting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        scope.launch {
            try {
                Wadb.enableWirelessDebugging(this@BootActivationService)
                if (!Wadb.awaitWifi(this@BootActivationService, WIFI_TIMEOUT_MS)) {
                    Log.w(TAG, "no Wi-Fi within timeout, giving up")
                    return@launch
                }
                // adbd takes a moment to start advertising after Wi-Fi connects.
                var attempt = 0
                while (attempt < ATTEMPTS) {
                    attempt++
                    manager.notify(NOTIFICATION_ID, notification(getString(R.string.boot_activating)))
                    when (val outcome = Activator.run(this@BootActivationService, Activator.Mode.ACTIVATE)) {
                        is Activator.Outcome.Success -> {
                            Log.i(TAG, "activated on boot")
                            return@launch
                        }
                        is Activator.Outcome.Failure -> Log.w(TAG, "attempt $attempt failed: ${outcome.reason}")
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "BootActivation"
        const val CHANNEL = "boot"
        const val NOTIFICATION_ID = 11
        const val WIFI_TIMEOUT_MS = 10 * 60 * 1000L
        const val ATTEMPTS = 4
        const val RETRY_DELAY_MS = 15_000L
    }
}
