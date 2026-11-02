package com.ataglanceplus

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.ataglanceplus.service.AtAGlanceService

/**
 * The privileged step. Pointing the system at our service needs shell privileges, which an app
 * cannot hold. The app gets them through wireless debugging (see the wadb package); the same
 * commands can also be run from a computer with adb.
 *
 * How it works: the system keeps one cached binding to the active Smartspace service. A temporary
 * override lasts at most two minutes, but clients that open a session while it is active stay
 * bound to our service until our process dies. So: set the override, restart the launcher and
 * open the home screen so it starts again at once, then restart SystemUI, and both open fresh
 * sessions while it is in force.
 *
 * The two restarts never overlap. On current Android the code that animates switches between
 * apps (WM Shell) runs inside SystemUI. Stopping the launcher and opening the home screen while
 * SystemUI was still starting again could leave the system waiting for an animation that never
 * finished, with every touch ignored until a reboot. So the launcher goes first, while SystemUI
 * is running, and the steps wait for the home screen to settle before SystemUI is restarted, and
 * for SystemUI to be back before activation reports done.
 */
object Commands {

    const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
    const val SYSTEM_UI = "com.android.systemui"

    /**
     * Restarts SystemUI's main process, the one that draws the lock screen. `am crash` with the
     * package name crashes only one of its processes, and once a screenshot has been taken that
     * can be `com.android.systemui:screenshot` instead, leaving the lock screen on Google's
     * provider. So the main process is crashed by its id (`pidof` matches the exact name), with
     * the package name as the fallback.
     */
    private const val RESTART_SYSTEM_UI = "am crash \$(pidof $SYSTEM_UI || echo $SYSTEM_UI)"
    /** The longest temporary override Android allows (two minutes). */
    private const val OVERRIDE_MS = 120_000

    /**
     * Opens the home screen, so the launcher (just stopped) starts again and connects while the
     * override is in force, rather than whenever the user next goes home, which may be too late.
     */
    private const val OPEN_HOME = "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"

    /**
     * Waits for the launcher to be running again (up to 10 s), then 2 s more for the switch to
     * the home screen to finish, so nothing is moving when SystemUI is restarted.
     */
    private const val SETTLE_HOME =
        "for i in \$(seq 1 40); do pidof $PIXEL_LAUNCHER >/dev/null && break; sleep 0.25; done; sleep 2"

    /**
     * Restarts SystemUI (see [RESTART_SYSTEM_UI]) and waits up to 15 s for its new main process,
     * then 2 s more for it to start, so activation is not reported done while it is still down.
     */
    private const val RESTART_SYSTEM_UI_AND_WAIT =
        "old=\$(pidof $SYSTEM_UI); $RESTART_SYSTEM_UI; " +
            "for i in \$(seq 1 60); do new=\$(pidof $SYSTEM_UI); [ -n \"\$new\" ] && [ \"\$new\" != \"\$old\" ] && break; sleep 0.25; done; sleep 2"

    fun serviceComponent(context: Context): String =
        ComponentName(context, AtAGlanceService::class.java).flattenToString()

    /** The current user's id. UserHandle.hashCode() is documented to return the identifier. */
    fun userId(): Int = Process.myUserHandle().hashCode()

    /** Result of looking up Google's own provider (Android System Intelligence). */
    sealed class Provider {
        data class Found(val component: ComponentName) : Provider()
        data class Missing(val reason: String) : Provider()
    }

    /** Google's own provider as configured by the firmware, if its package is installed. */
    fun defaultProvider(context: Context): ComponentName? =
        (lookupDefaultProvider(context) as? Provider.Found)?.component

    fun lookupDefaultProvider(context: Context): Provider {
        val id = context.resources.getIdentifier("config_defaultSmartspaceService", "string", "android")
        if (id == 0) return Provider.Missing("config_defaultSmartspaceService is not defined on this build")
        val flat = context.resources.getString(id)
        if (flat.isBlank()) return Provider.Missing("config_defaultSmartspaceService is empty")
        val component = ComponentName.unflattenFromString(flat)
            ?: return Provider.Missing("config_defaultSmartspaceService is malformed: $flat")
        return try {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
            Provider.Found(component)
        } catch (e: PackageManager.NameNotFoundException) {
            Provider.Missing("configured as ${component.flattenToShortString()} but not visible or not installed")
        }
    }

    fun isPixelLauncherInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PIXEL_LAUNCHER, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The activation as separate steps, so each one's output can be shown. Order matters: the
     * override first, then the launcher (restarted and settled on the home screen), and SystemUI
     * (lock screen, always-on display) last, one at a time, so both open fresh sessions while the
     * override is in force without the restarts overlapping (see the class comment).
     */
    fun activateSteps(context: Context): List<String> = overrideSteps(serviceComponent(context))

    fun resetSteps(context: Context): List<String>? =
        defaultProvider(context)?.let { overrideSteps(it.flattenToString()) }

    private fun overrideSteps(component: String): List<String> = listOf(
        "cmd smartspace set temporary-service ${userId()} $component $OVERRIDE_MS",
        "am force-stop $PIXEL_LAUNCHER",
        OPEN_HOME,
        SETTLE_HOME,
        RESTART_SYSTEM_UI_AND_WAIT,
    )

    /** Shell command that makes this app the At a Glance provider. */
    fun activateShell(context: Context): String = overrideShell(serviceComponent(context))

    /** Shell command that restores Google's provider, or null if none is configured. */
    fun resetShell(context: Context): String? =
        defaultProvider(context)?.let { overrideShell(it.flattenToString()) }

    /** Shell command that lets the app flip wireless debugging on by itself later. */
    fun grantSecureSettingsShell(context: Context): String =
        "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** The same commands wrapped for a computer with adb. */
    /** Shell commands that grant what the chosen chips need and the app cannot grant itself. */
    fun chipGrantSteps(context: Context, notifications: Boolean, usage: Boolean, callLog: Boolean): List<String> {
        val pkg = context.packageName
        return buildList {
            if (notifications) add("cmd notification allow_listener ${com.ataglanceplus.complications.NotificationCounter.component(context).flattenToString()}")
            if (usage) add("appops set $pkg GET_USAGE_STATS allow")
            if (callLog) add("pm grant $pkg android.permission.READ_CALL_LOG")
        }
    }

    // Single quotes, so the computer's shell leaves the $(pidof …) for the phone's.
    fun activateAdb(context: Context): String = "adb shell '${activateShell(context)}'"

    fun resetAdb(context: Context): String? = resetShell(context)?.let { "adb shell '$it'" }

    private fun overrideShell(component: String): String =
        "cmd smartspace set temporary-service ${userId()} $component $OVERRIDE_MS" +
            " && am force-stop $PIXEL_LAUNCHER && $OPEN_HOME; $SETTLE_HOME; $RESTART_SYSTEM_UI_AND_WAIT"
}
