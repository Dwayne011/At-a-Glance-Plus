package com.ataglanceplus

import android.content.Context
import android.content.SharedPreferences
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.ComplicationLayout
import com.ataglanceplus.cards.WeatherLayout
import com.ataglanceplus.cards.CardType

/** Small settings store. The service and the UI live in one process and both read it. */
object Prefs {

    private const val FILE = "settings"
    const val KEY_PAIRED = "wadb_paired"
    const val KEY_ACTIVATE_ON_BOOT = "activate_on_boot"

    private const val KEY_DISABLED_CARDS = "disabled_cards"
    private const val KEY_CALENDAR_LEAD = "calendar_lead_minutes"
    private const val KEY_CALENDAR_ALL_DAY = "calendar_all_day"
    private const val KEY_COMP_LAYOUT = "comp_layout"
    private const val KEY_WEATHER_LAYOUT = "weather_layout"
    private const val KEY_WEATHER_LOOK = "weather_look"
    private const val KEY_CHIPS = "chips"
    private const val KEY_DISMISSED = "dismissed_cards"
    private const val KEY_CHIP_APP = "chip_app_"
    private const val KEY_GMAIL_LABELS = "gmail_labels_"
    private const val KEY_COUNTDOWN = "countdown_day"
    private const val KEY_COUNTDOWN_NAME = "countdown_name"
    private const val KEY_COUNTDOWN_EVENT = "countdown_event"
    private const val KEY_STEP_GOAL = "step_goal"
    private const val KEY_CHIP_SIZE = "chip_size_"
    private const val KEY_CHIP_REFRESH = "chip_refresh"
    private const val KEY_AOD_PREVIEW = "aod_preview"
    private const val KEY_MEDIA_WAVE = "media_wave"
    private const val KEY_SPORTS_FOLLOWED = "sports_followed"
    private const val KEY_SPORTS_BEFORE = "sports_before_hours"
    private const val KEY_SPORTS_AFTER = "sports_after_hours"
    private const val KEY_SPORTS_NEXT = "sports_next_always"
    private const val KEY_SPORTS_STEP = "sports_race_step"
    private const val KEY_SPORTS_OPEN_GOOGLE = "sports_open_google"
    private const val KEY_FIN_TICKERS = "finance_tickers"
    private const val KEY_FIN_REFRESH = "finance_refresh_minutes"
    private const val KEY_FIN_MOVES = "finance_show_moves"
    private const val KEY_FIN_THRESHOLD = "finance_move_threshold"
    private const val KEY_FIN_PERIODIC = "finance_show_periodic"
    private const val KEY_FIN_PERIOD_HOURS = "finance_period_hours"
    private const val KEY_FIN_WINDOWS_ON = "finance_show_windows"
    private const val KEY_FIN_WINDOWS = "finance_windows"

    /** Keys that only matter to the wireless-debugging screens; changing them needs no redraw. */
    val NON_CARD_KEYS = setOf(KEY_PAIRED, KEY_ACTIVATE_ON_BOOT)

    /** Settings that change what the complication engine has to watch. */
    fun isChipSettingKey(key: String?): Boolean =
        key != null && (key == KEY_CHIPS || key.startsWith(KEY_GMAIL_LABELS) || key == KEY_COUNTDOWN || key == KEY_CHIP_REFRESH ||
            key == KEY_COUNTDOWN_NAME || key == KEY_STEP_GOAL || key == KEY_COUNTDOWN_EVENT)

    /** Tells the service a chip permission was granted, so it starts watching that source. */
    fun chipPermissionsChanged(context: Context) {
        get(context).edit().putLong(KEY_CHIP_REFRESH, System.currentTimeMillis()).apply()
    }

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True once a wireless-debugging pairing has completed on this install. */
    fun isPaired(context: Context): Boolean = get(context).getBoolean(KEY_PAIRED, false)

    fun setPaired(context: Context, paired: Boolean) {
        get(context).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    fun activateOnBoot(context: Context): Boolean = get(context).getBoolean(KEY_ACTIVATE_ON_BOOT, true)

    fun setActivateOnBoot(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_ACTIVATE_ON_BOOT, enabled).apply()
    }

    // --- Cards ---

    /** Card types the user switched off. All are on to start with. */
    fun cardEnabled(context: Context, type: CardType): Boolean =
        type.id !in (get(context).getStringSet(KEY_DISABLED_CARDS, emptySet()) ?: emptySet())

    fun setCardEnabled(context: Context, type: CardType, on: Boolean) {
        val now = (get(context).getStringSet(KEY_DISABLED_CARDS, emptySet()) ?: emptySet()).toMutableSet()
        if (on) now.remove(type.id) else now.add(type.id)
        get(context).edit().putStringSet(KEY_DISABLED_CARDS, now).apply()
    }

