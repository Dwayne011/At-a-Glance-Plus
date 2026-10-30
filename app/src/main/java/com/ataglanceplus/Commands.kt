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
 * bound to our service until our process dies. So: set the override, then restart the launcher
 * and SystemUI so they open fresh sessions while it is in force.
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
    private const val OVERRIDE_MS = 60_000

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
     * override first, then restart SystemUI (lock screen, always-on display) and the launcher so
     * both open fresh sessions while the override is in force.
     */
    fun activateSteps(context: Context): List<String> = overrideSteps(serviceComponent(context))

    fun resetSteps(context: Context): List<String>? =
        defaultProvider(context)?.let { overrideSteps(it.flattenToString()) }

    private fun overrideSteps(component: String): List<String> = listOf(
        "cmd smartspace set temporary-service ${userId()} $component $OVERRIDE_MS",
        RESTART_SYSTEM_UI,
        "am force-stop $PIXEL_LAUNCHER"
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
            " && $RESTART_SYSTEM_UI && am force-stop $PIXEL_LAUNCHER"
}
