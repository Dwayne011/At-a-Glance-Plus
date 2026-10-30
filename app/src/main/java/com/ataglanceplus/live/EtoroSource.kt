package com.ataglanceplus.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.ataglanceplus.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's own real eToro portfolio, read with their read-only API keys from eToro's public
 * API (GET /api/v1/trading/info/real/pnl). Totals follow eToro's own guides ("Calculate Equity",
 * "Calculate Profit/Loss"):
 *
 *  - Profit/loss = Σ positions' unrealised P/L + Σ copied positions' unrealised P/L + Σ copies'
 *    closed-position net profit.
 *  - Equity = available cash + total invested + profit/loss, where available cash is credit less
 *    pending orders, and total invested is open positions, copies and pending orders.
 *
 * The percentage (not given by eToro) is profit/loss against the amount in open positions and
 * copies. eToro keeps no history of an account's value for the API, so each refresh is recorded
 * here to draw one from now on. Amounts are in the account currency, USD.
 */
object EtoroSource {

    private const val TAG = "EtoroSource"
    private const val BASE = "https://public-api.etoro.com"
    private const val HISTORY_FILE = "etoro_history.json"
    /** Snapshots closer together than this replace the latest one rather than adding another. */
    private const val SNAPSHOT_GAP_MS = 10 * 60_000L
    private const val WEEK_MS = 7 * 24 * 60 * 60_000L
    private const val MAX_SNAPSHOTS = 1_500

    /** One instrument held, all its positions together. */
    data class Holding(
        val instrumentId: Int,
        val name: String,
        val symbol: String?,
        val invested: Double,
        val pnl: Double,
        val positions: Int,
    ) {
        val value: Double get() = invested + pnl
        val pnlPercent: Double get() = if (invested != 0.0) 100 * pnl / invested else 0.0
    }

    /** A trader being copied. */
    data class Copy(val username: String, val invested: Double, val pnl: Double) {
        val value: Double get() = invested + pnl
        val pnlPercent: Double get() = if (invested != 0.0) 100 * pnl / invested else 0.0
    }

    data class Portfolio(
        val at: Long,
        val equity: Double,
        val cash: Double,
        /** Everything invested, including pending orders (eToro's "total invested"). */
        val invested: Double,
        val pnl: Double,
        /** Invested in open positions and copies: the base of [pnlPercent]. */
        val openInvested: Double,
        val holdings: List<Holding>,
        val copies: List<Copy>,
    ) {
        val pnlPercent: Double get() = if (openInvested != 0.0) 100 * pnl / openInvested else 0.0
    }

    /** One recorded moment: when, the portfolio's value and its profit/loss. */
    data class Snapshot(val at: Long, val equity: Double, val pnl: Double)

    private val _portfolio = MutableStateFlow<Portfolio?>(null)
    val portfolio: StateFlow<Portfolio?> = _portfolio.asStateFlow()

