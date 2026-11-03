package com.ataglanceplus.wallet

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Which Wallet passes appear on the deck, and when and where. A pass shows only once the user
 * switches it on. Boarding passes, event tickets and transit tickets then follow their own times,
 * as in Google Wallet. Any other pass carries no location, so the user gives it places (a spot,
 * or any branch of a chain, each within a radius) and time windows; with neither, it always shows.
 */
object WalletRules {

    private const val FILE = "settings"
    const val KEY_RULES = "wallet_rules"

    /** Hours before a flight/event/transit begins that it starts showing, if the pass gives no time. */
    private const val AUTO_LEAD_MS = 3L * 60 * 60 * 1000
    private const val AUTO_TRAIL_MS = 3L * 60 * 60 * 1000
    private const val FIX_FRESH_MS = 30L * 60 * 1000

    /** How far a fix is from the place (its nearest branch for a chain), in metres, or null. */
    fun distanceTo(context: Context, location: Location, place: Place): Float? {
        val spots = if (place.chain == null) listOf(place.lat to place.lng) else PlaceSearch.branches(context, place.chain)
        return spots.minOfOrNull { (lat, lng) -> FloatArray(1).also { Location.distanceBetween(location.latitude, location.longitude, lat, lng, it) }[0] }
    }

    /**
     * A spot ([lat], [lng]) or, with [chain], any branch of that chain near the user (see
     * [PlaceSearch]); [detail] is the street and town for a spot.
     */
    data class Place(
        val label: String, val lat: Double, val lng: Double, val radiusM: Int,
        val chain: String? = null, val detail: String? = null,
    )

    /** A daily window. [days] holds Calendar.DAY_OF_WEEK values (1=Sun..7=Sat); empty means every day. */
    data class Window(val days: Set<Int>, val startMin: Int, val endMin: Int)

    data class Rule(
        val passId: String,
        val places: List<Place> = emptyList(),
        val windows: List<Window> = emptyList(),
        val auto: Boolean = true,
        /** Shown on the card at all (the switch beside the pass). */
        val enabled: Boolean = false,
    )

    /** Passes that carry their own times and need no places or times from the user. */
    val AUTOMATIC = setOf(WalletPass.Kind.FLIGHT, WalletPass.Kind.EVENT, WalletPass.Kind.TRANSIT)

    /** The chains in every switched-on rule, for looking up their branches. */
    fun chains(context: Context): Set<String> =
        rules(context).filter { it.enabled }.flatMap { r -> r.places.mapNotNull { it.chain } }.toSet()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rules(context: Context): List<Rule> {
        val raw = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun ruleFor(context: Context, passId: String): Rule? = rules(context).firstOrNull { it.passId == passId }

    fun upsert(context: Context, rule: Rule) {
        val list = rules(context).filterNot { it.passId == rule.passId } + rule
        save(context, list)
    }

    fun remove(context: Context, passId: String) {
        save(context, rules(context).filterNot { it.passId == passId })
    }

    private fun save(context: Context, list: List<Rule>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply()
    }

    /**
     * Every spot to watch, for geofences: each switched-on rule's spots, and each branch of its
     * chains as last found near the user.
     */
    fun allPlaces(context: Context): List<Pair<String, Place>> =
        rules(context).filter { it.enabled }.flatMap { rule ->
            rule.places.flatMap { place ->
                if (place.chain == null) listOf(rule.passId to place)
                else PlaceSearch.branches(context, place.chain).map { (lat, lng) -> rule.passId to place.copy(lat = lat, lng = lng) }
            }
        }

    /**
     * The passes to show right now: every rule whose location, time window, or own time matches
     * (or that is pinned). [location] may be null when it isn't known.
     */
    fun activePassIds(context: Context, now: Long, location: Location?): Set<String> {
        WalletSource.ensureLoaded(context)
        val passes = WalletSource.passes.value.associateBy { it.id }
        // At a place: Play services says the phone is inside one of the pass's geofences, or a
        // recent fix (under 30 minutes old) is within the place's radius. An old fix no longer
        // counts, so a pass does not stay up long after leaving.
        val inside = WalletGeofences.insidePasses()
        val fix = location?.takeIf { now - it.time < FIX_FRESH_MS }
        return rules(context).filter { rule ->
            if (!rule.enabled) return@filter false
            val pass = passes[rule.passId] ?: return@filter false
            if (pass.expired(now)) return@filter false
            // Boarding passes, tickets: their own times only, as in Google Wallet.
            if (pass.kind in AUTOMATIC) return@filter autoMatch(pass, now)
            (rule.places.isEmpty() && rule.windows.isEmpty()) ||
                rule.windows.any { it.matches(now) } ||
                (rule.places.isNotEmpty() && rule.passId in inside) ||
                (fix != null && rule.places.any { place -> near(context, fix, place) })
        }.map { it.passId }.toSet()
    }

    private fun autoMatch(pass: WalletPass, now: Long): Boolean {
        val begins = pass.showFrom ?: pass.start?.minus(AUTO_LEAD_MS) ?: return false
        val ends = pass.end ?: pass.start?.plus(AUTO_TRAIL_MS) ?: (begins + AUTO_LEAD_MS + AUTO_TRAIL_MS)
        return now in begins..ends
    }

    /** Within the place's radius: of the spot, or of any branch of its chain. */
    private fun near(context: Context, location: Location, place: Place): Boolean {
        val spots = if (place.chain == null) listOf(place.lat to place.lng) else PlaceSearch.branches(context, place.chain)
        return spots.any { (lat, lng) -> within(location, lat, lng, place.radiusM) }
    }

    private fun within(location: Location, lat: Double, lng: Double, radiusM: Int): Boolean {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lat, lng, result)
        return result[0] <= radiusM
    }

    private fun Window.matches(now: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        if (days.isNotEmpty() && calendar.get(Calendar.DAY_OF_WEEK) !in days) return false
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (endMin >= startMin) minuteOfDay in startMin..endMin
        else minuteOfDay >= startMin || minuteOfDay <= endMin   // wraps past midnight
    }

    // --- JSON ------------------------------------------------------------------------------------

    private fun toJson(rule: Rule): JSONObject {
        val places = JSONArray()
        rule.places.forEach {
            places.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lng", it.lng).put("r", it.radiusM)
                .put("chain", it.chain).put("detail", it.detail))
        }
        val windows = JSONArray()
        rule.windows.forEach {
            windows.put(JSONObject().put("days", JSONArray(it.days.toList())).put("s", it.startMin).put("e", it.endMin))
        }
        return JSONObject().put("id", rule.passId).put("places", places).put("windows", windows).put("auto", rule.auto).put("on", rule.enabled)
    }

    private fun fromJson(o: JSONObject): Rule {
        val places = o.optJSONArray("places")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                Place(
                    it.optString("label"), it.optDouble("lat"), it.optDouble("lng"), it.optInt("r", 150),
                    chain = it.optString("chain").ifEmpty { null }, detail = it.optString("detail").ifEmpty { null },
                )
            }
        }.orEmpty()
        val windows = o.optJSONArray("windows")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map { w ->
                val daysArray = w.optJSONArray("days") ?: JSONArray()
                Window((0 until daysArray.length()).map { daysArray.getInt(it) }.toSet(), w.optInt("s"), w.optInt("e"))
            }
        }.orEmpty()
        // Rules from before the switch was on if they had places or times set.
        val enabled = if (o.has("on")) o.optBoolean("on") else places.isNotEmpty() || windows.isNotEmpty()
        return Rule(o.getString("id"), places, windows, o.optBoolean("auto", true), enabled)
    }
}
