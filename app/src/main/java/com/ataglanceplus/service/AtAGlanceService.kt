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
import kotlinx.coroutines.flow.first
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
    /** The screen's state as a flow, for work that waits for it to come on (live scores). */
    private val screenOn = kotlinx.coroutines.flow.MutableStateFlow(true)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                screenOff = intent.action == Intent.ACTION_SCREEN_OFF
                screenOn.value = !screenOff
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
    private var messageSince = 0L
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
    /** The passes the Wallet card last showed, for the history (null before the first draw). */
    private var walletShown: Set<String>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The theme the cards were last drawn in: they follow the phone's dark mode. */
    private var dark: Boolean? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = com.ataglanceplus.cards.PanelCards.isDark(this)
        if (dark != null && dark != now) pushAll()
        dark = now
    }

    override fun onCreate() {
        super.onCreate()
        dark = com.ataglanceplus.cards.PanelCards.isDark(this)
        // Started with the screen off (after a restart, say): know it from the start.
        screenOff = !getSystemService(android.os.PowerManager::class.java).isInteractive
        screenOn.value = !screenOff
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
        // The clock's upcoming-alarm notification came or went: the alarm card's skip changes.
        scope.launch { NotificationHub.alarmSkip.drop(1).distinctUntilChanged().collect { pushAll() } }
        scope.launch { com.ataglanceplus.weather.RainSource.changed.drop(1).collect { pushAll() } }
        scope.launch { com.ataglanceplus.live.CommuteSource.trip.drop(1).collect { pushAll() } }
        scope.launch { com.ataglanceplus.live.GmailMail.parcels.drop(1).distinctUntilChanged().collect { pushAll() } }
        scope.launch { NotificationHub.messages.collect { showMessage(it) } }
        // The message was read or cleared (its notification is gone): the pop-up goes too.
        scope.launch {
            NotificationHub.activeKeys.collect { keys -> message?.let { if (it.key !in keys) endMessage() } }
        }
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
                // Not with the screen off: the always-on display shows no moving track bar.
                if (!screenOff && MediaSource.now.value?.playing == true && !Prefs.mediaWave(this@AtAGlanceService)) pushAll()
            }
        }
        // The trip bar moves on between the maps app's updates: redraw it every 15 s while
        // navigating with the screen on.
        scope.launch {
            while (true) {
                delay(NAV_TRIP_MS)
                if (!screenOff && NotificationHub.nav.value?.progress != null) pushAll()
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
                // Rain and snow on the way: every 10 minutes while that card is on and the screen is.
                if (!screenOff && Prefs.cardEnabled(this@AtAGlanceService, CardType.RAIN)) {
                    com.ataglanceplus.weather.RainSource.refresh(this@AtAGlanceService)
                }
                // The commute: every 5 minutes in a commute window, with the screen on.
                if (!screenOff && Prefs.cardEnabled(this@AtAGlanceService, CardType.COMMUTE)) {
                    com.ataglanceplus.live.CommuteSource.refresh(this@AtAGlanceService)
                }
                // Parcels from Gmail: every 15 minutes with the screen on, once signed in to Google.
                if (!screenOff && Prefs.cardEnabled(this@AtAGlanceService, CardType.PARCELS) && com.ataglanceplus.wallet.GoogleAccount.isSignedIn(this@AtAGlanceService)) {
                    runCatching { com.ataglanceplus.live.GmailMail.refreshParcels(this@AtAGlanceService) }
                        .onFailure { CrashLog.record(this@AtAGlanceService, "Reading parcels from Gmail", it) }
                }
                delay(WEATHER_CHECK_MS)
            }
        }
        // Roll the date over at midnight (checked every minute; cheap, and survives time-zone changes).
        scope.launch {
            // Also redraw at sunrise and sunset, so the weather icon and colours follow the sun.
            var shownDate = WeatherCard.dateText()
            var shownDay = WeatherRepository.weather.value?.isDayAt()
            var shownRules = com.ataglanceplus.cards.CardRules.stateNow(this@AtAGlanceService)
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
                // Rain counts down ("in 20 min"); quiet hours and the card order change with the time.
                val rules = com.ataglanceplus.cards.CardRules.stateNow(this@AtAGlanceService)
                if (now != shownDate || day != shownDay || calendars.isNotEmpty() || finance != shownFinance || sportsOn || walletOn ||
                    LiveCards.upcomingAlarm(this@AtAGlanceService) != null || com.ataglanceplus.weather.RainSource.soon() != null || rules != shownRules ||
                    com.ataglanceplus.live.CommuteSource.direction(this@AtAGlanceService) != null
                ) {
                    shownRules = rules
                    shownFinance = finance
                    shownDate = now
                    shownDay = day
                    pushAll()
                }
            }
        }
        ServiceState.event("Service created")
        com.ataglanceplus.DebugLog.add(this, com.ataglanceplus.DebugLog.Kind.CONNECTION, "Service started (screen ${if (screenOff) "off" else "on"})")
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
        com.ataglanceplus.Uptime.mark(this, false)
        sessions.keys.forEach { ServiceState.sessionDestroyed(it.id) }
        sessions.clear()
        ServiceState.event("Service destroyed")
        com.ataglanceplus.DebugLog.add(this, com.ataglanceplus.DebugLog.Kind.CONNECTION, "Service stopped by the system (no launcher or lock screen bound)")
        // Nothing is connected any more, so the phone shows Google's cards: offer to switch back,
        // unless this is the app's own activation restarting the launcher and SystemUI.
        if (!com.ataglanceplus.wadb.Wadb.busy.value) com.ataglanceplus.wadb.UpdateReceiver.notifyDisconnected(this)
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onCreateSmartspaceSession(config: SmartspaceConfig, sessionId: SmartspaceSessionId) {
        Log.i(TAG, "session created: ${sessionId.id} surface=${config.uiSurface} client=${config.packageName} count=${config.smartspaceTargetCount}")
        sessions[sessionId] = config
        if (config.uiSurface == SURFACE_HOME) com.ataglanceplus.Uptime.mark(this, true)
        com.ataglanceplus.DebugLog.add(this, com.ataglanceplus.DebugLog.Kind.CONNECTION, "${surfaceName(config.uiSurface)} connected (${config.packageName}, ${config.smartspaceTargetCount} cards)")
        // Connected again: the "disconnected" notification has done its job.
        com.ataglanceplus.wadb.UpdateReceiver.clearDisconnected(this)
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
        sessions[sessionId]?.let { com.ataglanceplus.DebugLog.add(this, com.ataglanceplus.DebugLog.Kind.CONNECTION, "${surfaceName(it.uiSurface)} disconnected (${it.packageName})") }
        sessions.remove(sessionId)
        if (sessions.values.none { it.uiSurface == SURFACE_HOME }) com.ataglanceplus.Uptime.mark(this, false)
        islandSeen.remove(sessionId)
        ServiceState.sessionDestroyed(sessionId.id)
    }

    override fun onDestroy(sessionId: SmartspaceSessionId) {
        onDestroySmartspaceSession(sessionId)
    }

    private fun surfaceName(surface: String): String = when (surface) {
        SURFACE_HOME -> "Home screen"
        SURFACE_LOCKSCREEN -> "Lock screen"
        else -> "Surface \"$surface\""
    }

    /**
     * Redraws every surface. While the screen is off the home screen cannot be seen, so only the
     * lock screen (the always-on display) is redrawn; turning the screen on redraws them all.
     */
    private fun pushAll() {
        sessions.entries.toList().forEach { (id, config) ->
            if (!screenOff || config.uiSurface != SURFACE_HOME) push(id)
        }
    }

    private fun push(sessionId: SmartspaceSessionId) {
        val config = sessions[sessionId] ?: return
        val aod = config.uiSurface == SURFACE_LOCKSCREEN && (screenOff || Prefs.aodPreview(this))
        // A card that fails to build must not take the whole app down: log it and show weather alone.
        // No hold ring on the lock screen.
        com.ataglanceplus.cards.Style.onLockScreen = config.uiSurface == SURFACE_LOCKSCREEN
        val targets = try {
            val data = cardData(sessionId)
            CardDeck.build(this, config.uiSurface, config.smartspaceTargetCount, aod, data).also {
                Prefs.pruneDismissed(this, CardDeck.activeInstances(this, data))
            }
        } catch (e: Exception) {
            CrashLog.record(this, "Building cards for ${config.uiSurface}", e)
            listOf(WeatherCard.build(this, config.uiSurface, aod, WeatherRepository.weather.value, WeatherRepository.status.value))
        } finally {
            com.ataglanceplus.cards.Style.onLockScreen = false
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
        messageSince = messageSince,
        messageUntil = messageUntil,
        messageEntering = sessionId !in islandSeen,
        rain = if (Prefs.cardEnabled(this, CardType.RAIN)) com.ataglanceplus.weather.RainSource.soon() else null,
        commute = if (Prefs.cardEnabled(this, CardType.COMMUTE)) com.ataglanceplus.live.CommuteSource.showing(this) else null,
        parcel = if (Prefs.cardEnabled(this, CardType.PARCELS)) com.ataglanceplus.live.GmailMail.forCard() else null,
        wallet = runCatching {
            val ids = com.ataglanceplus.wallet.WalletRules.activePassIds(
                this, System.currentTimeMillis(), com.ataglanceplus.wallet.WalletGeofences.cachedLocation,
            )
            com.ataglanceplus.wallet.WalletSource.passes.value.filter { it.id in ids }.also { shown ->
                // The history notes each change in which passes the card shows.
                if (ids != walletShown) {
                    walletShown = ids
                    com.ataglanceplus.DebugLog.add(
                        this, com.ataglanceplus.DebugLog.Kind.WALLET,
                        if (shown.isEmpty()) "Wallet card: no pass to show" else "Wallet card shows: " + shown.joinToString(", ") { it.title },
                    )
                }
            }
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
     * start), live scores every 30 seconds, otherwise a check every 5 minutes. Paused while the
     * screen is off, except while a followed team plays and game alerts are on: then every minute.
     */
    private fun restartSports() {
        sportsJob?.cancel()
        if (Prefs.sportsFollowed(this).isEmpty()) return
        sportsJob = scope.launch {
            val context = this@AtAGlanceService
            while (true) {
                val alerts = com.ataglanceplus.live.SportsAlerts.enabled(context)
                if (!screenOn.value) {
                    if (!alerts) {
                        // Scores are fetched again the moment the screen comes on.
                        screenOn.first { it }
                    } else if (!SportsSource.teamGameLiveOrSoon()) {
                        // Nothing on: look again (at the schedules already fetched) in 5 minutes,
                        // or at once when the screen comes on.
                        kotlinx.coroutines.withTimeoutOrNull(5 * 60_000L) { screenOn.first { it } }
                        continue
                    }
                }
                if (Prefs.cardEnabled(context, CardType.SPORTS) || alerts || com.ataglanceplus.live.SportsCalendar.enabled(context)) {
                    if (System.currentTimeMillis() - sportsScheduledAt > SPORTS_SCHEDULE_MS) {
                        runCatching { SportsSource.refreshSchedules(context) }
                            .onFailure { CrashLog.record(context, "Fetching sports schedules", it) }
                        sportsScheduledAt = System.currentTimeMillis()
                        runCatching { com.ataglanceplus.live.SportsCalendar.sync(context) }
                            .onFailure { CrashLog.record(context, "Adding games to the calendar", it) }
                    }
                    sportsCheckedAt = System.currentTimeMillis()
                    val live = runCatching { SportsSource.refreshLive(context) }
                        .onFailure { CrashLog.record(context, "Fetching live scores", it) }
                        .getOrDefault(false)
                    delay(if (!live) 5 * 60_000L else if (screenOn.value) 30_000L else 60_000L)
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
            // A location for the pass rules at most every 15 minutes, not at every unlock; but near
            // one of the places, at every unlock (at most once a minute) and precisely, so the pass
            // shows as soon as the phone is unlocked there, however long Play services takes to
            // notice the arrival. Near: within 2 km of the last fix, plus how far the phone could
            // have gone since it (at 15 m/s, about 34 mph), so a drive to the shop still counts.
            val fix = com.ataglanceplus.wallet.WalletGeofences.cachedLocation
            val travelled = fix?.let { (now - it.time).coerceIn(0L, WALLET_LOCATION_MS) / 1000f * WALLET_TRAVEL_MPS } ?: 0f
            val near = fix != null && com.ataglanceplus.wallet.WalletGeofences.nearAPlace(this, fix, WALLET_NEAR_M + travelled)
            when {
                fix == null || now - fix.time > WALLET_LOCATION_MS -> com.ataglanceplus.wallet.WalletGeofences.refreshLocation(this, precise = near)
                near && now - fix.time > WALLET_NEAR_EVERY_MS -> com.ataglanceplus.wallet.WalletGeofences.refreshLocation(this, precise = true)
            }
            // Places not watched yet (a permission given in Android's settings, location turned
            // back on): try registering them again.
            if (com.ataglanceplus.wallet.WalletGeofences.status.value?.ok != true) com.ataglanceplus.wallet.WalletGeofences.refresh(this)
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
     * dismissed, read or cleared (its notification goes), or replaced by a newer message, and at
     * most [MESSAGE_MAX_MS].
     */
    private fun showMessage(incoming: IncomingMessage) {
        if (!Prefs.cardEnabled(this, CardType.MESSAGES) || screenOff) return
        message = incoming
        messageSince = System.currentTimeMillis()
        messageUntil = messageSince + MESSAGE_MAX_MS
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
        messageSince = 0L
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
        private const val NAV_TRIP_MS = 15_000L
        private const val WALLET_LOCATION_MS = 15 * 60_000L
        /** Near a place, a fresh location at unlock if the last is older than this. */
        private const val WALLET_NEAR_EVERY_MS = 60_000L
        /** How close to a place counts as near. */
        private const val WALLET_NEAR_M = 2_000f
        /** The speed assumed since the last fix, for what could be near now. */
        private const val WALLET_TRAVEL_MPS = 15f
    }
}
