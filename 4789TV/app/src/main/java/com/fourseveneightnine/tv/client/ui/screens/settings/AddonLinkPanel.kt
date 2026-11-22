package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.transport.AddonLinkForm
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun AddonLinkPanel(onClose: () -> Unit, onStremioImport: () -> Unit) {
    val context = LocalContext.current
    val client = context.clientGraph
    val form = context.appGraph.pairing.addonLinks
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var attempt by remember { mutableStateOf(0) }
    var invitation by remember { mutableStateOf<AddonLinkForm.Invitation?>(null) }
    var message by remember { mutableStateOf("Creating a code…") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        message = "Creating a code…"
        val host = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
        val session = form.open(host) {
            val services = client.services.value
            AddonLinkForm.Inventory(services?.registry?.addons?.value.orEmpty(),
                services?.document?.let { it.tvImportedSources.isNotEmpty() || it.tvReplaceSources } == true)
        }
        if (session == null) {
            message = "Connect this TV and your phone to the same home network, then select New code."
            return@LaunchedEffect
        }
        invitation = session
        try {
            if (withTimeoutOrNull(AddonLinkForm.LIFETIME) { session.connected.first { it } } == true) {
                awaitCancellation()
            }
            message = "This code expired. Select New code to try again."
        } finally {
            form.close(session)
            invitation = null
        }
    }

    val session = invitation
    val connected = session?.connected?.collectAsState()?.value == true
    val pending = session?.pending?.collectAsState()?.value
    val receipt = session?.message?.collectAsState()?.value.orEmpty()
    SettingsModal("Manage add-ons from your phone", { if (!saving) onClose() }, width = 920.dp) {
        LaunchedEffect(session, pending) { first.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.M)) {
            if (session != null && !connected) {
                val qr = remember(session) { PairingQRCode.render(session.address).asImageBitmap() }
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(qr, "Scan to manage TV add-ons", Modifier.size(236.dp)
                        .clip(TvShape.Control).background(TvColor.TextPrimary).padding(12.dp))
                    Column {
                        Text("Scan with your phone’s camera. Keep both devices on the same Wi-Fi.",
                            style = TvType.Body, color = TvColor.TextPrimary)
                        PaneNote("Paste a link or manage installed add-ons. Confirm each change on this TV.")
                        PaneNote("If the page stops responding, choose New code and scan again.")
                    }
                }
            } else {
            Text(when {
                saving -> "Saving…"
                pending is AddonLinkForm.Change.Add -> "Add the add-on from ${URI(pending.url).host}?"
                pending is AddonLinkForm.Change.Remove -> "Remove ${pending.name} and its catalogs from this TV? You can add it again later."
                pending is AddonLinkForm.Change.Refresh -> "Check ${pending.name} for updated details?"
                pending is AddonLinkForm.Change.Toggle -> if (pending.enabled) "Turn on ${pending.name}?" else "Turn off ${pending.name}?"
                pending is AddonLinkForm.Change.Copy -> "Send the manifest link for ${pending.name} to your phone? The link can contain an access key."
                pending is AddonLinkForm.Change.DeleteImports -> "Delete the Stremio imports and their catalogs from this TV?"
                connected && receipt.isNotBlank() -> receipt
                connected -> "Your phone is connected. Keep this screen open and follow the instructions on your phone."
                else -> message
            }, style = TvType.Body, color = TvColor.TextPrimary)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S, Alignment.End)) {
                if (session != null && pending != null) {
                    SettingsDialogButton("Cancel", { form.finish(session, pending, "Change canceled on the TV.") },
                        enabled = !saving, modifier = if (pending is AddonLinkForm.Change.Add) Modifier else Modifier.focusRequester(first))
                    SettingsDialogButton(when (pending) {
                        is AddonLinkForm.Change.Add -> "Add to TV"
                        is AddonLinkForm.Change.Remove -> "Remove"
                        is AddonLinkForm.Change.Refresh -> "Refresh"
                        is AddonLinkForm.Change.Toggle -> if (pending.enabled) "Turn on" else "Turn off"
                        is AddonLinkForm.Change.Copy -> "Send link"
                        AddonLinkForm.Change.DeleteImports -> "Delete imports"
                    }, {
                        if (!saving && session.pending.value === pending) {
                            saving = true
                            scope.launch {
                                try {
                                    val receipt = when (pending) {
                                        is AddonLinkForm.Change.Add -> client.addDirectAddon(pending.url)
                                        is AddonLinkForm.Change.Remove -> withContext(Dispatchers.IO) {
                                            check(client.services.value?.registry?.remove(pending.url) == true)
                                            "Removed ${pending.name}."
                                        }
                                        is AddonLinkForm.Change.Refresh -> withContext(Dispatchers.IO) {
                                            if (client.services.value?.registry?.refreshAddon(pending.url) == true) {
                                                "Refreshed ${pending.name}."
                                            } else "Could not reach the add-on. Try again."
                                        }
                                        is AddonLinkForm.Change.Toggle -> withContext(Dispatchers.IO) {
                                            val registry = requireNotNull(client.services.value?.registry)
                                            check(registry.setEnabled(pending.url, pending.enabled))
                                            if (pending.enabled) registry.refreshAddon(pending.url)
                                            if (pending.enabled) "${pending.name} is on." else "${pending.name} is off."
                                        }
                                        is AddonLinkForm.Change.Copy -> "Link ready to copy on your phone."
                                        AddonLinkForm.Change.DeleteImports -> {
                                            context.appGraph.pairing.deleteStremioImports()
                                            "Stremio imports deleted."
                                        }
                                    }
                                    if (pending is AddonLinkForm.Change.Add || pending == AddonLinkForm.Change.DeleteImports) {
                                        client.reloadSettingsNow()
                                    }
                                    form.finish(session, pending, receipt, (pending as? AddonLinkForm.Change.Copy)?.url)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    form.finish(session, pending, "Could not complete this change. Check the add-on or try again.")
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }, enabled = !saving, modifier = if (pending is AddonLinkForm.Change.Add) Modifier.focusRequester(first) else Modifier,
                        kind = if (pending is AddonLinkForm.Change.Remove || pending == AddonLinkForm.Change.DeleteImports)
                            ButtonKind.Destructive else ButtonKind.Primary)
                } else {
                    SettingsDialogButton("Import from Stremio", onStremioImport)
                    SettingsDialogButton("New code", { attempt++ },
                        modifier = if (session == null) Modifier.focusRequester(first) else Modifier)
                }
                SettingsDialogButton(if (connected) "End session" else "Close", onClose,
                    modifier = if (session != null && pending == null) Modifier.focusRequester(first) else Modifier,
                    enabled = !saving)
            }
        }
    }
}
