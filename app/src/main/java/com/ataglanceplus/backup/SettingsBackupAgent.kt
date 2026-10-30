package com.ataglanceplus.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * Android's backup to the Google account (Auto Backup). Just before each backup it writes the
 * settings snapshot, which is the only file the backup rules include; after a restore (a reinstall
 * or a new phone) it puts those settings back. See [SettingsBackup] for what is and isn't kept.
 */
class SettingsBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        runCatching { SettingsBackup.writeSnapshot(this) }
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        SettingsBackup.applySnapshot(this)
    }

    // Auto Backup only (fullBackupOnly): the key-value backup is not used.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
