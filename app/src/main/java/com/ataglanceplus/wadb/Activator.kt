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

    suspend fun run(context: Context, mode: Mode): Outcome {
        val steps = when (mode) {
            Mode.ACTIVATE -> Commands.activateSteps(context)
            Mode.RESET -> Commands.resetSteps(context) ?: return fail("No Google provider found on this device")
        }
        return execute(
            context, steps,
            working = if (mode == Mode.ACTIVATE) "Switching At a Glance to this app" else "Restoring Google's At a Glance",
            done = if (mode == Mode.ACTIVATE) "Activated. The launcher and SystemUI restarted." else "Google's At a Glance restored.",
        )
    }

    /** Grants chip permissions that Android only lets the shell grant to a sideloaded app. */
    suspend fun grant(context: Context, steps: List<String>): Outcome =
        execute(context, steps, working = "Granting chip permissions", done = "Permissions granted.")

    private suspend fun execute(context: Context, steps: List<String>, working: String, done: String): Outcome = withContext(Dispatchers.IO) {
        if (!Prefs.isPaired(context)) return@withContext fail("Not paired yet")
        Wadb.setBusy(true)
        try {
            // The setting cannot be read reliably, so only use it to decide whether to switch
            // debugging on ourselves. Whether it is really on is decided by port discovery below.
            if (Wadb.wirelessDebuggingState(context) != true && Wadb.canWriteSecureSettings(context)) {
                Wadb.setStatus("Turning wireless debugging on")
                Wadb.enableWirelessDebugging(context)
                delay(1_500)
            }
            if (!Wadb.isWifiConnected(context)) {
                return@withContext fail("Wireless debugging needs Wi-Fi. Connect to a network and try again.")
            }

            Wadb.setStatus("Looking for the debugging port")
            val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, DISCOVERY_TIMEOUT_MS)
                ?: return@withContext fail("Could not find the wireless debugging port. Check that Wireless debugging is on in Developer options.")

            Wadb.setStatus("Connecting on port $port")
            val log = StringBuilder()
            try {
                AdbClient("127.0.0.1", port, Wadb.key(context)).use { client ->
                    client.connect()
                    if (!Wadb.canWriteSecureSettings(context)) {
                        Wadb.setStatus("Granting the secure-settings permission")
                        runStep(client, Commands.grantSecureSettingsShell(context), log)
                    }
                    Wadb.setStatus(working)
                    steps.forEach { runStep(client, it, log) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "adb failed", e)
                return@withContext fail("ADB connection failed: ${e.message ?: e.javaClass.simpleName}. If this persists, pair again.")
            }
            if (Wadb.canWriteSecureSettings(context)) {
                // Now that we hold the permission, make sure debugging stays on for next boot.
                runCatching { Wadb.enableWirelessDebugging(context) }
            }
            Wadb.setStatus(done)
            Outcome.Success
        } finally {
            Wadb.setBusy(false)
        }
    }

    /**
     * Runs one command and appends it to the log. A failing step is logged and the next steps
     * still run, so for example the launcher is restarted even if restarting SystemUI misbehaves.
     */
    private fun runStep(client: AdbClient, command: String, log: StringBuilder) {
        val out = try {
            client.shell("$command 2>&1; echo \"exit=$?\"").trim()
        } catch (e: Exception) {
            Log.w(TAG, "step failed: $command", e)
            "failed: ${e.message ?: e.javaClass.simpleName}"
        }
        Log.i(TAG, "$command -> $out")
        log.append("$ ").append(command).append('\n').append(out).append("\n\n")
        Wadb.setLog(log.toString().trim())
    }

    private fun fail(reason: String): Outcome {
        Wadb.setStatus(reason)
        return Outcome.Failure(reason)
    }

    private const val DISCOVERY_TIMEOUT_MS = 30_000L
}
