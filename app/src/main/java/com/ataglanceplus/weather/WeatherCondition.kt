package com.ataglanceplus.weather

import androidx.annotation.DrawableRes
import com.ataglanceplus.R

/** WMO weather interpretation codes, as returned by Open-Meteo, grouped into what we draw. */
enum class WeatherCondition(val label: String, @DrawableRes val dayIcon: Int, @DrawableRes val nightIcon: Int) {
    CLEAR("Clear", R.drawable.ic_weather_clear_day, R.drawable.ic_weather_clear_night),
    PARTLY_CLOUDY("Partly cloudy", R.drawable.ic_weather_partly_cloudy_day, R.drawable.ic_weather_partly_cloudy_night),
    CLOUDY("Cloudy", R.drawable.ic_weather_cloudy, R.drawable.ic_weather_cloudy),
    FOG("Fog", R.drawable.ic_weather_fog, R.drawable.ic_weather_fog),
    DRIZZLE("Drizzle", R.drawable.ic_weather_rain, R.drawable.ic_weather_rain),
    RAIN("Rain", R.drawable.ic_weather_rain, R.drawable.ic_weather_rain),
    SNOW("Snow", R.drawable.ic_weather_snow, R.drawable.ic_weather_snow),
    THUNDER("Thunderstorm", R.drawable.ic_weather_thunder, R.drawable.ic_weather_thunder);

    fun icon(isDay: Boolean): Int = if (isDay) dayIcon else nightIcon

    /** Card background for these conditions. */
    @DrawableRes
    fun background(isDay: Boolean): Int = when (this) {
        CLEAR, PARTLY_CLOUDY -> if (isDay) R.drawable.card_weather_bg_clear_day else R.drawable.card_weather_bg_clear_night
        CLOUDY -> R.drawable.card_weather_bg_cloudy
        FOG -> R.drawable.card_weather_bg_fog
        DRIZZLE, RAIN -> R.drawable.card_weather_bg_rain
        SNOW -> R.drawable.card_weather_bg_snow
        THUNDER -> R.drawable.card_weather_bg_thunder
    }

    companion object {
        fun fromWmo(code: Int): WeatherCondition = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDER
            else -> CLOUDY
        }
    }
}
