package com.ataglanceplus

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.ataglanceplus.service.AtAGlanceService

/**
 * The privileged step. Pointing the system at our service needs shell privileges, which an app
 * cannot hold. Milestone 1 does it from a computer with adb; a later milestone does the same over
 * wireless debugging from inside the app so it survives reboots.
 *
 * How it works: the system keeps one cached binding to the active Smartspace service. A temporary
 * override lasts at most two minutes, but clients that open a session while it is active stay
 * bound to our service until our process dies. So: set the override, then restart the launcher
 * and SystemUI so they open fresh sessions while it is in force.
 */
object Commands {

    const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
    const val SYSTEM_UI = "com.android.systemui"
    private const val OVERRIDE_MS = 60_000

    fun serviceComponent(context: Context): String =
        ComponentName(context, AtAGlanceService::class.java).flattenToString()

    /** The current user's id. UserHandle.hashCode() is documented to return the identifier. */
    fun userId(): Int = Process.myUserHandle().hashCode()

    /** Google's own provider (Android System Intelligence) as configured by the firmware. */
    fun defaultProvider(context: Context): ComponentName? {
        val id = context.resources.getIdentifier("config_defaultSmartspaceService", "string", "android")
        if (id == 0) return null
        val flat = context.resources.getString(id)
        if (flat.isBlank()) return null
        val component = ComponentName.unflattenFromString(flat) ?: return null
        return try {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
            component
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isPixelLauncherInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PIXEL_LAUNCHER, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun activate(context: Context): String =
        override(serviceComponent(context))

    fun reset(context: Context): String? =
        defaultProvider(context)?.let { override(it.flattenToString()) }

    private fun override(component: String): String =
        "adb shell \"cmd smartspace set temporary-service ${userId()} $component $OVERRIDE_MS" +
            " && am crash $SYSTEM_UI && am force-stop $PIXEL_LAUNCHER\""
}
