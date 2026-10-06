package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LiveBackupPanel(repo: IptvRepository, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var passphrase by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val encrypted = pendingExport
        pendingExport = null
        passphrase = ""
        if (uri != null && encrypted != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) } ?: error("No output file")
            }.isSuccess }
            onMessage(if (saved) "IPTV backup exported." else "Couldn't save the backup.")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (output.size() <= 4_000_000) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                } ?: error("No backup file")
            }.getOrNull() }
            val password = passphrase.toCharArray()
            val restored = bytes != null && bytes.size <= 4_000_000 && repo.importBackup(bytes, password)
            password.fill('\u0000')
            passphrase = ""
            onMessage(if (restored) "IPTV setup restored on this TV." else "Backup or passphrase was not valid.")
            if (restored) onClose()
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Backup and restore", onClose, width = 760.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Your sources, logins, favorites, and channel order are encrypted with a passphrase. " +
                "Recorded video files stay on this TV.", style = TvType.Body, color = TvColor.TextSecondary)
            LiveFormField("Backup passphrase (8 characters or more)", passphrase,
                modifier = Modifier.focusRequester(first), password = true, onChange = { passphrase = it })
            TvButton("Export encrypted setup", enabled = passphrase.length >= 8, onClick = {
                scope.launch {
                    val password = passphrase.toCharArray()
                    val bytes = runCatching { repo.exportBackup(password) }.getOrNull()
                    password.fill('\u0000')
                    if (bytes == null) onMessage("Couldn't create the backup.")
                    else { pendingExport = bytes; export.launch("4789-iptv-backup.4789") }
                }
            })
            Spacer(Modifier.height(12.dp))
            Text("Import replaces the saved IPTV sources and favorites on this TV.", style = TvType.Meta,
                color = TvColor.TextSecondary)
            TvButton("Replace from encrypted backup", enabled = passphrase.length >= 8,
                onClick = { import.launch(arrayOf("application/octet-stream", "*/*")) })
        }
    }
}
