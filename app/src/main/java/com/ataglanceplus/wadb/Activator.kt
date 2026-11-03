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

    /**
     * The system's side, for Advanced › Debug: why the Pixel Launcher and SystemUI last stopped
     * (each restart of either drops this app's connection), which provider At a Glance is bound
     * to now, and how Android treats this app in the background.
     */
    suspend fun systemCheck(context: Context): Outcome {
        val pkg = context.packageName
        return execute(
            context,
            listOf(
                "dumpsys smartspace | head -60",
                "dumpsys activity exit-info ${Commands.PIXEL_LAUNCHER} | head -45",
                "dumpsys activity exit-info ${Commands.SYSTEM_UI} | head -45",
                "am get-standby-bucket $pkg",
                "cmd appops get $pkg RUN_ANY_IN_BACKGROUND",
                "dumpsys deviceidle whitelist | grep -c $pkg",
            ),
            working = "Checking the launcher, SystemUI and At a Glance", done = "System check done; it is below.",
        )
    }

    /** Reads the phone's crash log (this app, the launcher and SystemUI) into the command log. */
    suspend fun readCrashLog(context: Context): Outcome =
        execute(context, listOf("logcat -d -b crash -t 300"), working = "Reading the crash log", done = "Crash log read; it is below.")

    private suspend fun execute(context: Context, steps: List<String>, working: String, done: String): Outcome = withContext(Dispatchers.IO) {
        if (!Prefs.isPaired(context)) return@withContext fail("Not paired yet").also { history(context, "$working: not paired") }
        history(context, "$working…")
        Wadb.setBusy(true)
        try {
            // The setting cannot be read reliably, so only use it to decide whether to switch
            // debugging on ourselves. Whether it is really on is decided by port discovery below.
            if (Wadb.wirelessDebuggingState(context) != true && Wadb.canSwitchDebuggingOn(context)) {
                Wadb.setStatus("Turning wireless debugging on")
                if (Wadb.enableWirelessDebugging(context)) delay(1_500)
            }
            if (!Wadb.isWifiConnected(context)) {
                return@withContext failLogged(context, "Wireless debugging needs Wi-Fi. Connect to a network and try again.")
            }

            Wadb.setStatus("Looking for the debugging port")
            val port = AdbMdns.discover(context, AdbMdns.TLS_CONNECT, DISCOVERY_TIMEOUT_MS)
                ?: return@withContext failLogged(
                    context,
                    if (Prefs.debugSwitchBlocked(context)) "Wireless debugging is off, and this version of Android no longer lets apps turn it on. Turn it on in Developer options, then tap Activate again."
                    else "Could not find the wireless debugging port. Check that Wireless debugging is on in Developer options.",
                )

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
                    steps.forEach { step ->
                        val out = runStep(client, step, log)
                        // The history keeps each step's result, not its output.
                        val exit = Regex("exit=(\\d+)").findAll(out).lastOrNull()?.groupValues?.get(1) ?: "?"
                        history(context, "  ${stepName(step)}: exit $exit")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "adb failed", e)
                history(context, "$working failed: ADB connection: ${e.message ?: e.javaClass.simpleName}")
                return@withContext fail("ADB connection failed: ${e.message ?: e.javaClass.simpleName}. If this persists, pair again.")
            }
            if (Wadb.canSwitchDebuggingOn(context)) {
                // Now that we hold the permission, make sure debugging stays on for next boot.
                Wadb.enableWirelessDebugging(context)
            }
            Wadb.setStatus(done)
            history(context, done)
            Outcome.Success
        } finally {
            Wadb.setBusy(false)
        }
    }

    /**
     * Runs one command and appends it to the log. A failing step is logged and the next steps
     * still run, so for example the launcher is restarted even if restarting SystemUI misbehaves.
     */
    /** A step as the history names it: "Set the override", "Restart SystemUI and wait"… */
    private fun stepName(step: String): String = when {
        step.startsWith("cmd smartspace set") -> "Set the override"
        step.startsWith("am force-stop") -> "Stop the launcher"
        step.startsWith("am start") -> "Open the home screen"
        step.startsWith("for i") -> "Wait for the launcher"
        step.startsWith("old=") || step.startsWith("am crash") -> "Restart SystemUI and wait"
        step.startsWith("pm grant") -> "Grant a permission"
        step.startsWith("cmd notification") -> "Allow notification access"
        step.startsWith("appops") -> "Allow usage access"
        else -> step.split(' ').take(3).joinToString(" ").take(40)
    }

    private fun history(context: Context, text: String) =
        com.ataglanceplus.DebugLog.add(context, com.ataglanceplus.DebugLog.Kind.ACTIVATION, text)

    /** Wireless debugging could not be reached, and so on: the reason also goes in the history. */
    private fun failLogged(context: Context, reason: String): Outcome {
        history(context, "Failed: $reason")
        return fail(reason)
    }

    private fun runStep(client: AdbClient, command: String, log: StringBuilder): String {
        val out = try {
            client.shell("$command 2>&1; echo \"exit=$?\"").trim()
        } catch (e: Exception) {
            Log.w(TAG, "step failed: $command", e)
            "failed: ${e.message ?: e.javaClass.simpleName}"
        }
        Log.i(TAG, "$command -> $out")
        log.append("$ ").append(command).append('\n').append(out).append("\n\n")
        Wadb.setLog(log.toString().trim())
        return out
    }

    private fun fail(reason: String): Outcome {
        Wadb.setStatus(reason)
        return Outcome.Failure(reason)
    }

    private const val DISCOVERY_TIMEOUT_MS = 30_000L
}
