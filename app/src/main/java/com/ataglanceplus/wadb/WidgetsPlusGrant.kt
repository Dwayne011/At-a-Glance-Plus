package com.ataglanceplus.wadb

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Widgets Plus (the user's own app) shows Pixel Buds' left, right and case battery, which Android
 * only prints in `dumpsys bluetooth_manager`; that needs the DUMP permission, which only the shell
 * can grant. At each activation, while this app has wireless debugging open anyway, it grants
 * DUMP to Widgets Plus, once, silently, and only to the real app: the package and the command are
 * fixed here, and the installed app must be signed with Widgets Plus's own key.
 */
object WidgetsPlusGrant {

    private const val PACKAGE = "com.widgetsplus"
    private const val PERMISSION = "android.permission.DUMP"
    /** SHA-256 of Widgets Plus's signing certificate. */
    private const val SIGNER = "A65E887A4CDD8B1BAC2B7E4913018FBA17A1FEB4274E641FFC026FE2286CD591"

    /** The command to add to an activation, or null when there is nothing to grant. */
    fun step(context: Context): String? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return null
        if (pm.checkPermission(PERMISSION, PACKAGE) == PackageManager.PERMISSION_GRANTED) return null
        // Exactly one signer, and it is Widgets Plus's key: an app that merely took the name gets nothing.
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        val genuine = signers.size == 1 &&
            MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02X".format(it) } == SIGNER
        if (!genuine) {
            com.ataglanceplus.DebugLog.add(context, com.ataglanceplus.DebugLog.Kind.ACTIVATION, "Widgets Plus is installed but not signed with its own key: no permission granted")
            return null
        }
        return "pm grant $PACKAGE $PERMISSION"
    }

    fun isGrant(step: String) = step == "pm grant $PACKAGE $PERMISSION"
}
