package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdList
import com.fourseveneightnine.tv.client.OfflineListAudit
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.transport.LetterboxdLinkForm
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun LetterboxdPage(actions: PaneActions) {
    val graph = LocalContext.current.clientGraph
    val importer = graph.letterboxdImporter
    val scope = rememberCoroutineScope()
    val current by importer.status.collectAsState()
    val revision by graph.offlineLetterboxd.revision.collectAsState()
    val library by produceState<List<OfflineListAudit>?>(null, graph, revision) {
        value = graph.offlineLetterboxd.audits()
    }
    val listRows = library.orEmpty()
    var showLists by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
        SettingRow("Import a list with your phone",
            note = "Scan a QR code and send a public Letterboxd list link.",
            prominent = true,
            enabled = !current.running, onClick = actions.onLetterboxdLink)
        Text("Saved on this TV", style = TvType.ControlLabel, color = TvColor.TextPrimary)
        if (library == null) {
            Text("Reading the saved catalog…", style = TvType.Meta, color = TvColor.TextSecondary)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                Stat("Lists", listRows.size, Modifier.weight(1f))
                Stat("Saved entries", listRows.sumOf { it.saved }, Modifier.weight(1f))
                Stat("Verified", listRows.sumOf { it.verified }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                Stat("Unverified", listRows.sumOf { it.unverified }, Modifier.weight(1f))
                Stat("Detected conflicts", listRows.sumOf { it.conflicts }, Modifier.weight(1f))
                Stat("Limited metadata", listRows.sumOf { it.limited }, Modifier.weight(1f))
            }
        }
        if (current.stage.isNotEmpty()) {
            Text(if (current.title.isBlank()) current.stage else "${current.title} · ${current.stage}",
                style = TvType.ControlLabel, color = TvColor.TextPrimary)
            val expected = current.total?.let { " of ${number(it)}" }.orEmpty()
            Text("${number(current.fetched)}$expected read · ${number(current.checked)} checked · ${number(current.saved)} saved",
                style = TvType.Meta, color = TvColor.TextSecondary)
            Text("${number(current.verified)} verified · ${number(current.conflicts)} conflicts · " +
                "${number(current.unverified)} unverified · ${number(current.limited)} limited metadata",
                style = TvType.Meta, color = TvColor.TextSecondary)
            current.log.takeLast(3).forEach { line ->
                Text(line, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 2)
            }
            if (current.running) {
                SettingRow("Cancel import", onClick = importer::cancel)
            } else if (current.owner.isNotEmpty() && current.slug.isNotEmpty()) {
                SettingRow("Import this list again", onClick = {
                    importer.start(TVLetterboxdList(current.owner, current.slug, current.title))
                })
            }
            if (current.log.isNotEmpty()) {
                SettingRow("Import log", note = "${current.log.size} recent events", onClick = {
                    actions.onCatalogReadout("Import log", current.log.reversed().mapIndexed { index, line ->
                        (current.log.size - index).toString() to line
                    })
                })
            }
        }
        if (listRows.isNotEmpty()) {
            SettingRow(if (showLists) "Hide saved lists" else "Browse saved lists",
                note = "${number(listRows.size)} lists in the offline catalog", onClick = { showLists = !showLists })
            if (showLists) {
                LazyColumn(Modifier.fillMaxWidth().height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
                    items(listRows, key = { it.key }) { row ->
                        SettingRow(row.title, note = "${number(row.saved)} titles · ${number(row.verified)} verified", onClick = {
                            scope.launch {
                                val counts = listOf(
                                    "Source" to if (row.imported) "Saved by this TV" else "Included with the APK",
                                    "Titles saved" to number(row.saved),
                                    "Verified" to number(row.verified),
                                    "Unverified" to number(row.unverified),
                                    "Conflicts" to if (row.imported) number(row.conflicts) else "No import report",
                                    "Limited metadata" to number(row.limited),
                                )
                                val issues = graph.offlineLetterboxd.issues(row)
                                actions.onCatalogReadout(row.title, counts + issues)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    Column(modifier.clip(TvShape.Control).background(TvColor.Elevated2).padding(12.dp)) {
        Text(number(value), style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
        Text(label, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 2)
    }
}

@Composable
internal fun LetterboxdImportPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val form = context.appGraph.pairing.letterboxdLinks
    val importer = context.clientGraph.letterboxdImporter
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val first = remember { FocusRequester() }
    var attempt by remember { mutableIntStateOf(0) }
    var invitation by remember { mutableStateOf<LetterboxdLinkForm.Invitation?>(null) }
    var message by remember { mutableStateOf("Creating a code…") }
    LaunchedEffect(attempt) {
        message = "Creating a code…"
        linkError = null
        val host = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
        val session = form.open(host)
        if (session == null) {
            message = "Connect your phone and TV to the same home network, then select New code."
            return@LaunchedEffect
        }
        invitation = session
        try {
            val received = withTimeoutOrNull(LetterboxdLinkForm.LIFETIME) { session.list.filterNotNull().first() }
            if (received != null || session.list.value != null) awaitCancellation()
            message = "This code expired. Select New code to try again."
        } finally {
            form.close(session)
            invitation = null
        }
    }
    val pending = invitation?.list?.collectAsState()?.value
    SettingsModal("Import a Letterboxd list", onClose, width = 780.dp) {
        LaunchedEffect(invitation, pending) { first.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.M)) {
            val session = invitation
            if (session != null && pending == null) {
                val qr = remember(session) { PairingQRCode.render(session.address).asImageBitmap() }
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.M), verticalAlignment = Alignment.CenterVertically) {
                    Image(qr, "Scan to send a public Letterboxd list", Modifier.size(236.dp)
                        .clip(TvShape.Control).background(TvColor.TextPrimary).padding(12.dp))
                    Text("Scan with your phone. Paste the Letterboxd list link and send it to this TV.",
                        style = TvType.Body, color = TvColor.TextSecondary)
                }
            } else {
                val address = pending?.let { TVLetterboxdClient.listLink(it)?.key ?: it }
                Text(linkError ?: when {
                    checking -> "Checking the link…"
                    address != null -> "Link received. Select Import list to save it on this TV."
                    else -> message
                }, style = TvType.Body, color = TvColor.TextPrimary)
                if (linkError == null && address != null) {
                    Text(address, style = TvType.Meta, color = TvColor.TextSecondary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S, Alignment.End)) {
                if (linkError != null) {
                    SettingsDialogButton("New code", { attempt++ }, modifier = Modifier.focusRequester(first))
                } else if (pending != null) {
                    SettingsDialogButton("Import list", {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                try {
                                    val list = context.clientGraph.letterboxd.resolveListLink(pending)
                                    if (list == null) linkError = "This address did not open a public list. Try the full Letterboxd address."
                                    else if (importer.start(list)) onClose()
                                    else linkError = "An import is already running. Check its progress in Letterboxd settings."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    linkError = "Could not open the share link. Try the full Letterboxd address."
                                } finally {
                                    checking = false
                                }
                            }
                        }
                    }, enabled = !checking, modifier = Modifier.focusRequester(first))
                } else if (session == null) {
                    SettingsDialogButton("New code", { attempt++ }, modifier = Modifier.focusRequester(first))
                }
                SettingsDialogButton("Close", onClose,
                    modifier = if (session != null && pending == null) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

private fun number(value: Int): String = NumberFormat.getIntegerInstance().format(value)
