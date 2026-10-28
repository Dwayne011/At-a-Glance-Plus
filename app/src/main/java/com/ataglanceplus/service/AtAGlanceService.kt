package com.ataglanceplus.service

import android.app.AlarmManager
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceSessionId
import android.app.smartspace.SmartspaceTargetEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.service.smartspace.SmartspaceService
import android.util.Log
import com.ataglanceplus.CrashLog
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.CardData
import com.ataglanceplus.cards.CardDeck
import com.ataglanceplus.cards.CardType
import com.ataglanceplus.cards.LiveCards
import com.ataglanceplus.cards.WeatherCard
import com.ataglanceplus.complications.ChipValues
import com.ataglanceplus.complications.ComplicationEngine
import com.ataglanceplus.complications.NotificationCounter
import com.ataglanceplus.live.CalendarNow
import com.ataglanceplus.live.CalendarSource
import com.ataglanceplus.live.IncomingMessage
import com.ataglanceplus.live.MediaSource
import com.ataglanceplus.live.NotificationHub
import com.ataglanceplus.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The At a Glance content provider.
 *
 * Once this component has been made the active Smartspace service (see the activation command on
 * the status screen), the system binds to it and every At a Glance client opens a session here:
 * Pixel Launcher for the home screen, SystemUI for the lock screen and always-on display, and a
 * few internal surfaces. For each session we answer with a list of targets (cards).
 *
 * Weather is always shown; every other card comes from real data on the phone (media sessions,
 * notifications, the calendar, alarms) and appears only while it has something to show.
 */
class AtAGlanceService : SmartspaceService() {

