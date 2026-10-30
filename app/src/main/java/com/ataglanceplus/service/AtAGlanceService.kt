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
import com.ataglanceplus.live.FinanceRules
import com.ataglanceplus.live.FinanceSource
import com.ataglanceplus.live.IncomingMessage
import com.ataglanceplus.live.MediaSource
import com.ataglanceplus.live.NotificationHub
import com.ataglanceplus.live.SportsRules
import com.ataglanceplus.live.SportsSource
import com.ataglanceplus.live.TorchSource
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
        if (Prefs.isFinanceFetchKey(key)) restartFinance()
        if (Prefs.isSportsFollowKey(key)) restartSports()
        if (key == com.ataglanceplus.wallet.WalletRules.KEY_RULES) com.ataglanceplus.wallet.WalletGeofences.refresh(this)
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
            if (intent.action == Intent.ACTION_SCREEN_ON) refreshIfStale()
            pushAll()
        }
    }

    private val engine by lazy { ComplicationEngine(this) }

    private val media by lazy { MediaSource(this) }

    private var calendars: List<CalendarNow> = emptyList()
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshCalendar()
    }

    /**
     * The message toast on show, and when it ends at the latest. [islandSeen] holds the sessions
     * that have shown it, so a later page change there can dismiss it.
     */
    private var message: IncomingMessage? = null
    private var messageUntil = 0L
    private var islandJob: Job? = null
    private val islandSeen = mutableSetOf<SmartspaceSessionId>()

    /**
     * When prices and sports were last fetched, by the wall clock. The refresh loops' own delays
     * stop while the phone is in deep sleep, so these say whether the cards are out of date when
     * the screen comes back on.
     */
    private var financeFetchedAt = 0L
    private var sportsScheduledAt = 0L
    private var sportsCheckedAt = 0L
    private var financeJob: Job? = null
    private var sportsJob: Job? = null
    private var walletJob: Job? = null
    private var walletSyncedAt = 0L
    private var shownFinance: String? = null

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
        scope.launch { FinanceSource.quotes.drop(1).collect { pushAll() } }
        restartFinance()
        scope.launch { SportsSource.games.drop(1).collect { pushAll() } }
        scope.launch { SportsSource.races.drop(1).collect { pushAll() } }
        TorchSource.start(this)
        scope.launch { TorchSource.torch.drop(1).collect { pushAll() } }
        restartSports()
        // Wallet passes: redraw when they change or when a geofence/location says so.
        com.ataglanceplus.wallet.WalletSource.ensureLoaded(this)
        scope.launch { com.ataglanceplus.wallet.WalletSource.passes.drop(1).collect { pushAll() } }
        scope.launch { com.ataglanceplus.wallet.WalletGeofences.changed.drop(1).collect { pushAll() } }
        restartWalletSync()
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
                // The finance card comes and goes with the time (periods and windows).
                val finance = FinanceRules.showing(this@AtAGlanceService, FinanceSource.quotes.value)?.instanceKey
                // Sports cards count down to kick-off and come and go with the time.
                val sportsOn = Prefs.sportsFollowed(this@AtAGlanceService).isNotEmpty()
                // Wallet passes come and go with the time (windows and flight/event/transit times).
                val walletOn = Prefs.cardEnabled(this@AtAGlanceService, CardType.WALLET) &&
                    com.ataglanceplus.wallet.WalletRules.rules(this@AtAGlanceService).isNotEmpty()
                if (now != shownDate || day != shownDay || calendars.isNotEmpty() || finance != shownFinance || sportsOn || walletOn ||
                    LiveCards.upcomingAlarm(this@AtAGlanceService) != null
                ) {
                    shownFinance = finance
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
        TorchSource.stop(this)
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
        val targetId = event.smartspaceTarget?.smartspaceTargetId
        ServiceState.event("$name on $surface" + (targetId?.let { " (${CardDeck.label(it)})" } ?: ""))
        // The message stays until it is swiped past (another page shown after it was), tapped,
        // or replaced. Events from before it was shown on this surface do not count.
        if (message != null) {
            val island = targetId == LiveCards.ISLAND_TARGET_ID
            when (event.eventType) {
                SmartspaceTargetEvent.EVENT_TARGET_SHOWN ->
                    if (island) islandSeen += sessionId else if (targetId != null && sessionId in islandSeen) endMessage()
                SmartspaceTargetEvent.EVENT_TARGET_INTERACTION -> if (island) endMessage()
            }
        }
        // Dismissed from the launcher's own menu: same as the card's ✕. The weather card stays.
        if (event.eventType == SmartspaceTargetEvent.EVENT_TARGET_DISMISS || event.eventType == SmartspaceTargetEvent.EVENT_TARGET_BLOCK) {
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
        islandSeen.remove(sessionId)
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
            val data = cardData(sessionId)
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
        ServiceState.sessionPushed(sessionId.id, System.currentTimeMillis(), targets.map { CardDeck.label(it.smartspaceTargetId) })
        Log.d(TAG, "pushed ${targets.size} target(s) to ${config.uiSurface}")
    }

    private fun cardData(sessionId: SmartspaceSessionId) = CardData(
        weather = WeatherRepository.weather.value,
        weatherStatus = WeatherRepository.status.value,
        nav = NotificationHub.nav.value,
        live = NotificationHub.live.value,
        timer = NotificationHub.timer.value,
        media = MediaSource.now.value,
        calendars = calendars,
        quotes = FinanceSource.quotes.value,
        torch = TorchSource.torch.value,
        sports = runCatching { SportsRules.showing(this, SportsSource.games.value, SportsSource.races.value) }.getOrDefault(emptyList()),
        message = message,
        messageRemainingMs = (messageUntil - System.currentTimeMillis()).coerceAtLeast(0L),
        messageEntering = sessionId !in islandSeen,
        wallet = runCatching {
            val ids = com.ataglanceplus.wallet.WalletRules.activePassIds(
                this, System.currentTimeMillis(), com.ataglanceplus.wallet.WalletGeofences.cachedLocation,
            )
            com.ataglanceplus.wallet.WalletSource.passes.value.filter { it.id in ids }
        }.getOrDefault(emptyList()),
    )

    /** Fetches prices now and then at the user's interval, while the finance card is on and has tickers. */
    private fun restartFinance() {
        financeJob?.cancel()
        financeJob = scope.launch {
            while (true) {
                if (Prefs.cardEnabled(this@AtAGlanceService, CardType.FINANCE)) {
                    runCatching { FinanceSource.refresh(this@AtAGlanceService) }
                        .onFailure { CrashLog.record(this@AtAGlanceService, "Fetching prices", it) }
                    financeFetchedAt = System.currentTimeMillis()
                }
                delay(Prefs.financeRefreshMinutes(this@AtAGlanceService) * 60_000L)
            }
        }
    }

    /**
     * Sports: schedules now and every half hour; while a game or session is on (or about to
     * start), live scores every 30 seconds, otherwise a check every 5 minutes.
     */
    private fun restartSports() {
        sportsJob?.cancel()
        if (Prefs.sportsFollowed(this).isEmpty()) return
        sportsJob = scope.launch {
            while (true) {
                if (Prefs.cardEnabled(this@AtAGlanceService, CardType.SPORTS)) {
                    if (System.currentTimeMillis() - sportsScheduledAt > SPORTS_SCHEDULE_MS) {
                        runCatching { SportsSource.refreshSchedules(this@AtAGlanceService) }
                            .onFailure { CrashLog.record(this@AtAGlanceService, "Fetching sports schedules", it) }
                        sportsScheduledAt = System.currentTimeMillis()
                    }
                    sportsCheckedAt = System.currentTimeMillis()
                    val live = runCatching { SportsSource.refreshLive(this@AtAGlanceService) }
                        .onFailure { CrashLog.record(this@AtAGlanceService, "Fetching live scores", it) }
                        .getOrDefault(false)
                    delay(if (live) 30_000L else 5 * 60_000L)
                } else {
                    delay(5 * 60_000L)
                }
            }
        }
    }

    /**
     * The screen came on: fetch prices and scores again if their timers fell behind while the
     * phone slept, so the cards do not show yesterday's figures. Also ends a message past its time.
     */
    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - financeFetchedAt > Prefs.financeRefreshMinutes(this) * 60_000L) restartFinance()
        if (now - sportsScheduledAt > SPORTS_SCHEDULE_MS || now - sportsCheckedAt > SPORTS_STALE_MS) restartSports()
        if (com.ataglanceplus.wallet.GoogleAccount.isSignedIn(this)) {
            com.ataglanceplus.wallet.WalletGeofences.refreshLocation(this)
            if (now - walletSyncedAt > WALLET_SYNC_MS) restartWalletSync()
        }
        if (message != null && now >= messageUntil) endMessage()
    }

    /** Syncs Wallet passes now and every few hours while signed in, and keeps the geofences current. */
    private fun restartWalletSync() {
        walletJob?.cancel()
        if (!com.ataglanceplus.wallet.GoogleAccount.isSignedIn(this)) return
        com.ataglanceplus.wallet.WalletGeofences.refresh(this)
        com.ataglanceplus.wallet.WalletGeofences.refreshLocation(this)
        walletJob = scope.launch {
            while (true) {
                withContext(Dispatchers.IO) {
                    runCatching { com.ataglanceplus.wallet.WalletSource.sync(this@AtAGlanceService) }
                        .onFailure { CrashLog.record(this@AtAGlanceService, "Syncing Wallet", it) }
                }
                walletSyncedAt = System.currentTimeMillis()
                delay(WALLET_SYNC_MS)
            }
        }
    }

    private fun refreshCalendar() {
        scope.launch {
            val events = withContext(Dispatchers.IO) { CalendarSource.events(this@AtAGlanceService) }
            if (events != calendars) {
                calendars = events
                pushAll()
            }
        }
    }

    /**
     * A new message: show the toast in first place, drawn once, until it is swiped past, tapped,
     * dismissed or replaced by a newer message, and at most [MESSAGE_MAX_MS].
     */
    private fun showMessage(incoming: IncomingMessage) {
        if (!Prefs.cardEnabled(this, CardType.MESSAGES) || screenOff) return
        message = incoming
        messageUntil = System.currentTimeMillis() + MESSAGE_MAX_MS
        islandSeen.clear()
        islandJob?.cancel()
        islandJob = scope.launch {
            delay(MESSAGE_MAX_MS)
            endMessage()
        }
        pushAll()
    }

    private fun endMessage() {
        islandJob?.cancel()
        islandSeen.clear()
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
        private const val MESSAGE_MAX_MS = 10 * 60_000L
        private const val SPORTS_SCHEDULE_MS = 30 * 60_000L
        private const val SPORTS_STALE_MS = 60_000L
        private const val WALLET_SYNC_MS = 6 * 60 * 60_000L
        private const val TRACK_BAR_MS = 30_000L
    }
}
