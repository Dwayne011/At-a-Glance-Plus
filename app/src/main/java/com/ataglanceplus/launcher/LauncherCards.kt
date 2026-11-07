package com.ataglanceplus.launcher

import android.app.PendingIntent
import android.app.smartspace.SmartspaceTarget
import android.content.Context
import android.net.Uri

/**
 * The home-screen cards as last built for third-party launchers.
 *
 * Only the system launcher can bind to the Smartspace service, so Pixel Launcher Plus (and any
 * other launcher that asks) reads the same cards through [GlanceProvider]. The service publishes
 * here whenever it would redraw the home screen; the cards' pending intents stay in this process
 * and are fired by [TapReceiver].
 */
object LauncherCards {

    const val AUTHORITY = "com.ataglanceplus.glance"
    val CARDS_URI: Uri = Uri.parse("content://$AUTHORITY/cards")

    /** How many cards to build for launchers; Pixel Launcher Plus shows the top few by score. */
    const val CARD_COUNT = 5

    @Volatile
    var targets: List<SmartspaceTarget> = emptyList()
        private set

    @Volatile
    private var taps: Map<String, PendingIntent> = emptyMap()

    @Volatile
    var publishedAt = 0L
        private set

    fun publish(context: Context, cards: List<SmartspaceTarget>) {
        targets = cards
        taps = cards.mapNotNull { target ->
            val tap = target.headerAction?.pendingIntent ?: target.baseAction?.pendingIntent ?: return@mapNotNull null
            target.smartspaceTargetId to tap
        }.toMap()
        publishedAt = System.currentTimeMillis()
        context.contentResolver.notifyChange(CARDS_URI, null)
    }

    fun tap(cardId: String): PendingIntent? = taps[cardId]
}
