package com.ataglanceplus

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's history on the phone, for Advanced › Debug: when the launcher and lock screen
 * connected and disconnected, each time the app's process started and why Android stopped it
 * before, activations, Wallet place events, fetch failures and errors. One line an event, newest
 * last, trimmed so the file stays small.
 *
 * Nothing secret goes in: no keys, tokens, account names or coordinates (places are named by
 * their label, positions by accuracy and distance only). The file is not in backups.
 */
object DebugLog {

    enum class Kind(val label: String) {
        PROCESS("App"), CONNECTION("Connection"), ACTIVATION("Activation"), LOCATION("Location"),
        WALLET("Wallet"), FETCH("Fetch"), POWER("Power"), ERROR("Error"),
    }

    private const val TAG = "DebugLog"
    private const val FILE = "debug_history.log"
    private const val KEEP_LINES = 600
    private const val TRIM_AT = 800
    private const val KEY_EXIT_SEEN = "debug_exit_seen"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun time(at: Long): String = stamp.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    @Synchronized
    fun add(context: Context, kind: Kind, message: String) {
        Log.i(TAG, "${kind.name} $message")
        runCatching {
            val file = file(context)
            file.appendText("${time(System.currentTimeMillis())}  ${kind.label.padEnd(10)}  ${message.replace('\n', ' ')}\n")
            // Trimmed now and then rather than on every line.
            if (file.length() > 120_000) {
                val lines = file.readLines()
                if (lines.size > TRIM_AT) file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** The history, newest first. */
    fun read(context: Context): List<String> = runCatching { file(context).readLines().filter { it.isNotBlank() }.asReversed() }.getOrDefault(emptyList())

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        add(context, Kind.PROCESS, "History cleared")
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /**
     * The process has started: note it, with Android's reason for each time it was stopped since
     * the last start this history saw (an update, low memory, a crash, a force stop…).
     */
    fun processStarted(context: Context) {
        val prefs = Prefs.get(context)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        val exits = exitReasons(context, 10).filter { it.timestamp > seen }.sortedBy { it.timestamp }
        exits.forEach { add(context, Kind.PROCESS, "Stopped at ${time(it.timestamp)}: ${describe(it)}") }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_EXIT_SEEN, it) } }
        add(context, Kind.PROCESS, "Started (version ${version(context)})")
    }

    fun exitReasons(context: Context, max: Int): List<ApplicationExitInfo> = runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, max)
    }.getOrDefault(emptyList())

    /** "Low memory (importance: perceptible) · description". */
    fun describe(info: ApplicationExitInfo): String {
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
            ApplicationExitInfo.REASON_CRASH -> "Crashed"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crashed (native)"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A process it depends on died"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Used too many resources"
            ApplicationExitInfo.REASON_EXIT_SELF -> "Exited by itself"
            ApplicationExitInfo.REASON_FREEZER -> "Stopped while frozen"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
            ApplicationExitInfo.REASON_OTHER -> "Stopped by the system"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "App enabled or disabled"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "App updated"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission changed"
            ApplicationExitInfo.REASON_SIGNALED -> "Killed (signal ${info.status})"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "Force-stopped or removed from recents"
            ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped by the user"
            else -> "Unknown (${info.reason})"
        }
        val importance = when (info.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
            else -> info.importance.toString()
        }
        val detail = info.description?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "$reason (was $importance)$detail"
    }

    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