    /** How long before a calendar event its card appears. */
    fun calendarLeadMinutes(context: Context): Int = get(context).getInt(KEY_CALENDAR_LEAD, 60)

    fun setCalendarLeadMinutes(context: Context, minutes: Int) {
        get(context).edit().putInt(KEY_CALENDAR_LEAD, minutes).apply()
    }

    /** Whether today's all-day events get a card when nothing else is on. */
    fun calendarAllDay(context: Context): Boolean = get(context).getBoolean(KEY_CALENDAR_ALL_DAY, false)

    fun setCalendarAllDay(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_CALENDAR_ALL_DAY, on).apply()
    }

    /**
     * The weather card's arrangement. Before it existed there was a Right / Bottom / None
     * choice; that maps to Columns / Split / No chips.
     */
    fun weatherLayout(context: Context): WeatherLayout {
        get(context).getString(KEY_WEATHER_LAYOUT, null)?.let { raw ->
            return runCatching { WeatherLayout.valueOf(raw) }.getOrDefault(WeatherLayout.COLUMNS)
        }
        return when (get(context).getString(KEY_COMP_LAYOUT, null)) {
            ComplicationLayout.BOTTOM.name -> WeatherLayout.SPLIT
            ComplicationLayout.NONE.name -> WeatherLayout.NONE
            else -> WeatherLayout.COLUMNS
        }
    }

    fun setWeatherLayout(context: Context, layout: WeatherLayout) {
        get(context).edit().putString(KEY_WEATHER_LAYOUT, layout.name).apply()
    }

    /** How chips sit on the other cards, following the weather card's arrangement. */
    /** The weather card's look; a still scene unless chosen otherwise. */
    fun weatherLook(context: Context): com.ataglanceplus.weather.WeatherLook =
        runCatching { com.ataglanceplus.weather.WeatherLook.valueOf(get(context).getString(KEY_WEATHER_LOOK, null)!!) }
            .getOrDefault(com.ataglanceplus.weather.WeatherLook.SCENES)

    fun setWeatherLook(context: Context, look: com.ataglanceplus.weather.WeatherLook) {
        get(context).edit().putString(KEY_WEATHER_LOOK, look.name).apply()
    }

    fun complicationLayout(context: Context): ComplicationLayout = weatherLayout(context).others

    /** The user's chosen weather-card chips in the order picked, or null to use the spec's defaults. */
    fun chips(context: Context): List<ChipSpec>? {
        val raw = get(context).getString(KEY_CHIPS, null) ?: return null
        return raw.split(',').filter { it.isNotEmpty() }.mapNotNull { ChipSpec.parse(it) }
    }

    fun setChips(context: Context, chips: List<ChipSpec>) {
        // Keys may hold an account address or a package name, never a comma.
        get(context).edit().putString(KEY_CHIPS, chips.joinToString(",") { it.key }).apply()
    }

    /** The app a chip opens when tapped, if the user chose one. */
    fun chipApp(context: Context, chipKey: String): String? = get(context).getString(KEY_CHIP_APP + chipKey, null)

    fun setChipApp(context: Context, chipKey: String, pkg: String?) {
        get(context).edit().apply { if (pkg == null) remove(KEY_CHIP_APP + chipKey) else putString(KEY_CHIP_APP + chipKey, pkg) }.apply()
    }

    /** Gmail labels counted for an account, by canonical name; the inbox until the user picks. */
    fun gmailLabels(context: Context, account: String): Set<String> =
        get(context).getStringSet(KEY_GMAIL_LABELS + account, null) ?: setOf(GMAIL_INBOX)

    fun setGmailLabels(context: Context, account: String, labels: Set<String>) {
        get(context).edit().putStringSet(KEY_GMAIL_LABELS + account, labels).apply()
    }

    /** The countdown chip's date as an epoch day, or null when none is set. */
    fun countdownDay(context: Context): Long? =
        get(context).getLong(KEY_COUNTDOWN, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    /** A date picked by hand; the countdown no longer follows a calendar event. */
    fun setCountdownDay(context: Context, epochDay: Long) {
        get(context).edit().putLong(KEY_COUNTDOWN, epochDay).remove(KEY_COUNTDOWN_EVENT).apply()
    }

    /** The calendar event the countdown follows (its date moves with the event), or null. */
    fun countdownEvent(context: Context): Long? =
        get(context).getLong(KEY_COUNTDOWN_EVENT, -1L).takeIf { it >= 0 }

    /** Counts down to a calendar event: its date now, its title as the name, and follows it from then on. */
    fun setCountdownEvent(context: Context, eventId: Long, epochDay: Long, title: String) {
        get(context).edit()
            .putLong(KEY_COUNTDOWN_EVENT, eventId)
            .putLong(KEY_COUNTDOWN, epochDay)
            .putString(KEY_COUNTDOWN_NAME, title.trim().take(30))
            .apply()
    }

    /** Remembers where a followed event has moved to, without letting go of it. */
    fun moveCountdownDay(context: Context, epochDay: Long) {
        get(context).edit().putLong(KEY_COUNTDOWN, epochDay).apply()
    }

    /** What the countdown is to ("Holiday"), shown on a big countdown chip; blank for none. */
    fun countdownName(context: Context): String = get(context).getString(KEY_COUNTDOWN_NAME, "").orEmpty()

    fun setCountdownName(context: Context, name: String) {
        get(context).edit().putString(KEY_COUNTDOWN_NAME, name.trim()).apply()
    }

    /** Daily step goal, shown on a big steps chip. */
    fun stepGoal(context: Context): Int = get(context).getInt(KEY_STEP_GOAL, 8_000)

    fun setStepGoal(context: Context, steps: Int) {
        get(context).edit().putInt(KEY_STEP_GOAL, steps).apply()
    }

    /** A chip's size: standard (1 slot) or extended (2). 0.9.3's wide and tall read as extended. */
    fun chipSize(context: Context, chipKey: String): com.ataglanceplus.cards.ChipSize =
        when (get(context).getString(KEY_CHIP_SIZE + chipKey, null)) {
            "EXTENDED", "WIDE", "TALL" -> com.ataglanceplus.cards.ChipSize.EXTENDED
            else -> com.ataglanceplus.cards.ChipSize.STANDARD
        }

    fun setChipSize(context: Context, chipKey: String, size: com.ataglanceplus.cards.ChipSize) {
        get(context).edit().putString(KEY_CHIP_SIZE + chipKey, size.name).apply()
    }

    const val GMAIL_INBOX = "^i"

    /**
     * Cards the user dismissed, by instance ("media:com.spotify.music", "calendar:12:1730131200000").
     * A dismissed instance stays hidden until it ends; a new one (next event, next delivery) shows.
     */
    fun dismissed(context: Context): Set<String> = get(context).getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    fun dismiss(context: Context, instanceKey: String) {
        get(context).edit().putStringSet(KEY_DISMISSED, dismissed(context) + instanceKey).apply()
    }

    /** Forgets dismissals of instances that have ended. */
    fun pruneDismissed(context: Context, active: Set<String>) {
        val now = dismissed(context)
        val kept = now.filter { it in active }.toSet()
        if (kept != now) get(context).edit().putStringSet(KEY_DISMISSED, kept).apply()
    }

    fun restoreDismissed(context: Context) {
        get(context).edit().remove(KEY_DISMISSED).apply()
    }

    /** Draw the lock screen in the always-on look even while the screen is on. */
    fun aodPreview(context: Context): Boolean = get(context).getBoolean(KEY_AOD_PREVIEW, false)

    fun setAodPreview(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_AOD_PREVIEW, on).apply()
    }

    /** True for the 14-bar wave on the media card, false for the 3dp track bar. */
    fun mediaWave(context: Context): Boolean = get(context).getBoolean(KEY_MEDIA_WAVE, true)

    fun setMediaWave(context: Context, wave: Boolean) {
        get(context).edit().putBoolean(KEY_MEDIA_WAVE, wave).apply()
    }

    // --- Finance card ---

    /** Tickers in the user's order (Yahoo Finance symbols, e.g. AAPL, VOD.L). */
    fun financeTickers(context: Context): List<String> =
        get(context).getString(KEY_FIN_TICKERS, "").orEmpty().split(',').filter { it.isNotBlank() }

    fun setFinanceTickers(context: Context, tickers: List<String>) {
        get(context).edit().putString(KEY_FIN_TICKERS, tickers.joinToString(",")).apply()
    }

    /** How often prices are fetched. */
    fun financeRefreshMinutes(context: Context): Int = get(context).getInt(KEY_FIN_REFRESH, 15)

    fun setFinanceRefreshMinutes(context: Context, minutes: Int) {
        get(context).edit().putInt(KEY_FIN_REFRESH, minutes).apply()
    }

    /** Show the card when a stock has moved at least [financeThreshold] percent today. On to start with. */
    fun financeOnMoves(context: Context): Boolean = get(context).getBoolean(KEY_FIN_MOVES, true)

    fun setFinanceOnMoves(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_FIN_MOVES, on).apply()
    }

    fun financeThreshold(context: Context): Int = get(context).getInt(KEY_FIN_THRESHOLD, 3)

    fun setFinanceThreshold(context: Context, percent: Int) {
        get(context).edit().putInt(KEY_FIN_THRESHOLD, percent).apply()
    }

    /** Show the card for 10 minutes every [financePeriodHours] hours. */
    fun financePeriodic(context: Context): Boolean = get(context).getBoolean(KEY_FIN_PERIODIC, false)

    fun setFinancePeriodic(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_FIN_PERIODIC, on).apply()
    }

    fun financePeriodHours(context: Context): Int = get(context).getInt(KEY_FIN_PERIOD_HOURS, 2)

    fun setFinancePeriodHours(context: Context, hours: Int) {
        get(context).edit().putInt(KEY_FIN_PERIOD_HOURS, hours).apply()
    }

    /** Show the card during the user's time windows. */
    fun financeInWindows(context: Context): Boolean = get(context).getBoolean(KEY_FIN_WINDOWS_ON, false)

    fun setFinanceInWindows(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_FIN_WINDOWS_ON, on).apply()
    }

    /** Time windows as minutes of the day, start to end (an end before the start runs past midnight). */
    fun financeWindows(context: Context): List<Pair<Int, Int>> =
        get(context).getString(KEY_FIN_WINDOWS, "").orEmpty().split(',').mapNotNull { part ->
            val (a, b) = part.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
            (a.toIntOrNull() ?: return@mapNotNull null) to (b.toIntOrNull() ?: return@mapNotNull null)
        }

    fun setFinanceWindows(context: Context, windows: List<Pair<Int, Int>>) {
        get(context).edit().putString(KEY_FIN_WINDOWS, windows.joinToString(",") { "${it.first}-${it.second}" }).apply()
    }

    /** Settings that change which prices are fetched. */
    fun isFinanceFetchKey(key: String?): Boolean = key == KEY_FIN_TICKERS || key == KEY_FIN_REFRESH

    // --- Sports cards ---

    /** Teams and series followed, in the user's order. */
    fun sportsFollowed(context: Context): List<com.ataglanceplus.live.Followed> = runCatching {
        val array = org.json.JSONArray(get(context).getString(KEY_SPORTS_FOLLOWED, "[]"))
        (0 until array.length()).map { com.ataglanceplus.live.Followed.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setSportsFollowed(context: Context, followed: List<com.ataglanceplus.live.Followed>) {
        val array = org.json.JSONArray().apply { followed.forEach { put(it.toJson()) } }
        get(context).edit().putString(KEY_SPORTS_FOLLOWED, array.toString()).apply()
    }

    /** Hours before a game its card appears. */
    fun sportsBeforeHours(context: Context): Int = get(context).getInt(KEY_SPORTS_BEFORE, 2)

    fun setSportsBeforeHours(context: Context, hours: Int) {
        get(context).edit().putInt(KEY_SPORTS_BEFORE, hours).apply()
    }

    /** Hours after a game its result stays. */
    fun sportsAfterHours(context: Context): Int = get(context).getInt(KEY_SPORTS_AFTER, 2)

    fun setSportsAfterHours(context: Context, hours: Int) {
        get(context).edit().putInt(KEY_SPORTS_AFTER, hours).apply()
    }

    /** Show each team's next fixture when nothing else is on. On to start with. */
    fun sportsNextAlways(context: Context): Boolean = get(context).getBoolean(KEY_SPORTS_NEXT, true)

    fun setSportsNextAlways(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_SPORTS_NEXT, on).apply()
    }

    /** Which race weekend the F1 card's Next was tapped on, how many times, and when last. */
    fun sportsRaceStep(context: Context): Triple<String, Int, Long> {
        val parts = get(context).getString(KEY_SPORTS_STEP, "").orEmpty().split('|')
        return Triple(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /** One more Next on [eventId]'s card; a new weekend, or a step that has run out, starts again. */
    fun stepSportsRace(context: Context, eventId: String, lasts: Long) {
        val now = System.currentTimeMillis()
        val (id, steps, at) = sportsRaceStep(context)
        val next = if (id == eventId && now - at < lasts) steps + 1 else 1
        get(context).edit().putString(KEY_SPORTS_STEP, "$eventId|$next|$now").apply()
    }

    /** Tapping a sports card opens Google's page for the game (else ESPN's). */
    fun sportsOpenGoogle(context: Context): Boolean = get(context).getBoolean(KEY_SPORTS_OPEN_GOOGLE, true)

    fun setSportsOpenGoogle(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_SPORTS_OPEN_GOOGLE, on).apply()
    }

    fun isSportsFollowKey(key: String?): Boolean = key == KEY_SPORTS_FOLLOWED
}
