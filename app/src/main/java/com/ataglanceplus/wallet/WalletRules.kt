package com.ataglanceplus.wallet

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * When and where each Wallet pass appears on the deck. A pass carries no location, so the user
 * gives it rules: any number of places (shown within a radius of each) and time windows. Flights,
 * events and transit can also use their own times automatically. A pass with a rule but no
 * triggers at all is simply pinned (always shown).
 */
object WalletRules {

    private const val FILE = "settings"
    const val KEY_RULES = "wallet_rules"

    /** Hours before a flight/event/transit begins that it starts showing, if the pass gives no time. */
    private const val AUTO_LEAD_MS = 3L * 60 * 60 * 1000
    private const val AUTO_TRAIL_MS = 3L * 60 * 60 * 1000

    data class Place(val label: String, val lat: Double, val lng: Double, val radiusM: Int)

    /** A daily window. [days] holds Calendar.DAY_OF_WEEK values (1=Sun..7=Sat); empty means every day. */
    data class Window(val days: Set<Int>, val startMin: Int, val endMin: Int)

    data class Rule(
        val passId: String,
        val places: List<Place> = emptyList(),
        val windows: List<Window> = emptyList(),
        val auto: Boolean = true,
    ) {
        val pinned: Boolean get() = places.isEmpty() && windows.isEmpty() && !auto
    }

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

    /** All the places across every rule, for registering geofences. */
    fun allPlaces(context: Context): List<Pair<String, Place>> =
        rules(context).flatMap { rule -> rule.places.map { rule.passId to it } }

    /**
     * The passes to show right now: every rule whose location, time window, or own time matches
     * (or that is pinned). [location] may be null when it isn't known.
     */
    fun activePassIds(context: Context, now: Long, location: Location?): Set<String> {
        WalletSource.ensureLoaded(context)
        val passes = WalletSource.passes.value.associateBy { it.id }
        return rules(context).filter { rule ->
            val pass = passes[rule.passId] ?: return@filter false
            if (pass.expired(now)) return@filter false
            rule.pinned ||
                rule.windows.any { it.matches(now) } ||
                (rule.auto && autoMatch(pass, now)) ||
                (location != null && rule.places.any { within(location, it) })
        }.map { it.passId }.toSet()
    }

    private fun autoMatch(pass: WalletPass, now: Long): Boolean {
        val begins = pass.showFrom ?: pass.start?.minus(AUTO_LEAD_MS) ?: return false
        val ends = pass.end ?: pass.start?.plus(AUTO_TRAIL_MS) ?: (begins + AUTO_LEAD_MS + AUTO_TRAIL_MS)
        return now in begins..ends
    }

    private fun within(location: Location, place: Place): Boolean {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, place.lat, place.lng, result)
        return result[0] <= place.radiusM
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
            places.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lng", it.lng).put("r", it.radiusM))
        }
        val windows = JSONArray()
        rule.windows.forEach {
            windows.put(JSONObject().put("days", JSONArray(it.days.toList())).put("s", it.startMin).put("e", it.endMin))
        }
        return JSONObject().put("id", rule.passId).put("places", places).put("windows", windows).put("auto", rule.auto)
    }

    private fun fromJson(o: JSONObject): Rule {
        val places = o.optJSONArray("places")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                Place(it.optString("label"), it.getDouble("lat"), it.getDouble("lng"), it.optInt("r", 150))
            }
        }.orEmpty()
        val windows = o.optJSONArray("windows")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.map { w ->
                val daysArray = w.optJSONArray("days") ?: JSONArray()
                Window((0 until daysArray.length()).map { daysArray.getInt(it) }.toSet(), w.optInt("s"), w.optInt("e"))
            }
        }.orEmpty()
        return Rule(o.getString("id"), places, windows, o.optBoolean("auto", true))
    }
}
