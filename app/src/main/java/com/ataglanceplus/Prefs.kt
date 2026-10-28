package com.ataglanceplus

import android.content.Context
import android.content.SharedPreferences
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.ComplicationLayout
import com.ataglanceplus.cards.PreviewCard

/** Small settings store. The service and the UI live in one process and both read it. */
object Prefs {

    private const val FILE = "settings"
    const val KEY_PAIRED = "wadb_paired"
    const val KEY_ACTIVATE_ON_BOOT = "activate_on_boot"

    // Card preview: sample content from the design spec until each data source is built.
    private const val KEY_PREVIEW_CARDS = "preview_cards"
    private const val KEY_PREVIEW_STATE = "preview_state_"
    private const val KEY_WEATHER_SAMPLE = "weather_sample"
    private const val KEY_COMP_LAYOUT = "comp_layout"
    private const val KEY_CHIPS = "chips"
    private const val KEY_DISMISSED = "dismissed"
    private const val KEY_CHIP_APP = "chip_app_"
    private const val KEY_GMAIL_LABELS = "gmail_labels_"
    private const val KEY_COUNTDOWN = "countdown_day"
    private const val KEY_CHIP_REFRESH = "chip_refresh"
    private const val KEY_LONG_TEXT = "long_text"
    private const val KEY_AOD_PREVIEW = "aod_preview"
    const val KEY_ISLAND_UNTIL = "island_until"
    private const val KEY_ISLAND_INDEX = "island_index"
    private const val KEY_MEDIA_PLAYING = "media_playing"
    private const val KEY_MEDIA_WAVE = "media_wave"
    private const val KEY_MEDIA_RESTARTED = "media_restarted"
    const val KEY_TIMER_END = "timer_end"
    private const val KEY_TIMER_LEFT = "timer_left"

    /** Keys that only matter to the wireless-debugging screens; changing them needs no redraw. */
    val NON_CARD_KEYS = setOf(KEY_PAIRED, KEY_ACTIVATE_ON_BOOT)

    /** Settings that change what the complication engine has to watch. */
    fun isChipSettingKey(key: String?): Boolean =
        key != null && (key == KEY_CHIPS || key.startsWith(KEY_GMAIL_LABELS) || key == KEY_COUNTDOWN || key == KEY_CHIP_REFRESH)

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

    // --- Card preview ---

    /** Preview cards switched on, in deck order. */
    fun previewCards(context: Context): Set<PreviewCard> {
        val raw = get(context).getStringSet(KEY_PREVIEW_CARDS, null) ?: return PreviewCard.entries.toSet()
        return raw.mapNotNull { id -> PreviewCard.entries.firstOrNull { it.id == id } }.toSet()
    }

    fun setPreviewCard(context: Context, card: PreviewCard, on: Boolean) {
        val now = previewCards(context).toMutableSet().apply { if (on) add(card) else remove(card) }
        get(context).edit().putStringSet(KEY_PREVIEW_CARDS, now.map { it.id }.toSet()).apply()
    }

    /** The chosen sample state for a preview card, e.g. "arrived" for the live update. */
    fun previewState(context: Context, card: PreviewCard): String =
        get(context).getString(KEY_PREVIEW_STATE + card.id, null)?.takeIf { it in card.states.map { s -> s.id } }
            ?: card.states.first().id

    fun setPreviewState(context: Context, card: PreviewCard, state: String) {
        get(context).edit().putString(KEY_PREVIEW_STATE + card.id, state).apply()
    }

    /** "live" for real weather, otherwise a sample condition from the spec. */
    fun weatherSample(context: Context): String = get(context).getString(KEY_WEATHER_SAMPLE, "live") ?: "live"

    fun setWeatherSample(context: Context, sample: String) {
        get(context).edit().putString(KEY_WEATHER_SAMPLE, sample).apply()
    }

    fun complicationLayout(context: Context): ComplicationLayout {
        val raw = get(context).getString(KEY_COMP_LAYOUT, null) ?: return ComplicationLayout.RIGHT
        return runCatching { ComplicationLayout.valueOf(raw) }.getOrDefault(ComplicationLayout.RIGHT)
    }

