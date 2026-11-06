package com.ataglanceplus.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream

/**
 * Android's backup to the Google account, as key-value backup: the settings (see [SettingsBackup]
 * for what is and isn't kept) as one entry. Key-value backup runs in the app's live process, so
 * unlike Auto Backup it neither waits for the app to leave the foreground (the card service never
 * does) nor stops it, which would drop the cards back to Google's. A restore (a reinstall or a new
 * phone) puts the settings back.
 */
class SettingsBackupAgent : BackupAgent() {

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor) {
        val bytes = SettingsBackup.export(this).toByteArray()
        data.writeEntityHeader(KEY, bytes.size)
        data.writeEntityData(bytes, bytes.size)
        // The state file only has to exist; every backup sends the settings in full (a few KB).
        FileOutputStream(newState.fileDescriptor).use { it.write(bytes.size.toString().toByteArray()) }
    }

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor) {
        while (data.readNextHeader()) {
            if (data.key != KEY) { data.skipEntityData(); continue }
            val bytes = ByteArray(data.dataSize)
            data.readEntityData(bytes, 0, bytes.size)
            runCatching { SettingsBackup.import(this, String(bytes)) }
        }
    }

    // A restore of an older, Auto Backup copy (before 0.44.0) still works.
    override fun onRestoreFinished() {
        SettingsBackup.applySnapshot(this)
    }

    private companion object {
        const val KEY = "settings"
    }
}
