package com.ataglanceplus.service

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceSessionId
import android.app.smartspace.SmartspaceTargetEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.service.smartspace.SmartspaceService
import android.util.Log
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.CardDeck
import com.ataglanceplus.cards.PreviewCards
import com.ataglanceplus.cards.WeatherCard
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

/**
 * The At a Glance content provider.
 *
 * Once this component has been made the active Smartspace service (see the activation command on
 * the status screen), the system binds to it and every At a Glance client opens a session here:
 * Pixel Launcher for the home screen, SystemUI for the lock screen and always-on display, and a
 * few internal surfaces. For each session we answer with a list of targets (cards).
 *
 * The weather card is real; the other cards show the design spec's sample content (preview mode)
 * until their data sources are built.
 */
class AtAGlanceService : SmartspaceService() {

    private val sessions = LinkedHashMap<SmartspaceSessionId, SmartspaceConfig>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Prefs.NON_CARD_KEYS) return@OnSharedPreferenceChangeListener
        when (key) {
            Prefs.KEY_ISLAND_UNTIL -> runIsland()
            Prefs.KEY_TIMER_END -> watchTimer()
        }
        pushAll()
    }

    /**
     * True while the screen is off, when the lock screen is drawn on the always-on display.
     * Whether SystemUI shows a new push while the phone dozes is not verified yet.
     */
    private var screenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            pushAll()
        }
    }

    private var islandJob: Job? = null
    private var timerJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        runIsland()
        watchTimer()
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
            var shownDate = WeatherCard.dateText()
            while (true) {
                delay(DATE_CHECK_MS)
                val now = WeatherCard.dateText()
                if (now != shownDate) {
                    shownDate = now
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
                targetId == PreviewCards.ISLAND_TARGET_ID -> Prefs.endIsland(this)
                targetId != null -> CardDeck.dismissibleId(targetId)?.let { Prefs.dismiss(this, it) } ?: pushAll()
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
        val island = (Prefs.islandUntil(this) - System.currentTimeMillis()).coerceAtLeast(0L)
        val targets = CardDeck.build(
            this, config.uiSurface, config.smartspaceTargetCount, aod,
            WeatherRepository.weather.value, WeatherRepository.status.value, island
        )
        updateSmartspaceTargets(sessionId, targets)
        ServiceState.sessionPushed(sessionId.id, System.currentTimeMillis())
        Log.d(TAG, "pushed ${targets.size} target(s) to ${config.uiSurface}")
    }

    /** While a message toast is showing, redraw every half second to step its countdown bar, then end it. */
    private fun runIsland() {
        islandJob?.cancel()
        if (Prefs.islandUntil(this) <= System.currentTimeMillis()) return
        islandJob = scope.launch {
            while (true) {
                val left = Prefs.islandUntil(this@AtAGlanceService) - System.currentTimeMillis()
                if (left <= 0) {
                    Prefs.endIsland(this@AtAGlanceService)
                    break
                }
                delay(minOf(ISLAND_STEP_MS, left))
                pushAll()
            }
        }
    }

    /** Redraw when the preview timer reaches zero, so it shows "Timer finished". */
    private fun watchTimer() {
        timerJob?.cancel()
        val left = Prefs.timerEnd(this) - System.currentTimeMillis()
        if (left <= 0) return
        timerJob = scope.launch {
            delay(left)
            pushAll()
        }
    }

    companion object {
        private const val TAG = "AtAGlanceService"

        const val SURFACE_HOME = "home"
        const val SURFACE_LOCKSCREEN = "lockscreen"

        private const val WEATHER_CHECK_MS = 5 * 60 * 1000L
        private const val DATE_CHECK_MS = 60 * 1000L
        private const val ISLAND_STEP_MS = 500L
    }
}
