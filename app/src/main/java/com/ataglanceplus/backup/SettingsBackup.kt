package com.ataglanceplus.backup

import android.content.Context
import com.ataglanceplus.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's settings as a portable JSON snapshot: for Android's backup to the Google account
 * ([SettingsBackupAgent]) and for export and import by hand.
 *
 * Only the settings listed here are copied (an allowlist), so secrets and device-specific state
 * kept in the same preferences file never leave the phone: the wireless-debugging key and
 * pairing, the eToro keys (encrypted with this phone's Keystore, unreadable elsewhere), caches
 * and the last location. Wallet sign-in lives in its own encrypted files and is not included
 * either; it is signed into again on a new phone.
 */
object SettingsBackup {

    private const val FORMAT = "at-a-glance-plus-settings"
    private const val VERSION = 1
    private const val SNAPSHOT = "backup/settings.json"

    private val KEYS = setOf(
        "activate_on_boot", "aod_preview", "calendar_all_day", "calendar_lead_minutes", "card_height_dp", "chips",
        "countdown_day", "countdown_name", "disabled_cards", "finance_open", "finance_show_moves",
        "finance_show_periodic", "finance_period_hours", "finance_refresh_minutes", "finance_move_threshold",
        "finance_tickers", "finance_windows", "finance_show_windows", "finance_vwap_source", "finance_vwap_anchor",
        "finance_extended_hours", "media_wave",
        "wallet_rules", "show_all_cards", "sports_after_hours", "sports_before_hours", "sports_followed",
        "sports_hidden", "sports_next_always", "sports_open", "sports_race_step", "step_goal", "weather_look",
    )
    private val PREFIXES = listOf("chip_app_", "chip_size_", "gmail_labels_")

    private fun included(key: String) = key in KEYS || PREFIXES.any { key.startsWith(it) }

    /** The settings as JSON text, each value with its type so it is restored exactly. */
    fun export(context: Context): String {
        val values = JSONObject()
        Prefs.get(context).all.forEach { (key, value) ->
            if (!included(key) || value == null) return@forEach
            val entry = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> return@forEach
            }
            values.put(key, entry)
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", version ?: "")
            .put("exported", System.currentTimeMillis())
            .put("settings", values)
            .toString(2)
    }

    /**
     * Replaces the settings with those in [json]. Settings not in the allowlist (secrets, pairing,
     * caches) are kept as they are. Returns how many settings were restored, or throws if [json]
     * is not a settings file from this app.
     */
    fun import(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This isn't an At a Glance Plus settings file." }
        require(root.optInt("version") <= VERSION) { "This settings file is from a newer version of the app." }
        val values = root.getJSONObject("settings")
        val prefs = Prefs.get(context)
        val edit = prefs.edit()
        // Settings the file does not have go back to their defaults, as on the phone it came from.
        prefs.all.keys.filter { included(it) }.forEach { edit.remove(it) }
        var count = 0
        values.keys().forEach { key ->
            if (!included(key)) return@forEach
            val entry = values.getJSONObject(key)
            when (entry.getString("t")) {
                "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                "i" -> edit.putInt(key, entry.getInt("v"))
                "l" -> edit.putLong(key, entry.getLong("v"))
                "f" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> edit.putString(key, entry.getString("v"))
                "ss" -> edit.putStringSet(key, entry.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> return@forEach
            }
            count++
        }
        edit.apply()
        return count
    }

    /** Writes the snapshot that the Google backup takes (it backs up this file only). */
    fun writeSnapshot(context: Context) {
        val file = File(context.filesDir, SNAPSHOT)
        file.parentFile?.mkdirs()
        file.writeText(export(context))
    }

    /** After a restore from the Google account: applies the restored snapshot, then removes it. */
    fun applySnapshot(context: Context) {
        val file = File(context.filesDir, SNAPSHOT)
        if (!file.exists()) return
        runCatching { import(context, file.readText()) }
        file.delete()
    }
}
