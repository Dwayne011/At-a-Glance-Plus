package com.ataglanceplus.cards

import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.uitemplatedata.BaseTemplateData
import android.app.smartspace.uitemplatedata.TapAction
import android.app.smartspace.uitemplatedata.Text
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Process
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.R
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity
import android.app.smartspace.uitemplatedata.Icon as TemplateIcon

/** Shared colours and helpers for every card. */
object Style {

    /** Always-on display text and icon colour (spec section 5). */
    const val AOD_TEXT = 0xFF9AA0A6.toInt()

    /** Switches a card body to the always-on look: black, thin outline, grey text. */
    fun aodBody(views: RemoteViews, vararg textIds: Int) {
        views.setInt(R.id.card_body, "setBackgroundResource", R.drawable.card_bg_aod)
        textIds.forEach { views.setTextColor(it, AOD_TEXT) }
    }

    /**
     * Picks the hold ring for the user's touch-and-hold delay (Accessibility settings), so it
     * closes round the card as the launcher's menu opens: amber on the pinned weather card, red
     * elsewhere.
     * Hidden on the always-on display, where nothing can be pressed.
     */
    fun holdBar(views: RemoteViews, pinned: Boolean, aod: Boolean) {
        if (aod) {
            views.setViewVisibility(R.id.hold_bar, android.view.View.GONE)
            return
        }
        val timeout = android.view.ViewConfiguration.getLongPressTimeout()
        val res = when {
            timeout < 700 -> if (pinned) R.drawable.hold_bar_amber_400 else R.drawable.hold_bar_red_400
            timeout < 1250 -> if (pinned) R.drawable.hold_bar_amber_1000 else R.drawable.hold_bar_red_1000
            else -> if (pinned) R.drawable.hold_bar_amber_1500 else R.drawable.hold_bar_red_1500
        }
        views.setImageViewResource(R.id.hold_bar, res)
    }

    /** An activity, opened only after the phone is unlocked. */
    fun gated(context: Context, intent: Intent, requestCode: Int): PendingIntent {
        val target = PendingIntent.getActivity(
            context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, requestCode)
    }

    /** An installed app by package, or this app when it is missing. Behind the unlock gate. */
    fun gatedApp(context: Context, pkg: String, requestCode: Int): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return gated(context, intent, requestCode)
    }

    /** The card height the layouts are drawn at (@dimen/card_body_height). */
    const val CARD_HEIGHT_DEFAULT_DP = 88

    /**
     * Sets the card to the user's chosen height: the slot, the body, and the parts drawn to fill
     * it. Only the views each layout has are touched.
     */
    private fun sizeCard(context: Context, views: RemoteViews) {
        val height = com.ataglanceplus.Prefs.cardHeightDp(context)
        if (height == CARD_HEIGHT_DEFAULT_DP) return
        val layout = views.layoutId
        val ids = mutableListOf(R.id.card_root, R.id.card_body)
        if (layout != R.layout.card_chips && layout != R.layout.include_island_message_toast) ids += R.id.hold_bar
        if (layout in WEATHER_SCENE_LAYOUTS) ids += R.id.weather_scene
        if (layout == R.layout.card_sports) ids += R.id.sports_surface
        ids.forEach { views.setViewLayoutHeight(it, height.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP) }
    }

    private val WEATHER_SCENE_LAYOUTS = setOf(
        R.layout.card_weather_columns, R.layout.card_weather_grid, R.layout.card_weather_material, R.layout.card_weather_split,
    )

    /**
     * Wraps a card's layout in a target. The header, base and template fields carry a plain-text
     * version for any surface that ignores custom layouts.
     */
    fun target(
        context: Context,
        id: String,
        views: RemoteViews,
        title: String,
        subtitle: String,
        @DrawableRes iconRes: Int,
        tap: PendingIntent,
        score: Float,
    ): SmartspaceTarget {
        sizeCard(context, views)
        val icon = Icon.createWithResource(context, iconRes)
        val header = SmartspaceAction.Builder("${id}_header", title)
            .setSubtitle(subtitle)
            .setIcon(icon)
            .setPendingIntent(tap)
            .build()
        val base = SmartspaceAction.Builder("${id}_base", "").build()
        val builder = BaseTemplateData.Builder(SmartspaceTarget.UI_TEMPLATE_DEFAULT)
            .setPrimaryItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(title).build())
                    .setIcon(TemplateIcon.Builder(icon).setShouldTint(false).build())
                    .setTapAction(
                        TapAction.Builder("${id}_tap")
                            .setPendingIntent(tap)
                            .setShouldShowOnLockscreen(false)
                            .build()
                    )
                    .build()
            )
        // Android refuses an item with nothing in it, so a card with no second line (a live update
        // with only a title) leaves the subtitle out rather than failing.
        if (subtitle.isNotBlank()) {
            builder.setSubtitleItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(subtitle).build())
                    .build()
            )
        }
        val template = builder.build()
        return SmartspaceTarget.Builder(
            id,
            ComponentName(context, AtAGlanceService::class.java),
            Process.myUserHandle()
        )
            .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
            .setHeaderAction(header)
            .setBaseAction(base)
            .setTemplateData(template)
            .setRemoteViews(views)
            .setCreationTimeMillis(System.currentTimeMillis())
            .setScore(score)
            .build()
    }
}
