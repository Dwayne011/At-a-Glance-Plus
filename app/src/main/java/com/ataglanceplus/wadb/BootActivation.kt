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

/**
 * Updating the app stops its At a Glance service, so after an update a notification offers to
 * re-activate: Activate runs the same activation as after a restart; tapping it opens the app.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.isPaired(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val activate = PendingIntent.getForegroundService(
            context, 0, Intent(context, BootActivationService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle(context.getString(R.string.update_title, version(context)))
            .setContentText(context.getString(R.string.update_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.update_activate), activate).build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "a new version"

    companion object {
        private const val CHANNEL = "update"
        const val NOTIFICATION_ID = 12
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
        // Started from the after-update notification: that one has done its job.
        manager.cancel(UpdateReceiver.NOTIFICATION_ID)

        scope.launch {
            try {
                // Needs the secure-settings permission (granted at the first activation); without
                // it, wireless debugging must already be on.
                if (Wadb.canWriteSecureSettings(this@BootActivationService)) Wadb.enableWirelessDebugging(this@BootActivationService)
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
