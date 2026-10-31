package com.ataglanceplus.wallet

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Places for Wallet passes, from Photon (photon.komoot.io): a free search over OpenStreetMap
 * made for typing ahead, nearest first when a location is given. Also finds every branch of a
 * chain ("any Tesco") around the user, kept on the phone and looked up again after a move of a
 * couple of kilometres or a day, so the pass can follow the user anywhere.
 */
object PlaceSearch {

    private const val TAG = "PlaceSearch"
    private const val BASE = "https://photon.komoot.io/api/"
    private const val PREFS = "wallet_chains"

    /** How far round the user a chain's branches are looked for (about 11 km each way). */
    private const val AREA_DEGREES = 0.1
    /** Look again once the user is this far from where the branches were found, or after a day. */
    private const val MOVE_M = 2_000f
    private const val KEEP_MS = 24 * 60 * 60_000L

    /** Generic words after a chain's name ("Tesco Extra" is a Tesco). */
    private val QUALIFIERS = setOf("express", "extra", "superstore", "metro", "local", "store", "supermarket", "petrol", "station", "megastore", "outlet")
    /** Places that carry a shop's name but are not the shop. */
    private val NOT_SHOPS = setOf("parking", "parking_entrance", "atm", "car_wash", "bicycle_parking", "charging_station", "vending_machine", "post_box")

    data class Suggestion(
        val name: String,
        /** Street and town, for the second line. */
        val detail: String,
        val lat: Double,
        val lng: Double,
        /** A shop, café, gym… (not an address or a road). */
        val business: Boolean,
        val distanceM: Float?,
    )

    /** Places matching [query] as typed, nearest first when [near] is known. Blocking. */
    fun suggest(query: String, near: Location?): List<Suggestion> {
        val url = BASE + "?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=15&lang=en" +
            (near?.let { "&lat=${it.latitude}&lon=${it.longitude}&location_bias_scale=0.1&zoom=12" } ?: "")
        return features(url).mapNotNull { suggestion(it, near) }.filter { it.business || it.detail.isNotEmpty() }
    }

    /**
     * A chain the suggestions point to, for "any …": the business name (without words like
     * "Express") that most suggestions share, if it starts as typed. Null when there is none.
     */
    fun chainFor(query: String, suggestions: List<Suggestion>): String? {
        val typed = query.trim().lowercase(Locale.getDefault())
        if (typed.length < 2) return null
        return suggestions.filter { it.business }.map { chainName(it.name) }
            .filter { it.lowercase(Locale.getDefault()).startsWith(typed) || typed.startsWith(it.lowercase(Locale.getDefault())) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /** "Tesco Express" as "Tesco"; "Costa Coffee" stays as it is. */
    fun chainName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).toMutableList()
        while (words.size > 1 && words.last().lowercase(Locale.ROOT) in QUALIFIERS) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }

    /** Every branch of [chain] within about 11 km of [near], looked up now. Blocking. */
    fun findBranches(chain: String, near: Location): List<Pair<Double, Double>> {
        val box = listOf(near.longitude - AREA_DEGREES * 1.6, near.latitude - AREA_DEGREES, near.longitude + AREA_DEGREES * 1.6, near.latitude + AREA_DEGREES)
            .joinToString(",") { String.format(Locale.ROOT, "%.5f", it) }
        val url = BASE + "?q=" + URLEncoder.encode(chain, "UTF-8") + "&limit=50&lat=${near.latitude}&lon=${near.longitude}&bbox=$box"
        val wanted = chain.lowercase(Locale.getDefault())
        return features(url).mapNotNull { f ->
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            if (!isBusiness(p)) return@mapNotNull null
            if (!p.optString("name").lowercase(Locale.getDefault()).contains(wanted)) return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            c.optDouble(1) to c.optDouble(0)
        }.distinct()
    }

    /** [chain]'s branches as last found (empty before the first look). */
    fun branches(context: Context, chain: String): List<Pair<Double, Double>> =
        stored(context, chain)?.points.orEmpty()

    /**
     * Looks up the branches of each of [chains] again where they are missing, the user has moved
     * [MOVE_M] since, or they are a day old. Returns true if any changed. Blocking.
     */
    fun refreshBranches(context: Context, chains: Collection<String>, near: Location): Boolean {
        var changed = false
        chains.distinct().forEach { chain ->
            val old = stored(context, chain)
            val stale = old == null || System.currentTimeMillis() - old.at > KEEP_MS ||
                FloatArray(1).also { Location.distanceBetween(old.lat, old.lng, near.latitude, near.longitude, it) }[0] > MOVE_M
            if (!stale) return@forEach
            val found = runCatching { findBranches(chain, near) }.getOrNull() ?: return@forEach
            store(context, chain, Stored(near.latitude, near.longitude, System.currentTimeMillis(), found))
            changed = changed || found != old?.points
        }
        return changed
    }

    private data class Stored(val lat: Double, val lng: Double, val at: Long, val points: List<Pair<Double, Double>>)

    private fun key(chain: String) = "chain_" + chain.lowercase(Locale.ROOT)

    private fun stored(context: Context, chain: String): Stored? = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(chain), null) ?: return null)
        val a = o.getJSONArray("p")
        Stored(o.getDouble("lat"), o.getDouble("lng"), o.getLong("at"), (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } })
    }.getOrNull()

    private fun store(context: Context, chain: String, s: Stored) {
        val o = JSONObject().put("lat", s.lat).put("lng", s.lng).put("at", s.at)
            .put("p", JSONArray(s.points.map { JSONArray().put(it.first).put(it.second) }))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(chain), o.toString()) }
    }

    private fun isBusiness(p: JSONObject): Boolean {
        val key = p.optString("osm_key")
        val value = p.optString("osm_value")
        return key in setOf("shop", "amenity", "leisure", "tourism", "office", "craft") && value !in NOT_SHOPS
    }

    private fun suggestion(f: JSONObject, near: Location?): Suggestion? {
        val p = f.optJSONObject("properties") ?: return null
        if (p.optString("osm_value") in setOf("bus_stop", "platform", "stop_position")) return null
        val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val lat = c.optDouble(1)
        val lng = c.optDouble(0)
        val street = listOfNotNull(
            p.optString("housenumber").ifEmpty { null }?.let { n -> p.optString("street").ifEmpty { null }?.let { "$n $it" } } ?: p.optString("street").ifEmpty { null },
            p.optString("city").ifEmpty { p.optString("district") }.ifEmpty { p.optString("county") }.ifEmpty { null },
        )
        val name = p.optString("name").ifEmpty { street.firstOrNull() ?: return null }
        val distance = near?.let { FloatArray(1).also { d -> Location.distanceBetween(it.latitude, it.longitude, lat, lng, d) }[0] }
        return Suggestion(name, street.filter { it != name }.joinToString(", "), lat, lng, isBusiness(p), distance)
    }

    private fun features(url: String): List<JSONObject> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) return emptyList()
            val a = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("features") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}
