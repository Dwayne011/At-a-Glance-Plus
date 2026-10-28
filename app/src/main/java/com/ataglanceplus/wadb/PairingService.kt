package com.ataglanceplus.wadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.adb.AdbInvalidPairingCodeException
import com.ataglanceplus.adb.AdbMdns
import com.ataglanceplus.adb.AdbPairingClient
import com.ataglanceplus.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Foreground service that drives pairing without a computer.
 *
 * 1. Watches for the pairing service adbd advertises while Developer options shows
 *    "Pair device with pairing code".
 * 2. Posts a notification with a text box; the user types the six-digit code there, so the
 *    pairing dialog can stay open.
 * 3. Runs the pairing handshake, then immediately activates At a Glance.
 */
class PairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var searchJob: Job? = null
    private var pairingPort = -1

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, searchingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                startSearch()
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()?.trim().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, pairingPort)
                startForeground(NOTIFICATION_ID, workingNotification(getString(R.string.pairing_working)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                if (code.length < 6 || port <= 0) {
                    finish(getString(R.string.pairing_failed), getString(R.string.pairing_code_invalid))
                } else {
                    pair(code, port)
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (searchJob?.isActive == true) return
        searchJob = scope.launch {
            AdbMdns.ports(this@PairingService, AdbMdns.TLS_PAIRING).collect { port ->
                Log.i(TAG, "pairing port: $port")
                if (port > 0) {
                    pairingPort = port
                    notificationManager.notify(NOTIFICATION_ID, inputNotification(port))
                }
            }
        }
    }

    private fun pair(code: String, port: Int) {
        searchJob?.cancel()
        scope.launch {
            val result = runCatching {
                AdbPairingClient("127.0.0.1", port, code, Wadb.key(this@PairingService)).use { it.start() }
            }
            result.onFailure { e ->
                Log.w(TAG, "pairing failed", e)
                val reason = when (e) {
                    is AdbInvalidPairingCodeException -> getString(R.string.pairing_code_wrong)
                    is ConnectException -> getString(R.string.pairing_port_closed)
                    else -> e.message ?: e.javaClass.simpleName
                }
                finish(getString(R.string.pairing_failed), reason)
            }
            result.onSuccess { ok ->
                if (!ok) {
                    finish(getString(R.string.pairing_failed), getString(R.string.pairing_rejected))
                    return@launch
                }
                Prefs.setPaired(this@PairingService, true)
                Log.i(TAG, "paired")
                notificationManager.notify(NOTIFICATION_ID, workingNotification(getString(R.string.pairing_activating)))
                when (val outcome = Activator.run(this@PairingService, Activator.Mode.ACTIVATE)) {
                    is Activator.Outcome.Success -> finish(getString(R.string.pairing_done), getString(R.string.pairing_done_text))
                    is Activator.Outcome.Failure -> finish(getString(R.string.pairing_done), getString(R.string.pairing_done_but, outcome.reason))
                }
            }
        }
    }

    private fun finish(title: String, text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(
            NOTIFICATION_ID,
            baseNotification().setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).build()
        )
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    private fun baseNotification(): Notification.Builder =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_card)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

    private fun searchingNotification(): Notification =
        baseNotification()
            .setContentTitle(getString(R.string.pairing_searching_title))
            .setContentText(getString(R.string.pairing_searching_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.pairing_searching_text)))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.pairing_cancel),
                    PendingIntent.getService(this, 2, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
                ).build()
            )
            .build()

    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(getString(R.string.pairing_code_label)).build()
        val reply = PendingIntent.getForegroundService(
            this, 1, replyIntent(this, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return baseNotification()
            .setContentTitle(getString(R.string.pairing_found_title))
            .setContentText(getString(R.string.pairing_found_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.pairing_found_text)))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.pairing_enter_code), reply)
                    .addRemoteInput(remoteInput)
                    .build()
            )
            .build()
    }

    private fun workingNotification(text: String): Notification =
        baseNotification().setContentTitle(text).setOngoing(true).setProgress(0, 0, true).build()

    companion object {
        private const val TAG = "PairingService"
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 10
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "port"

        fun startIntent(context: Context): Intent =
            Intent(context, PairingService::class.java).setAction(ACTION_START)

        private fun stopIntent(context: Context): Intent =
            Intent(context, PairingService::class.java).setAction(ACTION_STOP)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, PairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port)
    }
}
