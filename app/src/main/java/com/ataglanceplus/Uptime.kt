package com.ataglanceplus

import android.app.ApplicationExitInfo
import android.content.Context
import android.provider.Settings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * How well the home screen stayed connected, for the Debug page's weekly summary ("connected
 * 99%, dropped twice (an update and a restart)"). A small file of changes ("time state boot"),
 * apart from the history so its trimming can't cut the week short; 30 days are kept.
 */
object Uptime {

    private const val FILE = "uptime.log"
    private const val KEEP_MS = 30L * 24 * 3_600_000L
    private const val WEEK_MS = 7L * 24 * 3_600_000L

    /** A change: [up] whether the home screen is connected from [at]; [boot] the phone's boot count then. */
    data class Change(val at: Long, val up: Boolean, val boot: Int)

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun bootCount(context: Context) = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    fun read(context: Context): List<Change> = runCatching {
        file(context).readLines().mapNotNull { line ->
            line.trim().split(' ').takeIf { it.size >= 3 }?.let { Change(it[0].toLong(), it[1] == "1", it[2].toInt()) }
        }
    }.getOrDefault(emptyList())

    /** Notes that the home screen connected ([up]) or went, at [at]; a repeat of the last state is skipped. */
    @Synchronized
    fun mark(context: Context, up: Boolean, at: Long = System.currentTimeMillis()) {
        val changes = read(context)
        if (changes.lastOrNull()?.up == up) return
        val kept = (changes + Change(at, up, bootCount(context))).filter { at - it.at < KEEP_MS }
        runCatching { file(context).writeText(kept.joinToString("") { "${it.at} ${if (it.up) 1 else 0} ${it.boot}\n" }) }
    }

    /**
     * The process started: if the record says connected, the app was stopped without saying so
     * (killed, updated, the phone restarting): the drop is when Android stopped it, if it says.
     */
    fun processStarted(context: Context, lastExit: ApplicationExitInfo?) {
        val last = read(context).lastOrNull() ?: return
        if (!last.up) return
        val at = lastExit?.timestamp?.takeIf { it > last.at } ?: System.currentTimeMillis()
        mark(context, false, at)
    }

    /** Why one drop happened, in a few words. */
    private fun cause(drop: Change, next: Change?, exits: List<ApplicationExitInfo>): String {
        val exit = exits.firstOrNull { kotlin.math.abs(it.timestamp - drop.at) < 2 * 60_000L }
        if (exit != null) return when (exit.reason) {
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "an update"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> "a crash"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "a force stop"
            ApplicationExitInfo.REASON_SIGNALED -> "Android closing the app"
            else -> "Android closing the app"
        }
        if (next != null && next.boot != drop.boot && next.boot >= 0) return "a restart"
        if (next != null && next.at - drop.at < 60_000L) return "the launcher reconnecting"
        return "an unknown cause"
    }

    /** "an update and a restart", "2 updates and a restart". */
    private fun causes(list: List<String>): String {
        val counted = list.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { (what, n) ->
            if (n == 1) what else "$n " + when {
                what.startsWith("an ") -> what.removePrefix("an ") + "s"
                what.startsWith("a ") -> what.removePrefix("a ") + "s"
                else -> what
            }
        }
        return when (counted.size) {
            0 -> ""
            1 -> counted[0]
            else -> counted.dropLast(1).joinToString(", ") + " and " + counted.last()
        }
    }

    /**
     * The last 7 days (or since the record began): the share of time the home screen was
     * connected, and each drop with its cause. Null with no record yet.
     */
    fun summary(context: Context, now: Long = System.currentTimeMillis(), exits: List<ApplicationExitInfo> = DebugLog.exitReasons(context, 32)): String? =
        summary(read(context), now, exits)

    internal fun summary(changes: List<Change>, now: Long, exits: List<ApplicationExitInfo>): String? {
        if (changes.isEmpty()) return null
        val from = maxOf(now - WEEK_MS, changes.first().at)
        if (now - from < 60_000L) return null
        // The state at the start of the window, then each change in it.
        var up = changes.lastOrNull { it.at <= from }?.up ?: false
        var t = from
        var connected = 0L
        changes.filter { it.at > from && it.at <= now }.forEach { c ->
            if (up) connected += c.at - t
            up = c.up; t = c.at
        }
        if (up) connected += now - t
        val share = 100.0 * connected / (now - from)
        val drops = changes.withIndex().filter { (i, c) -> !c.up && c.at > from && i > 0 && changes[i - 1].up }
        val dropCauses = drops.map { (i, c) -> cause(c, changes.getOrNull(i + 1), exits) }
        val percent = if (share >= 99.95) "100%" else if (share >= 99) String.format(Locale.ROOT, "%.1f%%", share) else "${share.toInt()}%"
        val span = if (now - from >= WEEK_MS - 60_000L) "Last 7 days"
            else "Since " + Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val dropped = when (drops.size) {
            0 -> "never dropped"
            1 -> "dropped once (${causes(dropCauses)})"
            2 -> "dropped twice (${causes(dropCauses)})"
            else -> "dropped ${drops.size} times (${causes(dropCauses)})"
        }
        return "$span: connected $percent, $dropped"
    }
}
