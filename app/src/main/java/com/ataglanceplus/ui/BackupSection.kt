package com.ataglanceplus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ataglanceplus.backup.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Backup to the Google account, and settings export and import by hand. */
@Composable
internal fun BackupSection() {
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

    Section("Backup") {
        Text(
            "Your settings are backed up to your Google account with the phone's own backup (when it is on, in Settings › System › Backup) and come back when you reinstall or set up a new phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Not included, on purpose: the wireless debugging pairing, the eToro keys and the Google Wallet sign-in, which are tied to this phone. Set those up again on a new phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("at-a-glance-plus-settings-${LocalDate.now()}.json") }) { Text("Export settings") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import settings") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
