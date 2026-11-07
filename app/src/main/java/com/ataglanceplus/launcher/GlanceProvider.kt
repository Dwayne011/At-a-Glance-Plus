package com.ataglanceplus.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Read-only provider that exposes the home-screen cards to launchers holding
 * `com.ataglanceplus.permission.READ_GLANCE`. Contract: `content://com.ataglanceplus.glance/cards`.
 *
 * Columns: id, score, feature (the Smartspace feature type number), header_title,
 * header_subtitle, header_icon (PNG), base_title, base_subtitle, base_icon (PNG), intent
 * (an intent URI that fires the card's own pending intent through [TapReceiver]) and
 * content_description.
 */
class GlanceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val context = context ?: return MatrixCursor(COLUMNS)
        val cursor = MatrixCursor(COLUMNS)
        for (target in LauncherCards.targets) {
            val header = target.headerAction
            val base = target.baseAction
            val tap = Intent(TapReceiver.ACTION_TAP)
                .setPackage(context.packageName)
                .putExtra(TapReceiver.EXTRA_CARD, target.smartspaceTargetId)
            cursor.addRow(
                arrayOf<Any?>(
                    target.smartspaceTargetId,
                    target.score,
                    target.featureType.toString(),
                    header?.title?.toString(),
                    header?.subtitle?.toString(),
                    header?.icon?.toPng(context),
                    base?.title?.toString(),
                    base?.subtitle?.toString(),
                    base?.icon?.toPng(context),
                    tap.toUri(Intent.URI_INTENT_SCHEME),
                    header?.contentDescription?.toString(),
                ),
            )
        }
        cursor.setNotificationUri(context.contentResolver, LauncherCards.CARDS_URI)
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.ataglanceplus.card"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private fun Icon.toPng(context: Context): ByteArray? {
        val drawable = runCatching { loadDrawable(context) }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object {
        private const val ICON_PX = 96
        val COLUMNS = arrayOf(
            "id", "score", "feature", "header_title", "header_subtitle", "header_icon",
            "base_title", "base_subtitle", "base_icon", "intent", "content_description",
        )
    }
}
