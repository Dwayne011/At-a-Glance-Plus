package com.ataglanceplus.complications

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.ataglanceplus.Prefs

/**
 * Unread counts from the Gmail app's own label provider (the one Gmail offers other apps, as
 * documented in GmailContract). Each account is queried separately; the chip for an account adds
 * up the unread conversations in the labels the user picked for it.
 */
object GmailSource {

    private const val TAG = "GmailSource"
    const val PERMISSION = "com.google.android.gm.permission.READ_CONTENT_PROVIDER"
    private const val AUTHORITY = "com.google.android.gm"

    data class Label(val canonicalName: String, val name: String, val unread: Int)

    fun hasPermission(context: Context) =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun labelsUri(account: String): Uri = Uri.parse("content://$AUTHORITY/$account/labels")

    /** Every label for [account] with its unread count, or null if Gmail would not answer. */
    fun labels(context: Context, account: String): List<Label>? {
        if (!hasPermission(context)) return null
        return try {
            context.contentResolver.query(
                labelsUri(account), arrayOf("canonicalName", "name", "numUnreadConversations"), null, null, null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Label(cursor.getString(0) ?: continue, cursor.getString(1) ?: "", cursor.getInt(2)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "labels query failed for an account", e)
            null
        }
    }

    /** The chip value for one account: unread conversations in the chosen labels, summed. */
    fun unread(context: Context, account: String): ChipValues.Value? {
        val chosen = Prefs.gmailLabels(context, account)
        val total = labels(context, account)?.filter { it.canonicalName in chosen }?.sumOf { it.unread } ?: return null
        return if (total > 0) ChipValues.Value(SimpleSources.cap(total)) else null
    }

    /** Friendly names for Gmail's system labels; user labels keep their own names. */
    fun displayName(label: Label): String = when (label.canonicalName) {
        "^i" -> "Inbox (all categories)"
        "^sq_ig_i_personal" -> "Primary"
        "^sq_ig_i_social" -> "Social"
        "^sq_ig_i_promo" -> "Promotions"
        "^sq_ig_i_notification" -> "Updates"
        "^sq_ig_i_group" -> "Forums"
        "^iim" -> "Important"
        "^t" -> "Starred"
        else -> label.name.ifEmpty { label.canonicalName }
    }
}
