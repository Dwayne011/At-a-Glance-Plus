package com.ataglanceplus.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * The user's Google Wallet passes, synced the way SmartspacerPlugins' Google Wallet plugin does it
 * (see NOTICE), parsed from the protobuf sync response and kept encrypted on the phone (see
 * [SecureStore]). Passes carry no locations; where and when each one shows is the user's own rules
 * (see WalletRules), except that flights, events and transit also know their own times.
 *
 * Google offers no supported way to read Wallet passes, so this uses the account's Wallet token
 * ([GoogleAccount]); it is against Google's terms and the user is warned before signing in.
 */
object WalletSource {

    private const val TAG = "WalletSource"
    private const val SYNC_URL = "https://pay-users-pa.googleapis.com/g/valuables/syncvaluables"
    private const val STORE = "wallet_valuables.json"
    // The fixed request header the plugin sends (a list of supported valuable field tags).
    private const val REQUEST_HEADER = "ExoBBA8FCxQMCAcJAwYOCg0QEhYYHA=="

    private val _passes = MutableStateFlow<List<WalletPass>>(emptyList())
    val passes: StateFlow<List<WalletPass>> = _passes.asStateFlow()

    @Volatile private var loaded = false

    /** Reads what is already on disk into [passes], once. Safe to call often. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        _passes.value = parseAll(readStore(context))
    }

    fun pass(context: Context, id: String): WalletPass? {
        ensureLoaded(context)
        return _passes.value.firstOrNull { it.id == id }
    }

    /**
     * Fetches changes from Google and merges them in. Returns true if the sync succeeded (whether or
     * not anything changed). Blocks; call off the main thread.
     */
    fun sync(context: Context): Boolean {
        ensureLoaded(context)
        val token = GoogleAccount.walletToken(context) ?: return false
        val stored = readStore(context)

        var response = request(token, stored)
        if (response == null) {
            // A stale Wallet token reads as 401; drop it and try once with a fresh one.
            GoogleAccount.clearWalletToken(context)
            val fresh = GoogleAccount.walletToken(context) ?: return false
            response = request(fresh, stored) ?: return false
        }

        val merged = merge(stored, response)
        writeStore(context, merged)
        _passes.value = parseAll(merged)
        return true
    }

    /** Forgets every synced pass (on sign-out). */
    fun clear(context: Context) {
        SecureStore.delete(context, STORE)
        _passes.value = emptyList()
        loaded = true
    }

    // --- Network ---------------------------------------------------------------------------------

    /** The sync response bytes, or null on a 401 (so the caller can refresh the token) or failure. */
    private fun request(token: String, stored: List<Stored>): ByteArray? {
        val body = ProtoWriter().message(2) {           // SyncValuablesRequest.inner
            message(2) {                                 // .request
                bytes(1, SecureStore.unbase64(REQUEST_HEADER))
                string(2, ZoneId.systemDefault().getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH))
                stored.forEach { s ->                    // .cached { id, hash }
                    message(3) {
                        string(1, s.id)
                        string(2, s.hash)
                    }
                }
            }
        }.toByteArray()

        val connection = (URL(SYNC_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/x-protobuf")
        }
        return try {
            connection.outputStream.use { it.write(body) }
            when (val code = connection.responseCode) {
                in 200..299 -> connection.inputStream.use { it.readBytes() }
                401 -> null
                else -> {
                    Log.w(TAG, "sync HTTP $code")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    // --- Merge & storage -------------------------------------------------------------------------

    private data class Stored(val id: String, val hash: String, val raw: ByteArray)

    /** Applies the server's changed/removed valuables onto what we already hold. */
    private fun merge(stored: List<Stored>, responseBytes: ByteArray): List<Stored> {
        val byId = stored.associateBy { it.id }.toMutableMap()
        val response = Proto(responseBytes)
        // SyncValuablesResponse{2: inner{2: valuables{1: repeated valuable}}}
        val valuables = response.msg(2)?.msg(2)?.msgs(1).orEmpty()
        for (v in valuables) {
            val id = v.str(2) ?: continue
            val wrapper = v.bytes(4)
            val hash = v.str(3)
            if (wrapper == null || hash == null || hash == "0") {
                byId.remove(id)                          // deleted or archived away
            } else {
                byId[id] = Stored(id, hash, wrapper)
            }
        }
        return byId.values.toList()
    }

    private fun readStore(context: Context): List<Stored> {
        val text = SecureStore.read(context, STORE)?.toString(Charsets.UTF_8) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Stored(o.getString("id"), o.getString("hash"), SecureStore.unbase64(o.getString("raw")))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeStore(context: Context, items: List<Stored>) {
        val array = JSONArray()
        items.forEach { s ->
            array.put(JSONObject().put("id", s.id).put("hash", s.hash).put("raw", SecureStore.base64(s.raw)))
        }
        SecureStore.write(context, STORE, array.toString().toByteArray(Charsets.UTF_8))
    }

    private fun parseAll(items: List<Stored>): List<WalletPass> =
        items.mapNotNull { runCatching { WalletPass.parse(it.id, Proto(it.raw)) }.getOrNull() }
            .filter { it.active }
}
