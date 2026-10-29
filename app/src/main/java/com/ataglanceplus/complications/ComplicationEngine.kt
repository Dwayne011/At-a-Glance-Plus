package com.ataglanceplus.complications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.Complication
import com.ataglanceplus.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps [ChipValues] current while At a Glance is connected. Values update on the system's own
 * signals where there is one (battery, alarm, call log, Gmail, notifications, Bluetooth) and on a
 * timer otherwise (screen time every 5 minutes, steps and sleep every 15). Everything refreshes
 * when the screen turns on.
 */
class ComplicationEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val handler = Handler(Looper.getMainLooper())
    private val buds: BudsSource = BudsSource(context) { onBudsChanged() }
    private val observers = mutableListOf<ContentObserver>()

    private fun onBudsChanged() = set(Complication.BUDS, buds.value())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> set(Complication.BATTERY, SimpleSources.battery(context))
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> set(Complication.ALARM, SimpleSources.alarm(context))
                Intent.ACTION_SCREEN_ON -> refreshAll()
            }
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        buds.start()
        watchContent()
        scope.launch { NotificationCounter.counts.collect { updateNotificationChips(it) } }
        scope.launch { refreshRequests.collect { refreshAll() } }
        scope.launch { WeatherRepository.weather.collect { set(Complication.SUNSET, SimpleSources.sunEvent(context)) } }
        // Once a minute: values that change with the clock (alarm "Fri" → time, countdown, sun).
        scope.launch {
            while (true) {
                refreshQuick()
                delay(MINUTE_MS)
            }
        }
        scope.launch {
            while (true) {
                refreshGmailAndScreen()
                delay(5 * MINUTE_MS)
            }
        }
        scope.launch {
            while (true) {
                refreshHealth()
                delay(15 * MINUTE_MS)
            }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { context.unregisterReceiver(receiver) }
        buds.stop()
        unwatchContent()
    }

    /** Chips, accounts, labels or permissions changed: start watching what is now needed. */
    fun settingsChanged() {
        unwatchContent()
        watchContent()
        buds.stop()
        buds.start()
        refreshAll()
    }

    fun refreshAll() {
        refreshQuick()
        scope.launch { refreshGmailAndScreen() }
        scope.launch { refreshHealth() }
        updateNotificationChips(NotificationCounter.counts.value)
    }

    private fun refreshQuick() {
        set(Complication.BATTERY, SimpleSources.battery(context))
        set(Complication.ALARM, SimpleSources.alarm(context))
        set(Complication.SUNSET, SimpleSources.sunEvent(context))
        set(Complication.COUNTDOWN, SimpleSources.countdown(context))
        set(Complication.BUDS, buds.value())
        scope.launch { set(Complication.MISSED_CALLS, withContext(Dispatchers.IO) { SimpleSources.missedCalls(context) }) }
    }

    private suspend fun refreshGmailAndScreen() {
        set(Complication.SCREEN_TIME, withContext(Dispatchers.IO) { SimpleSources.screenTime(context) })
        val accounts = gmailAccounts()
        accounts.forEach { account ->
            ChipValues.set(ChipSpec(Complication.GMAIL, account).key, withContext(Dispatchers.IO) { GmailSource.unread(context, account) })
        }
        // Forget chips that were removed.
        val gmailKeys = accounts.map { ChipSpec(Complication.GMAIL, it).key }.toSet()
        ChipValues.retain(ChipValues.values.value.keys.filter { !it.startsWith("gmail:") || it in gmailKeys }.toSet())
    }

    private suspend fun refreshHealth() {
        val granted = HealthSource.granted(context)
        set(Complication.STEPS, HealthSource.steps(context, granted))
        set(Complication.SLEEP, HealthSource.sleep(context, granted))
        selected().filter { it.kind == Complication.HEALTH }.forEach { spec ->
            HealthMetric.fromId(spec.arg)?.let { metric -> ChipValues.set(spec.key, HealthMetrics.value(context, metric, granted)) }
        }
    }

    private fun updateNotificationChips(counts: Map<String, Int>) {
        val sms = Telephony.Sms.getDefaultSmsPackage(context)
        set(Complication.TEXTS, sms?.let { counts[it] }?.takeIf { it > 0 }?.let { ChipValues.Value(SimpleSources.cap(it)) })
        selected().filter { it.kind == Complication.APP_NOTIFICATIONS && it.arg != null }.forEach { spec ->
            val count = counts[spec.arg] ?: 0
            ChipValues.set(
                spec.key,
                if (count > 0) ChipValues.Value(SimpleSources.cap(count), appIcon = NotificationCounter.icons.value[spec.arg]) else null
            )
        }
    }

    /** Call log and each chosen Gmail account tell us when they change. */
    private fun watchContent() {
        fun observe(uri: android.net.Uri, onChange: () -> Unit) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = onChange()
            }
            runCatching {
                context.contentResolver.registerContentObserver(uri, true, observer)
                observers += observer
            }
        }
        if (SimpleSources.hasCallLog(context)) {
            observe(CallLog.Calls.CONTENT_URI) { refreshQuick() }
        }
        if (GmailSource.hasPermission(context)) {
            gmailAccounts().forEach { account ->
                observe(GmailSource.labelsUri(account)) {
                    scope.launch {
                        ChipValues.set(ChipSpec(Complication.GMAIL, account).key, withContext(Dispatchers.IO) { GmailSource.unread(context, account) })
                    }
                }
            }
        }
    }

    private fun unwatchContent() {
        observers.forEach { context.contentResolver.unregisterContentObserver(it) }
        observers.clear()
    }

    private fun selected(): List<ChipSpec> = Prefs.chips(context).orEmpty()

    private fun gmailAccounts(): List<String> =
        selected().filter { it.kind == Complication.GMAIL }.mapNotNull { it.arg }.distinct()

    private fun set(kind: Complication, value: ChipValues.Value?) = ChipValues.set(kind.id, value)

    companion object {
        private const val MINUTE_MS = 60_000L

        private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Asks the running engine, if there is one, to refresh every chip now (a dashboard was pulled down). */
        fun requestRefresh() {
            refreshRequests.tryEmit(Unit)
        }
    }
}
