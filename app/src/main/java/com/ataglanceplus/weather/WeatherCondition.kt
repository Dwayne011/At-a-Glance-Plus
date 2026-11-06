package com.ataglanceplus.weather

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
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

    /** Outline icon for the always-on display. */
    @DrawableRes
    fun aodIcon(isDay: Boolean): Int = when (this) {
        CLEAR -> if (isDay) R.drawable.ic_weather_clear_day_aod else R.drawable.ic_weather_clear_night_aod
        PARTLY_CLOUDY, CLOUDY, FOG -> R.drawable.ic_weather_cloudy_aod
        DRIZZLE, RAIN -> R.drawable.ic_weather_rain_aod
        SNOW -> R.drawable.ic_weather_snow_aod
        THUNDER -> R.drawable.ic_weather_thunder_aod
    }

    /** The scene's sky (Scenes looks). */
    @DrawableRes
    fun sceneSky(isDay: Boolean): Int = when (this) {
        CLEAR -> if (isDay) R.drawable.scene_sky_clear_day else R.drawable.scene_sky_clear_night
        PARTLY_CLOUDY -> if (isDay) R.drawable.scene_sky_partly_day else R.drawable.scene_sky_partly_night
        CLOUDY -> if (isDay) R.drawable.scene_sky_cloudy_day else R.drawable.scene_sky_cloudy_night
        FOG -> if (isDay) R.drawable.scene_sky_fog_day else R.drawable.scene_sky_fog_night
        DRIZZLE, RAIN -> if (isDay) R.drawable.scene_sky_rain_day else R.drawable.scene_sky_rain_night
        SNOW -> if (isDay) R.drawable.scene_sky_snow_day else R.drawable.scene_sky_snow_night
        THUNDER -> if (isDay) R.drawable.scene_sky_thunder_day else R.drawable.scene_sky_thunder_night
    }

    /** The weather drawn over the sky, back to front (Scenes looks). */
    fun sceneLayers(isDay: Boolean): List<SceneLayer> = when (this) {
        CLEAR -> if (isDay) listOf(SceneLayer.SUN) else listOf(SceneLayer.STARS, SceneLayer.WISPS)
        PARTLY_CLOUDY -> listOf(if (isDay) SceneLayer.SUN else SceneLayer.STARS, if (isDay) SceneLayer.CLOUDS_LIGHT else SceneLayer.CLOUDS_LIGHT_NIGHT)
        CLOUDY -> listOf(if (isDay) SceneLayer.CLOUDS_HEAVY else SceneLayer.CLOUDS_HEAVY_NIGHT)
        FOG -> listOf(SceneLayer.FOG)
        DRIZZLE, RAIN -> listOf(if (isDay) SceneLayer.CLOUDS_HEAVY else SceneLayer.CLOUDS_HEAVY_NIGHT, SceneLayer.RAIN)
        SNOW -> listOf(if (isDay) SceneLayer.CLOUDS_HEAVY else SceneLayer.CLOUDS_HEAVY_NIGHT, SceneLayer.SNOW)
        THUNDER -> listOf(if (isDay) SceneLayer.CLOUDS_HEAVY else SceneLayer.CLOUDS_HEAVY_NIGHT, SceneLayer.RAIN, SceneLayer.BOLT)
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

/**
 * A layer of weather over a scene's sky: a still image (Scenes) and its animated form
 * (Animated scenes), added to the card only when needed. Lightning has no still form.
 */
enum class SceneLayer(@DrawableRes val still: Int?, @IdRes val animated: Int) {
    SUN(R.drawable.scene_sun, R.id.scene_anim_sun),
    STARS(R.drawable.scene_stars, R.id.scene_anim_stars),
    WISPS(R.drawable.scene_wisps, R.id.scene_anim_wisps),
    CLOUDS_LIGHT(R.drawable.scene_clouds_light, R.id.scene_anim_clouds_light),
    CLOUDS_LIGHT_NIGHT(R.drawable.scene_clouds_light_night, R.id.scene_anim_clouds_light_night),
    CLOUDS_HEAVY(R.drawable.scene_clouds_heavy, R.id.scene_anim_clouds_heavy),
    CLOUDS_HEAVY_NIGHT(R.drawable.scene_clouds_heavy_night, R.id.scene_anim_clouds_heavy_night),
    FOG(R.drawable.scene_fog, R.id.scene_anim_fog),
    RAIN(R.drawable.scene_rain, R.id.scene_anim_rain),
    SNOW(R.drawable.scene_snow, R.id.scene_anim_snow),
    BOLT(null, R.id.scene_anim_bolt),
}

/** The weather card's look: the plain gradient, a still scene, or a moving one. */
enum class WeatherLook(val label: String) { STANDARD("Standard"), SCENES("Scenes"), ANIMATED("Animated scenes") }

