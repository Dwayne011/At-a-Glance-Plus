package com.ataglanceplus.wadb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import com.ataglanceplus.Prefs
import com.ataglanceplus.adb.AdbKey
import com.ataglanceplus.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Shared helpers and live status for the wireless-debugging path. */
object Wadb {

    const val KEY_NAME = "ataglanceplus"
    private const val SETTING_WIRELESS_DEBUGGING = "adb_wifi_enabled"
    private const val SETTING_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    private val _status = MutableStateFlow("")
    /** One line describing what the activator is doing or last did, for the status screen. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow("")
    /** Each command the last activation ran and what it printed, for diagnosis. */
    val log: StateFlow<String> = _log.asStateFlow()

    fun setLog(text: String) {
        _log.value = text
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setBusy(value: Boolean) {
        _busy.value = value
    }

    fun key(context: Context): AdbKey = AdbKey(PreferenceAdbKeyStore(Prefs.get(context)), KEY_NAME)

    /**
     * Whether wireless debugging is on, or null when Android will not tell us. From Android 12
     * apps cannot read hidden settings such as this one, so a null (or even a false) here is not
     * proof that it is off. The reliable test is whether adbd advertises its port.
     */
    fun wirelessDebuggingState(context: Context): Boolean? = try {
        Settings.Global.getString(context.contentResolver, SETTING_WIRELESS_DEBUGGING)?.let { it == "1" }
    } catch (e: SecurityException) {
        null
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Turns wireless debugging (and USB debugging, which it depends on) on. Needs WRITE_SECURE_SETTINGS. */
    fun enableWirelessDebugging(context: Context) {
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putInt(resolver, SETTING_WIRELESS_DEBUGGING, 1)
        // Stop Android from switching debugging off after a week without a connection.
        Settings.Global.putLong(resolver, SETTING_ADB_ALLOWED_CONNECTION_TIME, 0L)
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Suspends until a Wi-Fi network is available, or [timeoutMs] passes. */
    suspend fun awaitWifi(context: Context, timeoutMs: Long): Boolean {
        if (isWifiConnected(context)) return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.unregisterNetworkCallback(this)
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
                cm.registerNetworkCallback(request, callback)
                continuation.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
            }
        } ?: false
    }

    /** Opens Developer options scrolled to Wireless debugging. */
    fun wirelessDebuggingSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
}
