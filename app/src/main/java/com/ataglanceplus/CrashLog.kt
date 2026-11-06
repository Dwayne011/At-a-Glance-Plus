package com.ataglanceplus

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Starts the crash log as soon as the app's process starts, before any screen or service. */
class App : Application() {
    /** Tells Android's backup when a backed-up setting changes (held here so it isn't collected). */
    private val backupListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || com.ataglanceplus.backup.SettingsBackup.included(key)) android.app.backup.BackupManager.dataChanged(packageName)
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(backupListener)
        // Once at each start too, so the first key-value backup (from 0.44.0) is not waiting on a change.
        android.app.backup.BackupManager.dataChanged(packageName)
        // The history notes each start, with why Android stopped the app before (off the main thread).
        Thread { DebugLog.processStarted(this) }.start()
    }
}

/**
 * Keeps the last few errors on the phone, so a crash can be read and copied from the App tab:
 * crashes that would close the app, and errors the app caught and survived (a card that failed
 * to draw).
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE = "crash_log.txt"
    private const val MAX_CHARS = 20_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Crash on thread ${thread.name}", error) }
            runCatching { DebugLog.add(app, DebugLog.Kind.ERROR, "Crash on thread ${thread.name}: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** An error the app caught and carried on from. */
    fun record(context: Context, where: String, error: Throwable) {
        // A job stopped on purpose (the sports loop restarted as the screen came on, say) is
        // not an error.
        if (error is kotlinx.coroutines.CancellationException) return
        Log.w(TAG, where, error)
        runCatching { write(context.applicationContext, where, error) }
        DebugLog.add(context, DebugLog.Kind.ERROR, "$where: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}")
    }

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun write(context: Context, where: String, error: Throwable) {
        val entry = "=== ${DateFormat.getDateTimeInstance().format(Date())} · app ${BuildConfigInfo.version(context)}\n" +
            "$where\n${Log.getStackTraceString(error)}\n"
        // Newest first, trimmed so the file stays small.
        val text = (entry + read(context)).take(MAX_CHARS)
        file(context).writeText(text)
    }
}

/** The installed version, for the crash log. */
private object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