    /** What the last refresh did, for settings: "" before any, else a sentence. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val lock = Mutex()

    fun hasKeys(context: Context): Boolean = EtoroKeys.load(context) != null

    /** Fetches the portfolio now; keeps the last one if eToro does not answer. Returns the error, or null. */
    suspend fun refresh(context: Context): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            val keys = EtoroKeys.load(context)
            if (keys == null) {
                _portfolio.value = null
                _status.value = ""
                return@withContext "No eToro keys yet."
            }
            try {
                val root = get("/api/v1/trading/info/real/pnl", keys)
                val portfolio = parse(context, root, keys)
                _portfolio.value = portfolio
                record(context, portfolio)
                _status.value = "Updated"
                null
            } catch (e: EtoroException) {
                _status.value = e.message.orEmpty()
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                val why = "Could not read the portfolio: ${e.message ?: e.javaClass.simpleName}"
                _status.value = why
                why
            }
        }
    }

    /** Forgets the portfolio and its history (the keys were removed). */
    fun clear(context: Context) {
        _portfolio.value = null
        _status.value = ""
        File(context.filesDir, HISTORY_FILE).delete()
    }

    private class EtoroException(message: String) : Exception(message)

    private fun get(path: String, keys: EtoroKeys.Keys): JSONObject {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("x-request-id", UUID.randomUUID().toString())
            setRequestProperty("x-api-key", keys.apiKey)
            setRequestProperty("x-user-key", keys.userKey)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw EtoroException(
                    when (code) {
                        401 -> "eToro did not accept the keys (401). Check both keys, and that the key is for Real, not Demo."
                        403 -> "eToro refused (403): the key may lack Read permission, or the account needs verifying."
                        429 -> "eToro asked to slow down (429). It will try again shortly."
                        else -> "eToro answered $code."
                    } + body.take(200).trim().let { if (it.isEmpty()) "" else " ($it)" }
                )
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(context: Context, root: JSONObject, keys: EtoroKeys.Keys): Portfolio {
        val portfolio = root.obj("clientPortfolio") ?: root
        fun pnlOf(p: JSONObject) = p.obj("unrealizedPnL")?.num("pnL") ?: p.num("pnL") ?: 0.0
        val positions = portfolio.list("positions")
        val mirrors = portfolio.list("mirrors")
        val orders = portfolio.list("orders")
        val ordersForOpen = portfolio.list("ordersForOpen").filter { (it.num("mirrorID")?.toInt() ?: 0) == 0 }

        val pending = ordersForOpen.sumOf { it.num("amount") ?: 0.0 } + orders.sumOf { it.num("amount") ?: 0.0 }
        val cash = (portfolio.num("credit", "credits") ?: 0.0) - pending

        val copies = mirrors.map { m ->
            val copied = m.list("positions")
            val closed = m.num("closedPositionsNetProfit") ?: 0.0
            Copy(
                username = m.str("parentUsername") ?: "Copied trader",
                invested = copied.sumOf { it.num("amount") ?: 0.0 } + (m.num("availableAmount") ?: 0.0) - closed,
                pnl = copied.sumOf(::pnlOf) + closed,
            )
        }
        val ownInvested = positions.sumOf { it.num("amount") ?: 0.0 }
        val pnl = positions.sumOf(::pnlOf) + copies.sumOf { it.pnl }
        val openInvested = ownInvested + copies.sumOf { it.invested }
        val invested = openInvested + pending + ordersForOpen.sumOf { it.num("totalExternalCosts") ?: 0.0 }

        val byInstrument = positions.groupBy { it.num("instrumentID")?.toInt() ?: 0 }
        val names = names(context, byInstrument.keys.filter { it != 0 }, keys)
        val holdings = byInstrument.map { (id, list) ->
            val (name, symbol) = names[id] ?: ("Instrument $id" to null)
            Holding(id, name, symbol, list.sumOf { it.num("amount") ?: 0.0 }, list.sumOf(::pnlOf), list.size)
        }
        return Portfolio(System.currentTimeMillis(), cash + invested + pnl, cash, invested, pnl, openInvested, holdings, copies)
    }

    /** Names and tickers for instruments, cached in settings: eToro gives positions by id only. */
    private fun names(context: Context, ids: List<Int>, keys: EtoroKeys.Keys): Map<Int, Pair<String, String?>> {
        val prefs = Prefs.get(context)
        val cached = runCatching { JSONObject(prefs.getString(KEY_NAMES, "{}")!!) }.getOrDefault(JSONObject())
        val missing = ids.filter { !cached.has(it.toString()) }
        if (missing.isNotEmpty()) {
            runCatching {
                val response = get("/api/v1/market-data/instruments?instrumentIds=" + missing.joinToString(","), keys)
                response.list("instrumentDisplayDatas").forEach { d ->
                    val id = d.num("instrumentID")?.toInt() ?: return@forEach
                    cached.put(id.toString(), JSONObject().put("name", d.str("instrumentDisplayName") ?: "").put("symbol", d.str("symbolFull") ?: ""))
                }
                prefs.edit().putString(KEY_NAMES, cached.toString()).apply()
            }.onFailure { Log.w(TAG, "instrument names failed", it) }
        }
        return ids.associateWith { id ->
            val entry = cached.optJSONObject(id.toString())
            val name = entry?.optString("name")?.ifEmpty { null }
            val symbol = entry?.optString("symbol")?.ifEmpty { null }
            (name ?: symbol ?: "Instrument $id") to symbol
        }
    }

    // --- History, recorded here ---

    /**
     * How much the portfolio's value has moved today: from the last value recorded before
     * midnight (or, without one, the first recorded today) to [p]. Null until there are two
     * readings to compare. Recorded values include deposits and withdrawals, which count as moves.
     */
    fun todayChange(context: Context, p: Portfolio): Double? {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val history = history(context)
        val base = history.lastOrNull { it.at < midnight } ?: history.firstOrNull { it.at >= midnight && it.at < p.at } ?: return null
        return p.equity - base.equity
    }

    fun history(context: Context): List<Snapshot> = runCatching {
        val array = JSONArray(File(context.filesDir, HISTORY_FILE).readText())
        (0 until array.length()).map { array.getJSONArray(it) }.map { Snapshot(it.getLong(0), it.getDouble(1), it.getDouble(2)) }
    }.getOrDefault(emptyList())

    /**
     * Adds a snapshot (replacing the latest if it is under 10 minutes old). Beyond a week, one a
     * day is kept (the day's last).
     */
    private fun record(context: Context, p: Portfolio) {
        val now = Snapshot(p.at, p.equity, p.pnl)
        var list = history(context).toMutableList()
        if (list.isNotEmpty() && now.at - list.last().at < SNAPSHOT_GAP_MS) list[list.lastIndex] = now else list += now
        val zone = java.time.ZoneId.systemDefault()
        val recent = list.filter { now.at - it.at <= WEEK_MS }
        val older = list.filter { now.at - it.at > WEEK_MS }
            .groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .map { (_, day) -> day.maxBy { it.at } }
        list = (older + recent).sortedBy { it.at }.takeLast(MAX_SNAPSHOTS).toMutableList()
        val array = JSONArray()
        list.forEach { array.put(JSONArray().put(it.at).put(it.equity).put(it.pnl)) }
        File(context.filesDir, HISTORY_FILE).writeText(array.toString())
    }

    private const val KEY_NAMES = "etoro_instrument_names"

    // --- JSON, tolerant of casing: eToro's schema says "instrumentID", its examples "instrumentId" ---

    private fun JSONObject.find(name: String): Any? {
        if (has(name)) return opt(name)
        val wanted = name.lowercase()
        return keys().asSequence().firstOrNull { it.lowercase() == wanted }?.let { opt(it) }
    }

    private fun JSONObject.num(vararg names: String): Double? = names.firstNotNullOfOrNull { name ->
        when (val v = find(name)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.str(name: String): String? = (find(name) as? String)?.ifEmpty { null }

    private fun JSONObject.obj(name: String): JSONObject? = find(name) as? JSONObject

    private fun JSONObject.list(name: String): List<JSONObject> {
        val array = find(name) as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}

/**
 * The user's eToro keys, encrypted with a key held in the phone's Android Keystore (it never
 * leaves the phone), and stored in the app's settings.
 */
object EtoroKeys {

    data class Keys(val apiKey: String, val userKey: String)

    private const val ALIAS = "etoro_keys"
    private const val KEY_API = "etoro_api_key"
    private const val KEY_USER = "etoro_user_key"

    fun save(context: Context, apiKey: String, userKey: String) {
        Prefs.get(context).edit()
            .putString(KEY_API, encrypt(apiKey.trim()))
            .putString(KEY_USER, encrypt(userKey.trim()))
            .apply()
    }

    fun load(context: Context): Keys? {
        val prefs = Prefs.get(context)
        val api = prefs.getString(KEY_API, null)?.let(::decrypt) ?: return null
        val user = prefs.getString(KEY_USER, null)?.let(::decrypt) ?: return null
        return if (api.isBlank() || user.isBlank()) null else Keys(api, user)
    }

    fun clear(context: Context) {
        Prefs.get(context).edit().remove(KEY_API).remove(KEY_USER).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
        }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}
