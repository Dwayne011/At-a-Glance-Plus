package com.ataglanceplus.cards

import android.app.smartspace.SmartspaceTarget
import android.content.Context
import com.ataglanceplus.Prefs
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.weather.Weather

/** Everything shown on a surface, in order. */
object CardDeck {

    /**
     * The weather card first (it is the base card and cannot be dismissed), then the preview cards
     * that are switched on and not dismissed. While a message toast is showing it takes the first
     * place. The list is cut to the number of cards the surface asked for.
     */
    fun build(
        context: Context,
        surface: String,
        maxCards: Int,
        aod: Boolean,
        weather: Weather?,
        weatherStatus: String,
        islandRemainingMs: Long,
    ): List<SmartspaceTarget> {
        if (surface != AtAGlanceService.SURFACE_HOME && surface != AtAGlanceService.SURFACE_LOCKSCREEN) {
            // Media, glanceable hub, screensaver and ambient surfaces: nothing yet.
            return emptyList()
        }
        val enabled = Prefs.previewCards(context)
        val dismissed = Prefs.dismissed(context)
        val cards = buildList {
            if (islandRemainingMs > 0) add(PreviewCards.island(context, aod, islandRemainingMs))
            add(WeatherCard.build(context, surface, aod, weather, weatherStatus))
            PreviewCard.entries
                .filter { it in enabled && it.id !in dismissed }
                .forEach { add(PreviewCards.build(context, it, aod)) }
        }
        return if (maxCards > 0) cards.take(maxCards) else cards
    }

    /** Whether a target id may be dismissed: everything but the weather card. */
    fun dismissibleId(targetId: String): String? =
        PreviewCard.entries.firstOrNull { it.targetId == targetId }?.id
}
