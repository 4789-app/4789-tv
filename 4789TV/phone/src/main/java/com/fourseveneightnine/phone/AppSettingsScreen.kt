package com.fourseveneightnine.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AppSettingsScreen(onBack: () -> Unit, onOpenSources: () -> Unit) {
    val context = LocalContext.current
    val offlineStore = remember(context) { OfflineMediaStore(context) }
    val progressStore = remember(context) { LocalPlaybackProgressStore(context) }
    val settingsStore = remember(context) { PhoneSettingsStore(context) }
    val downloadQueue = remember(context) { OfflineDownloadQueue(context) }
    var offlineSummary by remember { mutableStateOf(offlineStore.summary()) }
    var downloadNetworkRule by remember { mutableStateOf(settingsStore.downloadNetworkRule()) }
    var downloadJobs by remember { mutableStateOf(downloadQueue.jobs()) }
    var confirmingOfflineRemoval by remember { mutableStateOf(false) }
    var confirmingProgressRemoval by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-screen"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } }
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            // Only claim what the code does. The old wording also promised caption preferences,
            // which nothing here reads, and there is no captions feature on Android to apply them
            // to. Animation is claimed now because Motion.kt reads the system animation scale;
            // before it did not, and the fade ran at a fixed length whatever the phone asked for.
            Text(
                "4789 follows Android's text size, display size, language, and animation " +
                    "preferences. Playback links and selected document access are never saved.",
            )
        }
        item {
            Button(
                onClick = onOpenSources,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Sources and TV") }
        }
        item {
            Text(
                PhoneSettingsPolicy.downloadNetworkLine(downloadNetworkRule),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("download-network-line"),
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    val next = PhoneSettingsPolicy.toggled(downloadNetworkRule)
                    downloadNetworkRule = if (settingsStore.setDownloadNetworkRule(next)) {
                        next
                    } else {
                        settingsStore.downloadNetworkRule()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("download-network-toggle"),
            ) { Text(PhoneSettingsPolicy.downloadNetworkAction(downloadNetworkRule)) }
        }
        item {
            Text(
                PhoneSettingsPolicy.queueSummary(downloadJobs.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("download-queue-summary"),
            )
        }
        items(downloadJobs, key = { it.recipe.titleID }) { job ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    job.recipe.label + " — " + OfflineDownloadQueuePolicy.statusLine(job),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        downloadQueue.cancel(job.recipe.titleID)
                        downloadJobs = downloadQueue.jobs()
                        status = "Download stopped."
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Stop this download") }
            }
        }
        if (downloadJobs.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = {
                        downloadQueue.cancelAll()
                        downloadJobs = downloadQueue.jobs()
                        status = "All downloads stopped."
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("cancel-all-downloads"),
                ) { Text("Stop all downloads") }
            }
        }
        item {
            Text(
                "Offline copies stay inside this app and can be removed from each title. " +
                    "Uninstalling 4789 removes them. Stored now: ${offlineSummary.count} copies, " +
                    "${OfflineMediaPolicy.formatBytes(offlineSummary.bytes)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            if (confirmingOfflineRemoval) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // Stop the queue first. A worker that woke up afterwards would fetch
                            // back a copy the viewer has just asked this app to get rid of.
                            downloadQueue.cancelAll()
                            downloadJobs = downloadQueue.jobs()
                            val removed = offlineStore.removeAll()
                            offlineSummary = offlineStore.summary()
                            confirmingOfflineRemoval = false
                            status = if (removed) {
                                "All offline copies removed."
                            } else {
                                "Some offline copies could not be removed."
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("confirm-offline-removal"),
                    ) { Text("Confirm offline removal") }
                    OutlinedButton(
                        onClick = { confirmingOfflineRemoval = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Cancel") }
                }
            } else {
                OutlinedButton(
                    onClick = { confirmingOfflineRemoval = true },
                    enabled = offlineSummary.count > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Remove all offline copies") }
            }
        }
        item {
            if (confirmingProgressRemoval) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val removed = progressStore.clearAll()
                            confirmingProgressRemoval = false
                            status = if (removed) {
                                "Saved playback positions cleared."
                            } else {
                                "Playback positions could not be cleared."
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("confirm-progress-removal"),
                    ) { Text("Confirm progress removal") }
                    OutlinedButton(
                        onClick = { confirmingProgressRemoval = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Cancel") }
                }
            } else {
                OutlinedButton(
                    onClick = { confirmingProgressRemoval = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Clear saved playback positions") }
            }
        }
        status?.let { message -> item { Text(message, modifier = Modifier.testTag("settings-status")) } }
        item {
            Text(
                "No ads, analytics, telemetry, or 4789 account.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
