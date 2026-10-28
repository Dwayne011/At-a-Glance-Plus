package com.ataglanceplus.service

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceSessionId
import android.app.smartspace.SmartspaceTargetEvent
import android.content.SharedPreferences
import android.service.smartspace.SmartspaceService
import android.util.Log
import com.ataglanceplus.Prefs
import com.ataglanceplus.cards.DateWeatherCard
import com.ataglanceplus.cards.TestCards
import com.ataglanceplus.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Milestone 1: one test card per surface, drawn either as a Google template or as our own layout.
 */
class AtAGlanceService : SmartspaceService() {

    private val sessions = LinkedHashMap<SmartspaceSessionId, SmartspaceConfig>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_CARD_STYLE || key == Prefs.KEY_SHOW_TEST_CARD) pushAll()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        WeatherRepository.loadCached(this)
        // New weather: redraw every surface.
        scope.launch {
            WeatherRepository.weather.drop(1).distinctUntilChanged().collect { pushAll() }
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
            var shownDate = DateWeatherCard.dateText()
            while (true) {
                delay(DATE_CHECK_MS)
                val now = DateWeatherCard.dateText()
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
        val targets = TestCards.build(this, config.uiSurface, Prefs.cardStyle(this), WeatherRepository.weather.value)
        updateSmartspaceTargets(sessionId, targets)
        ServiceState.sessionPushed(sessionId.id, System.currentTimeMillis())
        Log.d(TAG, "pushed ${targets.size} target(s) to ${config.uiSurface}")
    }

    companion object {
        private const val TAG = "AtAGlanceService"

        const val SURFACE_HOME = "home"
        const val SURFACE_LOCKSCREEN = "lockscreen"

        private const val WEATHER_CHECK_MS = 5 * 60 * 1000L
        private const val DATE_CHECK_MS = 60 * 1000L
    }
}