    fun setComplicationLayout(context: Context, layout: ComplicationLayout) {
        get(context).edit().putString(KEY_COMP_LAYOUT, layout.name).apply()
    }

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

    fun setCountdownDay(context: Context, epochDay: Long) {
        get(context).edit().putLong(KEY_COUNTDOWN, epochDay).apply()
    }

    const val GMAIL_INBOX = "^i"

    fun dismissed(context: Context): Set<String> = get(context).getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    fun dismiss(context: Context, cardId: String) {
        get(context).edit().putStringSet(KEY_DISMISSED, dismissed(context) + cardId).apply()
    }

    fun restoreDismissed(context: Context) {
        get(context).edit().remove(KEY_DISMISSED).apply()
    }

    /** Longest realistic strings (spec section 5, variant 5). */
    fun longText(context: Context): Boolean = get(context).getBoolean(KEY_LONG_TEXT, false)

    fun setLongText(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_LONG_TEXT, on).apply()
    }

    /** Draw the lock screen in the always-on look even while the screen is on. */
    fun aodPreview(context: Context): Boolean = get(context).getBoolean(KEY_AOD_PREVIEW, false)

    fun setAodPreview(context: Context, on: Boolean) {
        get(context).edit().putBoolean(KEY_AOD_PREVIEW, on).apply()
    }

    /** Wall-clock time the incoming-message toast ends, 0 when none is showing. */
    fun islandUntil(context: Context): Long = get(context).getLong(KEY_ISLAND_UNTIL, 0L)

    /** Which of the spec's three sample messages is showing (they take turns). */
    fun islandIndex(context: Context): Int = get(context).getInt(KEY_ISLAND_INDEX, -1).coerceAtLeast(0)

    fun startIsland(context: Context, durationMs: Long) {
        val next = (get(context).getInt(KEY_ISLAND_INDEX, -1) + 1) % 3
        get(context).edit()
            .putInt(KEY_ISLAND_INDEX, next)
            .putLong(KEY_ISLAND_UNTIL, System.currentTimeMillis() + durationMs)
            .apply()
    }

    fun endIsland(context: Context) {
        get(context).edit().putLong(KEY_ISLAND_UNTIL, 0L).apply()
    }

    fun mediaPlaying(context: Context): Boolean = get(context).getBoolean(KEY_MEDIA_PLAYING, true)

    fun setMediaPlaying(context: Context, playing: Boolean) {
        get(context).edit().putBoolean(KEY_MEDIA_PLAYING, playing).apply()
    }

    /** True for the 14-bar wave, false for the compact 3dp track bar. */
    fun mediaWave(context: Context): Boolean = get(context).getBoolean(KEY_MEDIA_WAVE, true)

    fun setMediaWave(context: Context, wave: Boolean) {
        get(context).edit().putBoolean(KEY_MEDIA_WAVE, wave).apply()
    }

    /** Set by the previous/next buttons so a tap visibly does something in preview. */
    fun mediaRestarted(context: Context): Boolean = get(context).getBoolean(KEY_MEDIA_RESTARTED, false)

    fun setMediaRestarted(context: Context, restarted: Boolean) {
        get(context).edit().putBoolean(KEY_MEDIA_RESTARTED, restarted).apply()
    }

    /** Preview timer: wall-clock end while running (0 when not), or time left while paused. */
    fun timerEnd(context: Context): Long = get(context).getLong(KEY_TIMER_END, 0L)

    fun timerLeft(context: Context): Long = get(context).getLong(KEY_TIMER_LEFT, TIMER_SAMPLE_MS)

    fun setTimer(context: Context, endAt: Long, leftMs: Long) {
        get(context).edit().putLong(KEY_TIMER_END, endAt).putLong(KEY_TIMER_LEFT, leftMs).apply()
    }

    /** The spec's sample timer, 6:42. */
    const val TIMER_SAMPLE_MS = 402_000L
}
