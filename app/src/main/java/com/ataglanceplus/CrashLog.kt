package com.ataglanceplus

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Starts the crash log as soon as the app's process starts, before any screen or service. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
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
            previous?.uncaughtException(thread, error)
        }
    }

    /** An error the app caught and carried on from. */
    fun record(context: Context, where: String, error: Throwable) {
        Log.w(TAG, where, error)
        runCatching { write(context.applicationContext, where, error) }
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
