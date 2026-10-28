package com.ataglanceplus

import android.content.Context
import android.content.SharedPreferences

/** Small settings store. The service and the UI live in one process and both read it. */
object Prefs {

    private const val FILE = "settings"
    const val KEY_CARD_STYLE = "card_style"
    const val KEY_PAIRED = "wadb_paired"
    const val KEY_ACTIVATE_ON_BOOT = "activate_on_boot"
    const val KEY_SHOW_TEST_CARD = "show_test_card"

    /** How the test card is drawn in the At a Glance slot. */
    enum class CardStyle {
        /** One of Google's fixed layouts: the launcher draws it, we supply text and icon. */
        TEMPLATE,

        /** Our own RemoteViews layout: full control of the card's contents (Android 16+). */
        LAYOUT
    }

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cardStyle(context: Context): CardStyle {
        val raw = get(context).getString(KEY_CARD_STYLE, null) ?: return CardStyle.LAYOUT
        return runCatching { CardStyle.valueOf(raw) }.getOrDefault(CardStyle.LAYOUT)
    }

    fun setCardStyle(context: Context, style: CardStyle) {
        get(context).edit().putString(KEY_CARD_STYLE, style.name).apply()
    }

    /** True once a wireless-debugging pairing has completed on this install. */
    fun isPaired(context: Context): Boolean = get(context).getBoolean(KEY_PAIRED, false)

    fun setPaired(context: Context, paired: Boolean) {
        get(context).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    fun showTestCard(context: Context): Boolean = get(context).getBoolean(KEY_SHOW_TEST_CARD, true)

    fun setShowTestCard(context: Context, show: Boolean) {
        get(context).edit().putBoolean(KEY_SHOW_TEST_CARD, show).apply()
    }

    fun activateOnBoot(context: Context): Boolean = get(context).getBoolean(KEY_ACTIVATE_ON_BOOT, true)

    fun setActivateOnBoot(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_ACTIVATE_ON_BOOT, enabled).apply()
    }
}
