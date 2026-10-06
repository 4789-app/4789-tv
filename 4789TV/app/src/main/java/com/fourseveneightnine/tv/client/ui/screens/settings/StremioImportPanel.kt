package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.StremioAccountImport
import com.fourseveneightnine.tv.client.data.addons.StremioImportCollection
import com.fourseveneightnine.tv.client.data.addons.StremioLink
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.settings.TVSettingsFileReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

private sealed interface ImportStage {
    data object Connecting : ImportStage
    data class Waiting(val link: StremioLink) : ImportStage
    data class Review(val collection: StremioImportCollection) : ImportStage
    data class Done(val message: String) : ImportStage
    data class Error(val message: String) : ImportStage
}

/** TV-local Stremio import. A QR opens Stremio's own sign-in page on the viewer's phone. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun StremioImportPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val clientGraph = remember(context) { context.clientGraph }
    val services by clientGraph.services.collectAsState()
    val noAddons = remember { MutableStateFlow(emptyList<Addon>()) }
    val currentAddons by (services?.registry?.addons ?: noAddons).collectAsState()
    val importer = remember { StremioAccountImport() }
    val scope = rememberCoroutineScope()
    val firstControl = remember { FocusRequester() }
    var stage by remember { mutableStateOf<ImportStage>(ImportStage.Connecting) }
    var attempt by remember { mutableStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            attempt = -1 // Cancel the outstanding Stremio link poll before reading another source.
            stage = ImportStage.Connecting
            scope.launch {
                stage = try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            TVSettingsFileReader.read(it, maxBytes = 4 * 1024 * 1024)
                        } ?: error("missing_file")
                    }
                    ImportStage.Review(importer.parseCollection(bytes.decodeToString()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ImportStage.Error("That file is not a Stremio add-on collection JSON export.")
                }
            }
        }
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(attempt) {
        if (attempt < 0) return@LaunchedEffect
        stage = ImportStage.Connecting
        try {
            val link = importer.createLink()
            stage = ImportStage.Waiting(link)
            var authKey: String? = null
            for (poll in 0 until 150) {
                authKey = importer.readAuthKey(link)
                if (authKey != null) break
                delay(2_000)
            }
            if (authKey == null) {
                stage = ImportStage.Error("The Stremio code expired. Show a new code and try again.")
            } else {
                val collection = importer.collectAndLogout(authKey)
                stage = ImportStage.Review(collection)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stage = ImportStage.Error("Stremio did not complete the transfer. Check the connection and try again.")
        }
    }
    LaunchedEffect(stage::class) { runCatching { firstControl.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.94f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .semantics { paneTitle = "Import from Stremio" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(850.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(36.dp),
        ) {
            Text("Import from Stremio", style = TvType.PanelHeader, color = TvColor.TextPrimary)
            Spacer(Modifier.height(TvSpace.S))
            Text(
                "Sign in on your phone. This TV receives a temporary Stremio session, reads your add-on links, then closes the session. Configured links may contain add-on keys and are encrypted on this TV. Your 4789 iPhone setup is unchanged.",
                style = TvType.Body,
                color = TvColor.TextSecondary,
            )
            Spacer(Modifier.height(TvSpace.M))
            when (val current = stage) {
                ImportStage.Connecting -> {
                    Text("Requesting a Stremio code…", style = TvType.Body, color = TvColor.TextPrimary)
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        TvButton("Import JSON file", { filePicker.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier.focusRequester(firstControl))
                        TvButton("Cancel", onClose, kind = ButtonKind.Ghost)
                    }
                }
                is ImportStage.Waiting -> {
                    val qr = remember(current.link.url) { PairingQRCode.render(current.link.url).asImageBitmap() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(260.dp).background(TvColor.TextPrimary), contentAlignment = Alignment.Center) {
                            Image(qr, contentDescription = "Stremio sign-in code", modifier = Modifier.size(236.dp))
                        }
                        Spacer(Modifier.width(TvSpace.M))
                        Column {
                            Text("Scan with your phone", style = TvType.PlateTitle, color = TvColor.TextPrimary)
                            Spacer(Modifier.height(TvSpace.XS))
                            Text("Or open ${current.link.url}", style = TvType.Body, color = TvColor.TextSecondary)
                            Spacer(Modifier.height(TvSpace.XS))
                            Text("The code expires in about five minutes.", style = TvType.Meta, color = TvColor.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        TvButton("Import JSON file", { filePicker.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier.focusRequester(firstControl))
                        TvButton("Cancel", onClose, kind = ButtonKind.Ghost)
                    }
                }
                is ImportStage.Review -> {
                    val collection = current.collection
                    val builtIn = AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST)
                    val existing = (currentAddons + services?.registry?.subtitleAddons().orEmpty())
                        .mapNotNull { AddonEndpoint.normalize(it.manifestURL) }
                        .filterNot { it == builtIn }
                        .toSet()
                    val imported = collection.addons.mapNotNull { AddonEndpoint.normalize(it.url) }.toSet()
                    val added = imported.count { it !in existing }
                    val matching = imported.count { it in existing }
                    val removed = existing.count { it !in imported }
                    val fitsReplace = collection.addons.size <= 256
                    val fitsUpsert = fitsReplace && (existing + imported).size <= 256
                    Text(
                        "${collection.addons.size} add-ons ready · ${collection.skipped} unavailable or duplicate",
                        style = TvType.PlateTitle,
                        color = TvColor.TextPrimary,
                    )
                    Spacer(Modifier.height(TvSpace.S))
                    Text(
                        if (fitsUpsert) "Add & update: $added new, $matching matching. Replace: $removed removed from this TV."
                        else if (fitsReplace) "Add & update exceeds this TV's 256-add-on limit. Replace can still import this collection."
                        else "This collection exceeds this TV's 256-add-on limit. No changes will be saved.",
                        style = TvType.Body,
                        color = if (fitsUpsert) TvColor.TextSecondary else TvColor.Error,
                    )
                    Spacer(Modifier.height(TvSpace.S))
                    Column(Modifier.height(230.dp).verticalScroll(rememberScrollState())) {
                        collection.addons.forEach { addon ->
                            Text(addon.name, style = TvType.Body, color = TvColor.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        TvButton(
                            "Add & update",
                            {
                                if (!applying) {
                                    applying = true
                                    scope.launch {
                                        stage = applyImport(graph.pairing, clientGraph, collection, replace = false)
                                        applying = false
                                    }
                                }
                            },
                            kind = ButtonKind.Primary,
                            enabled = fitsUpsert && !applying,
                            modifier = Modifier.focusRequester(firstControl),
                        )
                        TvButton(
                            "Replace add-ons",
                            {
                                if (!applying) {
                                    applying = true
                                    scope.launch {
                                        stage = applyImport(graph.pairing, clientGraph, collection, replace = true)
                                        applying = false
                                    }
                                }
                            },
                            kind = ButtonKind.Secondary,
                            enabled = fitsReplace && !applying,
                        )
                        TvButton("Cancel", onClose, kind = ButtonKind.Ghost)
                    }
                }
                is ImportStage.Done -> {
                    Text(current.message, style = TvType.Body, color = TvColor.TextPrimary)
                    Spacer(Modifier.height(TvSpace.M))
                    TvButton("Done", onClose, modifier = Modifier.focusRequester(firstControl))
                }
                is ImportStage.Error -> {
                    Text(current.message, style = TvType.Body, color = TvColor.Error)
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        TvButton("New code", { attempt = (attempt + 1).coerceAtLeast(0) }, modifier = Modifier.focusRequester(firstControl))
                        TvButton("Cancel", onClose, kind = ButtonKind.Ghost)
                    }
                }
            }
        }
    }
}

private suspend fun applyImport(
    pairing: com.fourseveneightnine.tv.settings.TVSettingsPairingCoordinator,
    clientGraph: com.fourseveneightnine.tv.client.ClientGraph,
    collection: StremioImportCollection,
    replace: Boolean,
): ImportStage = try {
    val receipt = pairing.importStremioAddons(collection.addons, replace)
    val refreshed = runCatching {
        clientGraph.reloadSettingsNow()
    }.isSuccess
    val counts = "${receipt.added} added · ${receipt.updated} updated · ${receipt.removed} removed on this TV."
    ImportStage.Done(if (refreshed) counts else "$counts Catalogs will refresh when the TV reconnects.")
} catch (_: Exception) {
    ImportStage.Error("The add-ons could not be saved. Nothing was changed; check available storage and try again.")
}
