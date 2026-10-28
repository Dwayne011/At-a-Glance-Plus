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
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.ataglanceplus.R
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity
import com.ataglanceplus.weather.Weather
import com.ataglanceplus.weather.WeatherCondition
import java.util.Date
import java.util.Locale
import android.app.smartspace.uitemplatedata.Icon as TemplateIcon

/**
 * The weather card, in our own layout rather than Google's date-and-weather line.
 *
 * Drawn with RemoteViews, which Pixel Launcher and SystemUI honour on both the home screen and
 * the lock screen. The date sits in the card on the home screen only; the lock screen already
 * shows it beside the clock. With no weather yet, the card says why instead of staying blank.
 *
 * Header, base and template fields carry a plain-text version for any surface that ignores
 * custom layouts.
 */
object WeatherCard {

    const val TARGET_ID = "aagp_weather"
    private const val REQUEST_WEATHER = 201
    private const val REQUEST_SETUP = 202
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    /** The date in the phone's locale, e.g. "Wed 28 Oct" in the UK, "Wed, Oct 28" in the US. */
    fun dateText(locale: Locale = Locale.getDefault()): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), Date()).toString()

    fun build(context: Context, surface: String, weather: Weather?, status: String): SmartspaceTarget {
        val showDate = surface == AtAGlanceService.SURFACE_HOME
        val tap = if (weather != null) weatherIntent(context) else setupIntent(context)

        val title: String
        val subtitle: String
        val iconRes: Int
        val views = RemoteViews(context.packageName, R.layout.card_weather)
        if (weather != null) {
            val condition = weather.condition
            title = "${weather.temperatureText} ${condition.label}"
            subtitle = weather.detailText
            iconRes = condition.icon(weather.isDay)
            views.setTextViewText(R.id.weather_temp, weather.temperatureText)
            views.setTextViewText(R.id.weather_condition, condition.label)
            views.setTextViewText(R.id.weather_detail, weather.detailText)
            views.setViewVisibility(R.id.weather_detail, if (weather.detailText.isEmpty()) View.GONE else View.VISIBLE)
            views.setInt(R.id.weather_container, "setBackgroundResource", condition.background(weather.isDay))
        } else {
            title = "Weather"
            subtitle = status.ifEmpty { "Open At a Glance Plus to set up weather" }
            iconRes = WeatherCondition.CLOUDY.icon(true)
            views.setViewVisibility(R.id.weather_temp, View.GONE)
            views.setTextViewText(R.id.weather_condition, title)
            views.setTextViewText(R.id.weather_detail, subtitle)
            views.setInt(R.id.weather_container, "setBackgroundResource", R.drawable.card_weather_bg_unavailable)
        }
        views.setImageViewResource(R.id.weather_icon, iconRes)
        if (showDate) {
            views.setTextViewText(R.id.weather_date, dateText())
            views.setViewVisibility(R.id.weather_date, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.weather_date, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.weather_root, tap)

        // Plain-text fallback for surfaces that do not draw custom layouts (for example the
        // always-on display): the system draws these fields in its own style.
        val icon = Icon.createWithResource(context, iconRes)
        val header = SmartspaceAction.Builder("aagp_weather_header", title)
            .setSubtitle(subtitle)
            .setIcon(icon)
            .setPendingIntent(tap)
            .build()
        val base = SmartspaceAction.Builder("aagp_weather_base", "").build()
        val template = BaseTemplateData.Builder(SmartspaceTarget.UI_TEMPLATE_DEFAULT)
            .setPrimaryItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(title).build())
                    .setIcon(TemplateIcon.Builder(icon).setShouldTint(false).build())
                    .setTapAction(
                        TapAction.Builder("aagp_weather_tap")
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
            TARGET_ID,
            ComponentName(context, AtAGlanceService::class.java),
            Process.myUserHandle()
        )
            .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
            .setHeaderAction(header)
            .setBaseAction(base)
            .setTemplateData(template)
            .setRemoteViews(views)
            .setCreationTimeMillis(System.currentTimeMillis())
            .setScore(2f)
            .build()
    }

    /** Pixel Weather if installed, otherwise this app; always behind the unlock gate. */
    private fun weatherIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(PIXEL_WEATHER)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return setupIntent(context)
        val target = PendingIntent.getActivity(
            context, REQUEST_WEATHER, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, REQUEST_WEATHER)
    }

    /** Opens this app (where weather is set up), behind the unlock gate. */
    private fun setupIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val target = PendingIntent.getActivity(
            context, REQUEST_SETUP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, REQUEST_SETUP)
    }
}
