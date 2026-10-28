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
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity
import com.ataglanceplus.weather.Weather
import android.app.smartspace.uitemplatedata.Icon as TemplateIcon

/**
 * Builds the milestone-1 test card for a surface.
 *
 * Every target carries the legacy header/base actions and template data as well, so the lock
 * screen, the always-on display and any client that ignores RemoteViews still has something
 * sensible to draw.
 */
object TestCards {

    /** Everything shown on a surface, in order: the date and weather line first, then cards. */
    fun build(context: Context, surface: String, style: Prefs.CardStyle, weather: Weather?): List<SmartspaceTarget> {
        return when (surface) {
            AtAGlanceService.SURFACE_HOME,
            AtAGlanceService.SURFACE_LOCKSCREEN -> buildList {
                add(DateWeatherCard.build(context, weather))
                if (Prefs.showTestCard(context)) add(testCard(context, surface, style))
            }
            // Media, glanceable hub, screensaver and ambient surfaces: nothing yet.
            else -> emptyList()
        }
    }

    private fun testCard(context: Context, surface: String, style: Prefs.CardStyle): SmartspaceTarget {
        val title = context.getString(R.string.card_test_title)
        val subtitle = context.getString(
            if (surface == AtAGlanceService.SURFACE_LOCKSCREEN) R.string.card_test_subtitle_lock
            else R.string.card_test_subtitle_home
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_card)
        val tap = openAppIntent(context)

        val header = SmartspaceAction.Builder("aagp_test_header_$surface", title)
            .setSubtitle(subtitle)
            .setIcon(icon)
            .setPendingIntent(tap)
            .build()
        // A blank base action keeps clients that expect one happy without adding a complication.
        val base = SmartspaceAction.Builder("aagp_test_base_$surface", "").build()

        val template = BaseTemplateData.Builder(SmartspaceTarget.UI_TEMPLATE_DEFAULT)
            .setPrimaryItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(title).build())
                    .setIcon(TemplateIcon.Builder(icon).setShouldTint(true).build())
                    .setTapAction(
                        TapAction.Builder("aagp_test_tap_$surface")
                            .setPendingIntent(tap)
                            // Never act straight from the lock screen; the gate asks for an unlock.
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

        val builder = SmartspaceTarget.Builder(
            "aagp_test_$surface",
            ComponentName(context, AtAGlanceService::class.java),
            Process.myUserHandle()
        )
            .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
            .setHeaderAction(header)
            .setBaseAction(base)
            .setTemplateData(template)
            .setCreationTimeMillis(System.currentTimeMillis())
            .setScore(1f)

        if (style == Prefs.CardStyle.LAYOUT) {
            builder.setRemoteViews(layoutCard(context, tap))
        }
        return builder.build()
    }

    /** Our own layout: this is the tier where fonts, colours, progress bars and buttons are ours. */
    private fun layoutCard(context: Context, tap: PendingIntent): RemoteViews {
        return RemoteViews(context.packageName, R.layout.card_test).apply {
            setTextViewText(R.id.card_title, context.getString(R.string.card_test_title))
            setTextViewText(R.id.card_eta, context.getString(R.string.card_test_eta))
            setTextViewText(R.id.card_subtitle, context.getString(R.string.card_test_layout_subtitle))
            setProgressBar(R.id.card_progress, 100, 55, false)
            setOnClickPendingIntent(R.id.card_root, tap)
        }
    }

    /** Opens the app, but only after the phone is unlocked. */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val target = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, REQUEST_OPEN_APP)
    }

    private const val REQUEST_OPEN_APP = 100
}
