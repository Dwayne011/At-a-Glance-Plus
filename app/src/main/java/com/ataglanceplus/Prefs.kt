package com.ataglanceplus

import android.content.Context
import android.content.SharedPreferences
import com.ataglanceplus.cards.ChipSpec
import com.ataglanceplus.cards.ComplicationLayout
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
    private const val KEY_CHIPS = "chips"
    private const val KEY_DISMISSED = "dismissed_cards"
    private const val KEY_CHIP_APP = "chip_app_"
    private const val KEY_GMAIL_LABELS = "gmail_labels_"
    private const val KEY_COUNTDOWN = "countdown_day"
    private const val KEY_CHIP_REFRESH = "chip_refresh"
    private const val KEY_AOD_PREVIEW = "aod_preview"
    private const val KEY_MEDIA_WAVE = "media_wave"

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
}
