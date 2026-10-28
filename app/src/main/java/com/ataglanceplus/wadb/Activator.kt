package com.ataglanceplus.wadb

import android.content.Context
import android.util.Log
import com.ataglanceplus.Commands
import com.ataglanceplus.Prefs
import com.ataglanceplus.adb.AdbClient
import com.ataglanceplus.adb.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Connects to this phone's own adbd over wireless debugging and runs the privileged commands:
 * grant ourselves the secure-settings permission (first time), then make this app the
 * At a Glance provider, or hand it back to Google.
 */
object Activator {

    private const val TAG = "Activator"

    enum class Mode { ACTIVATE, RESET }

    sealed class Outcome {
        data object Success : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    suspend fun run(context: Context, mode: Mode): Outcome = withContext(Dispatchers.IO) {
        if (!Prefs.isPaired(context)) return@withContext fail("Not paired yet")
        Wadb.setBusy(true)
        try {
            if (!Wadb.isWirelessDebuggingEnabled(context)) {
                if (Wadb.canWriteSecureSettings(context)) {
                    Wadb.setStatus("Turning wireless debugging on")
                    Wadb.enableWirelessDebugging(context)
                    delay(1_500)
                } else {
                    return@withContext fail("Wireless debugging is off. Turn it on in Developer options.")
                }
            }
            if (!Wadb.isWifiConnected(context)) {
                return@withContext fail("Wireless debugging needs Wi-Fi. Connect to a network and try again.")
            }

            Wadb.setStatus("Looking for the debugging port")
            val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, DISCOVERY_TIMEOUT_MS)
                ?: return@withContext fail("Could not find the wireless debugging port. Is it switched on?")

            Wadb.setStatus("Connecting on port $port")
            val command = when (mode) {
                Mode.ACTIVATE -> Commands.activateShell(context)
                Mode.RESET -> Commands.resetShell(context)
                    ?: return@withContext fail("No Google provider found on this device")
            }
            try {
                AdbClient("127.0.0.1", port, Wadb.key(context)).use { client ->
                    client.connect()
                    if (!Wadb.canWriteSecureSettings(context)) {
                        Wadb.setStatus("Granting the secure-settings permission")
                        val out = client.shell(Commands.grantSecureSettingsShell(context))
                        Log.i(TAG, "grant: $out")
                    }
                    Wadb.setStatus(if (mode == Mode.ACTIVATE) "Switching At a Glance to this app" else "Restoring Google's At a Glance")
                    val out = client.shell(command)
                    Log.i(TAG, "command output: $out")
                }
            } catch (e: Exception) {
                Log.w(TAG, "adb failed", e)
                return@withContext fail("ADB connection failed: ${e.message ?: e.javaClass.simpleName}. If this persists, pair again.")
            }
            if (Wadb.canWriteSecureSettings(context)) {
                // Now that we hold the permission, make sure debugging stays on for next boot.
                runCatching { Wadb.enableWirelessDebugging(context) }
            }
            Wadb.setStatus(if (mode == Mode.ACTIVATE) "Activated. The launcher and SystemUI restarted." else "Google's At a Glance restored.")
            Outcome.Success
        } finally {
            Wadb.setBusy(false)
        }
    }

    private fun fail(reason: String): Outcome {
        Wadb.setStatus(reason)
        return Outcome.Failure(reason)
    }

    private const val DISCOVERY_TIMEOUT_MS = 30_000L
}