    private val sessions = LinkedHashMap<SmartspaceSessionId, SmartspaceConfig>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Prefs.NON_CARD_KEYS) return@OnSharedPreferenceChangeListener
        if (Prefs.isChipSettingKey(key)) engine.settingsChanged()
        // Calendar options or permission may have changed.
        refreshCalendar()
        pushAll()
    }

    /**
     * True while the screen is off, when the lock screen is drawn on the always-on display.
     * Whether SystemUI shows a new push while the phone dozes is not verified yet.
     */
    private var screenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            }
            pushAll()
        }
    }

    private val engine by lazy { ComplicationEngine(this) }

    private val media by lazy { MediaSource(this) }

    private var calendar: CalendarNow? = null
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshCalendar()
    }

    /** The message toast on show, and when it ends. */
    private var message: IncomingMessage? = null
    private var messageUntil = 0L
    private var islandJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        })
        try {
            engine.start()
        } catch (e: Exception) {
            CrashLog.record(this, "Starting the chips", e)
        }
        runCatching { contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver) }
        refreshCalendar()
        // Media sessions are visible once notification access is on; start (again) when it is.
        media.start()
        scope.launch {
            NotificationCounter.connected.collect { if (it) media.start() }
        }
        // Real cards: redraw when what they show changes.
        scope.launch { MediaSource.now.drop(1).collect { pushAll() } }
        scope.launch { NotificationHub.timer.drop(1).distinctUntilChanged().collect { pushAll() } }
        scope.launch { NotificationHub.live.drop(1).distinctUntilChanged().collect { pushAll() } }
        scope.launch { NotificationHub.nav.drop(1).distinctUntilChanged().collect { pushAll() } }
        scope.launch { NotificationHub.messages.collect { showMessage(it) } }
        // The media track bar cannot move by itself; nudge it while playing.
        scope.launch {
            while (true) {
                delay(TRACK_BAR_MS)
                if (MediaSource.now.value?.playing == true && !Prefs.mediaWave(this@AtAGlanceService)) pushAll()
            }
        }
        // A chip value changed: redraw.
        scope.launch {
            ChipValues.values.drop(1).distinctUntilChanged().collect { pushAll() }
        }
        WeatherRepository.loadCached(this)
        // New weather: redraw every surface.
        scope.launch {
            WeatherRepository.weather.drop(1).distinctUntilChanged().collect { pushAll() }
        }
        // While there is no weather, the card shows the reason; redraw when it changes.
        scope.launch {
            WeatherRepository.status.drop(1).distinctUntilChanged().collect {
                if (WeatherRepository.weather.value == null) pushAll()
            }
        }
        // Keep weather fresh while At a Glance is connected (the repository skips recent readings).
        scope.launch {
            while (true) {
                WeatherRepository.refresh(this@AtAGlanceService)
                delay(WEATHER_CHECK_MS)
            }
        }
        // Roll the date over at midnight (checked every minute; cheap, and survives time-zone changes).
        scope.launch {
            // Also redraw at sunrise and sunset, so the weather icon and colours follow the sun.
            var shownDate = WeatherCard.dateText()
            var shownDay = WeatherRepository.weather.value?.isDayAt()
            while (true) {
                delay(DATE_CHECK_MS)
                val now = WeatherCard.dateText()
                val day = WeatherRepository.weather.value?.isDayAt()
                // The calendar card counts down ("in 25 min") and the alarm card appears 12 hours
                // ahead, so those redraw each minute too.
                refreshCalendar()
                if (now != shownDate || day != shownDay || calendar != null || LiveCards.upcomingAlarm(this@AtAGlanceService) != null) {
                    shownDate = now
                    shownDay = day
                    pushAll()
                }
            }
        }
        ServiceState.event("Service created")
        Log.i(TAG, "onCreate")
    }

    override fun onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        unregisterReceiver(screenReceiver)
        contentResolver.unregisterContentObserver(calendarObserver)
        media.stop()
        engine.stop()
        scope.cancel()
        sessions.keys.forEach { ServiceState.sessionDestroyed(it.id) }
        sessions.clear()
        ServiceState.event("Service destroyed")
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onCreateSmartspaceSession(config: SmartspaceConfig, sessionId: SmartspaceSessionId) {
        Log.i(TAG, "session created: ${sessionId.id} surface=${config.uiSurface} client=${config.packageName} count=${config.smartspaceTargetCount}")
        sessions[sessionId] = config
        ServiceState.sessionCreated(
            SessionInfo(
                id = sessionId.id,
                surface = config.uiSurface,
                clientPackage = config.packageName,
                targetCount = config.smartspaceTargetCount,
                lastPushAt = 0L
            )
        )
        push(sessionId)
    }

    override fun onRequestSmartspaceUpdate(sessionId: SmartspaceSessionId) {
        push(sessionId)
        scope.launch { WeatherRepository.refresh(this@AtAGlanceService) }
    }

    override fun notifySmartspaceEvent(sessionId: SmartspaceSessionId, event: SmartspaceTargetEvent) {
        val name = when (event.eventType) {
            SmartspaceTargetEvent.EVENT_TARGET_INTERACTION -> "interaction"
            SmartspaceTargetEvent.EVENT_TARGET_SHOWN -> "shown"
            SmartspaceTargetEvent.EVENT_TARGET_HIDDEN -> "hidden"
            SmartspaceTargetEvent.EVENT_TARGET_DISMISS -> "dismiss"
            SmartspaceTargetEvent.EVENT_TARGET_BLOCK -> "block"
            SmartspaceTargetEvent.EVENT_UI_SURFACE_SHOWN -> "surface shown"
            SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN -> "surface hidden"
            else -> "event ${event.eventType}"
        }
        val surface = sessions[sessionId]?.uiSurface ?: "?"
        ServiceState.event("$name on $surface")
        // Dismissed from the launcher's own menu: same as the card's ✕. The weather card stays.
        if (event.eventType == SmartspaceTargetEvent.EVENT_TARGET_DISMISS || event.eventType == SmartspaceTargetEvent.EVENT_TARGET_BLOCK) {
            val targetId = event.smartspaceTarget?.smartspaceTargetId
            when {
                targetId == LiveCards.ISLAND_TARGET_ID -> endMessage()
                targetId != null -> LiveCards.instanceKey(targetId)?.let { Prefs.dismiss(this, it) } ?: pushAll()
            }
        }
        Log.d(TAG, "event: $name session=${sessionId.id} target=${event.smartspaceTarget?.smartspaceTargetId}")
    }

    override fun onDestroySmartspaceSession(sessionId: SmartspaceSessionId) {
        Log.i(TAG, "session destroyed: ${sessionId.id}")
        sessions.remove(sessionId)
        ServiceState.sessionDestroyed(sessionId.id)
    }

    override fun onDestroy(sessionId: SmartspaceSessionId) {
        onDestroySmartspaceSession(sessionId)
    }

    private fun pushAll() {
        sessions.keys.toList().forEach { push(it) }
    }

    private fun push(sessionId: SmartspaceSessionId) {
        val config = sessions[sessionId] ?: return
        val aod = config.uiSurface == SURFACE_LOCKSCREEN && (screenOff || Prefs.aodPreview(this))
        // A card that fails to build must not take the whole app down: log it and show weather alone.
        val targets = try {
            val data = cardData()
            CardDeck.build(this, config.uiSurface, config.smartspaceTargetCount, aod, data).also {
                Prefs.pruneDismissed(this, CardDeck.activeInstances(this, data))
            }
        } catch (e: Exception) {
            CrashLog.record(this, "Building cards for ${config.uiSurface}", e)
            listOf(WeatherCard.build(this, config.uiSurface, aod, WeatherRepository.weather.value, WeatherRepository.status.value))
        }
        try {
            updateSmartspaceTargets(sessionId, targets)
        } catch (e: Exception) {
            CrashLog.record(this, "Sending cards to ${config.uiSurface}", e)
            return
        }
        ServiceState.sessionPushed(sessionId.id, System.currentTimeMillis())
        Log.d(TAG, "pushed ${targets.size} target(s) to ${config.uiSurface}")
    }

    private fun cardData() = CardData(
        weather = WeatherRepository.weather.value,
        weatherStatus = WeatherRepository.status.value,
        nav = NotificationHub.nav.value,
        live = NotificationHub.live.value,
        timer = NotificationHub.timer.value,
        media = MediaSource.now.value,
        calendar = calendar,
        message = message,
        messageRemainingMs = (messageUntil - System.currentTimeMillis()).coerceAtLeast(0L),
    )

    private fun refreshCalendar() {
        scope.launch {
            val next = withContext(Dispatchers.IO) { CalendarSource.next(this@AtAGlanceService) }
            if (next != calendar) {
                calendar = next
                pushAll()
            }
        }
    }

    /**
     * A new message: show the toast in first place for 4.5 seconds, redrawing every half second to
     * step its countdown bar. A newer message replaces it and restarts the countdown.
     */
    private fun showMessage(incoming: IncomingMessage) {
        if (!Prefs.cardEnabled(this, CardType.MESSAGES) || screenOff) return
        message = incoming
        messageUntil = System.currentTimeMillis() + LiveCards.ISLAND_MS
        islandJob?.cancel()
        islandJob = scope.launch {
            while (true) {
                pushAll()
                val left = messageUntil - System.currentTimeMillis()
                if (left <= 0) break
                delay(minOf(ISLAND_STEP_MS, left))
            }
            endMessage()
        }
    }

    private fun endMessage() {
        islandJob?.cancel()
        message = null
        messageUntil = 0L
        pushAll()
    }

    companion object {
        private const val TAG = "AtAGlanceService"

        const val SURFACE_HOME = "home"
        const val SURFACE_LOCKSCREEN = "lockscreen"

        private const val WEATHER_CHECK_MS = 5 * 60 * 1000L
        private const val DATE_CHECK_MS = 60 * 1000L
        private const val ISLAND_STEP_MS = 500L
        private const val TRACK_BAR_MS = 30_000L
    }
}
