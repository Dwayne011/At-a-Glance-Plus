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
        val icon = Icon.createWithResource(context, iconRes)
        val header = SmartspaceAction.Builder("${id}_header", title)
            .setSubtitle(subtitle)
            .setIcon(icon)
            .setPendingIntent(tap)
            .build()
        val base = SmartspaceAction.Builder("${id}_base", "").build()
        val template = BaseTemplateData.Builder(SmartspaceTarget.UI_TEMPLATE_DEFAULT)
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
            .setSubtitleItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(subtitle).build())
                    .build()
            )
            .build()
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
