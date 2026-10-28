package com.ataglanceplus

import android.content.Context
import android.content.SharedPreferences

/** Small settings store. The service and the UI live in one process and both read it. */
object Prefs {

    private const val FILE = "settings"
    const val KEY_CARD_STYLE = "card_style"

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
}
