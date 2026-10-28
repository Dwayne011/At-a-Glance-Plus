package com.ataglanceplus.cards

import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.uitemplatedata.BaseTemplateData
import android.app.smartspace.uitemplatedata.TapAction
import android.app.smartspace.uitemplatedata.Text
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.text.format.DateFormat
import com.ataglanceplus.service.AtAGlanceService
import com.ataglanceplus.ui.UnlockGateActivity
import com.ataglanceplus.weather.Weather
import java.util.Date
import java.util.Locale
import android.app.smartspace.uitemplatedata.Icon as TemplateIcon

/**
 * The line Google used to provide: today's date, then the current weather.
 *
 * Shaped the way Pixel Launcher and SystemUI expect: the header action carries the date, the
 * base action carries the weather (untinted icon, marked as a weather subcard), and the template
 * data mirrors both for clients that read templates.
 */
object DateWeatherCard {

    const val TARGET_ID = "aagp_date"
    private const val EXTRA_SUBCARD_TYPE = "subcardType"
    private const val EXTRA_DISMISS_INTENT = "dismiss_intent"
    private const val REQUEST_CALENDAR = 200
    private const val REQUEST_WEATHER = 201
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    /** The date as the launcher shows it, e.g. "Wed 28 Oct" in the UK, "Wed, Oct 28" in the US. */
    fun dateText(locale: Locale = Locale.getDefault()): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), Date()).toString()

    fun build(context: Context, weather: Weather?): SmartspaceTarget {
        val date = dateText()
        val calendarTap = calendarIntent(context)

        val header = SmartspaceAction.Builder("aagp_date_header", date)
            .setPendingIntent(calendarTap)
            .build()

        val templateBuilder = BaseTemplateData.Builder(SmartspaceTarget.UI_TEMPLATE_DEFAULT)
            .setPrimaryItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(date).build())
                    .setTapAction(tapAction("aagp_date_tap", calendarTap))
                    .setLoggingInfo(
                        BaseTemplateData.SubItemLoggingInfo.Builder(0, SmartspaceTarget.FEATURE_UNDEFINED).build()
                    )
                    .build()
            )

        val base: SmartspaceAction
        if (weather != null) {
            val icon = Icon.createWithResource(context, weather.condition.icon(weather.isDay))
            val weatherTap = weatherIntent(context) ?: calendarTap
            val extras = Bundle().apply {
                // Keeps the coloured icon (no tint) and stops media surfaces treating it as dismissable.
                putInt(EXTRA_SUBCARD_TYPE, SmartspaceTarget.FEATURE_WEATHER)
                putParcelable(EXTRA_DISMISS_INTENT, Intent())
            }
            base = SmartspaceAction.Builder("aagp_weather", "")
                .setSubtitle(weather.temperatureText)
                .setIcon(icon)
                .setContentDescription("${weather.condition.label}, ${weather.temperatureText}")
                .setPendingIntent(weatherTap)
                .setExtras(extras)
                .build()
            templateBuilder.setSubtitleSupplementalItem(
                BaseTemplateData.SubItemInfo.Builder()
                    .setText(Text.Builder(weather.temperatureText).build())
                    .setIcon(
                        TemplateIcon.Builder(icon)
                            .setShouldTint(false)
                            .setContentDescription(weather.condition.label)
                            .build()
                    )
                    .setTapAction(tapAction("aagp_weather_tap", weatherTap))
                    .build()
            )
        } else {
            base = SmartspaceAction.Builder("aagp_weather", "").build()
        }

        return SmartspaceTarget.Builder(
            TARGET_ID,
            ComponentName(context, AtAGlanceService::class.java),
            Process.myUserHandle()
        )
            .setFeatureType(SmartspaceTarget.FEATURE_UNDEFINED)
            .setHeaderAction(header)
            .setBaseAction(base)
            .setTemplateData(templateBuilder.build())
            .setCreationTimeMillis(System.currentTimeMillis())
            // Highest score: the date line comes first, like Google's.
            .setScore(2f)
            .build()
    }

    private fun tapAction(id: String, intent: PendingIntent): TapAction =
        TapAction.Builder(id)
            .setPendingIntent(intent)
            .setShouldShowOnLockscreen(false)
            .build()

    /** Calendar opened at today, behind the unlock gate. */
    private fun calendarIntent(context: Context): PendingIntent {
        val uri = ContentUris.appendId(
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time"),
            System.currentTimeMillis()
        ).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = PendingIntent.getActivity(
            context, REQUEST_CALENDAR, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, REQUEST_CALENDAR)
    }

    /** Pixel Weather if installed, behind the unlock gate; otherwise null (tap opens the calendar). */
    private fun weatherIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(PIXEL_WEATHER) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = PendingIntent.getActivity(
            context, REQUEST_WEATHER, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, REQUEST_WEATHER)
    }
}
