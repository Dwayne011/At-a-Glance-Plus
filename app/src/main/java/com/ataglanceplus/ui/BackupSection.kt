package com.ataglanceplus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ataglanceplus.backup.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Backup and restore: what the phone's own backup keeps, and settings export and import by hand. */
@Composable
internal fun BackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SettingsBackup.export(context).toByteArray()) }
                }
                "Settings exported."
            }.getOrElse { "Couldn't export: ${it.message}" }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }

    SettingsPage("Backup and restore", onBack) {
        Subheader("Google account")
        SettingRow(
            "Backed up with the phone",
            "Your settings are kept in your Google account by the phone's own backup (Settings › System › Backup) and come back on a reinstall or a new phone.",
            icon = Icons.Default.CheckCircle,
        )
        Subheader("By hand")
        SettingRow("Export settings", "Save them to a file", icon = Icons.Default.Share,
            onClick = { export.launch("at-a-glance-plus-settings-${LocalDate.now()}.json") })
        SettingRow("Import settings", "Replace these settings with a file's", icon = Icons.Default.Refresh,
            onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) })
        status?.let { SettingNote(it, MaterialTheme.colorScheme.primary) }
        SettingNote("Not included, on purpose: the wireless debugging pairing, the eToro keys and the Google Wallet sign-in, which are tied to this phone. Set those up again on a new phone.")
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import settings?") },
            text = { Text("This replaces your current settings with the ones in the file. Pairing, eToro keys and Wallet sign-in stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        status = runCatching {
                            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                            "Imported ${SettingsBackup.import(context, text)} settings."
                        }.getOrElse { "Couldn't import: ${it.message}" }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
