package com.fourseveneightnine.tv.client.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.StremioAccountImport
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.addons.StremioImportCollection
import com.fourseveneightnine.tv.client.data.addons.StremioLink
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.settings.TVSettingsFileReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var confirmReplace by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !applying) {
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
                    ImportStage.Error("Choose a JSON export of your Stremio add-on collection.")
                }
            }
        }
    }

    fun pickFile() {
        pickerError = null
        try {
            filePicker.launch(arrayOf("application/json", "text/plain"))
        } catch (_: ActivityNotFoundException) {
            pickerError = "This TV has no file picker. Link Stremio with your phone."
        }
    }

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
            stage = ImportStage.Error("Couldn’t connect to Stremio. Check the TV’s connection, request a new code or use a JSON file.")
        }
    }
    val close = {
        if (confirmReplace) confirmReplace = false
        else if (!applying) onClose()
    }
    SettingsModal(
        title = when {
            confirmReplace -> "Replace TV add-ons?"
            stage is ImportStage.Review -> "Review Stremio add-ons"
            stage is ImportStage.Done -> "Import complete"
            else -> "Connect Stremio"
        },
        onClose = close,
        width = if (stage is ImportStage.Review && !confirmReplace) 1080.dp else 860.dp,
    ) {
        LaunchedEffect(stage::class, applying, confirmReplace) {
            repeat(12) {
                withFrameNanos { }
                if (runCatching { firstControl.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        if (confirmReplace) {
            val review = stage as ImportStage.Review
            Text(
                "This TV will use the add-ons from this import. Other TV add-ons and catalogs will be removed or hidden.",
                style = TvType.Body, color = TvColor.TextSecondary,
            )
            Spacer(Modifier.height(TvSpace.L))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                SettingsDialogButton("Keep current add-ons", { confirmReplace = false },
                    Modifier.weight(1f).focusRequester(firstControl))
                SettingsDialogButton("Replace add-ons", {
                    confirmReplace = false
                    applying = true
                    scope.launch {
                        stage = applyImport(graph.pairing, clientGraph, review.collection, replace = true)
                        applying = false
                    }
                }, Modifier.weight(1f), kind = ButtonKind.Destructive)
            }
        } else {
            if (stage == ImportStage.Connecting || stage is ImportStage.Waiting || stage is ImportStage.Error) {
                pickerError?.let {
                    Text(it, style = TvType.Body, color = TvColor.Error)
                    Spacer(Modifier.height(TvSpace.S))
                }
            }
            when (val current = stage) {
                ImportStage.Connecting -> {
                    Text("Requesting a Stremio code…", style = TvType.Body, color = TvColor.TextSecondary)
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        SettingsDialogButton("Use a JSON file", ::pickFile,
                            kind = ButtonKind.Secondary, modifier = Modifier.focusRequester(firstControl))
                        SettingsDialogButton("Cancel", onClose, kind = ButtonKind.Ghost)
                    }
                }
                is ImportStage.Waiting -> {
                    val qr = remember(current.link.url) { PairingQRCode.render(current.link.url).asImageBitmap() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(236.dp).clip(TvShape.Control).background(TvColor.TextPrimary), contentAlignment = Alignment.Center) {
                            Image(qr, contentDescription = "Stremio sign-in code", modifier = Modifier.size(212.dp))
                        }
                        Spacer(Modifier.width(TvSpace.M))
                        Column {
                            PaneTitle("Scan to sign in on your phone")
                            Spacer(Modifier.height(TvSpace.XS))
                            Text("Or open ${current.link.url}", style = TvType.Body, color = TvColor.TextSecondary)
                            Spacer(Modifier.height(TvSpace.XS))
                            Text("The code expires in five minutes. Review your add-ons on this TV before saving.",
                                style = TvType.Meta, color = TvColor.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        SettingsDialogButton("Use a JSON file", ::pickFile,
                            kind = ButtonKind.Secondary, modifier = Modifier.focusRequester(firstControl))
                        SettingsDialogButton("Cancel", onClose, kind = ButtonKind.Ghost)
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
                    val fitsReplace = collection.addons.size <= 256
                    val fitsUpsert = fitsReplace && (existing + imported).size <= 256
                    val ready = if (collection.addons.size == 1) "1 add-on ready" else "${collection.addons.size} add-ons ready"
                    Text(
                        "$ready · ${collection.skipped} unavailable or duplicate",
                        style = TvType.PlateTitle,
                        color = TvColor.TextPrimary,
                    )
                    Spacer(Modifier.height(TvSpace.S))
                    Text(
                        if (fitsUpsert) "Add $added new and update $matching matching add-ons. Imported links may contain keys. This TV stores them encrypted."
                        else if (fitsReplace) "Add & update exceeds this TV's 256-add-on limit. Replace can still import this collection."
                        else "This collection exceeds this TV's 256-add-on limit. No changes will be saved.",
                        style = TvType.Body,
                        color = if (fitsUpsert) TvColor.TextSecondary else TvColor.Error,
                    )
                    Spacer(Modifier.height(TvSpace.S))
                    var listFocused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 216.dp)
                            .tvFocusRing(listFocused, 12.dp).onFocusChanged { listFocused = it.isFocused }
                            .focusable().verticalScroll(rememberScrollState()).padding(12.dp),
                    ) {
                        collection.addons.forEach { addon ->
                            Text(addon.name, style = TvType.Body, color = TvColor.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        SettingsDialogButton(
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
                            modifier = if (fitsUpsert && !applying) Modifier.focusRequester(firstControl) else Modifier,
                        )
                        SettingsDialogButton(
                            "Replace add-ons",
                            { confirmReplace = true },
                            kind = ButtonKind.Secondary,
                            enabled = fitsReplace && !applying,
                        )
                        SettingsDialogButton(if (applying) "Saving…" else "Cancel", close, kind = ButtonKind.Secondary,
                            modifier = if (applying || !fitsUpsert) Modifier.focusRequester(firstControl) else Modifier)
                    }
                }
                is ImportStage.Done -> {
                    Text(current.message, style = TvType.Body, color = TvColor.TextPrimary)
                    Spacer(Modifier.height(TvSpace.M))
                    SettingsDialogButton("Done", onClose, modifier = Modifier.focusRequester(firstControl))
                }
                is ImportStage.Error -> {
                    Text(current.message, style = TvType.Body, color = TvColor.Error)
                    Spacer(Modifier.height(TvSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        SettingsDialogButton("New code", { attempt = (attempt + 1).coerceAtLeast(0) },
                            kind = ButtonKind.Primary, modifier = Modifier.focusRequester(firstControl))
                        SettingsDialogButton("Use a JSON file", ::pickFile)
                        SettingsDialogButton("Cancel", onClose, kind = ButtonKind.Secondary)
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
    val refreshed = try {
        clientGraph.reloadSettingsNow()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    val counts = "${receipt.added} added · ${receipt.updated} updated · ${receipt.removed} removed on this TV."
    ImportStage.Done(if (refreshed) counts else "$counts Open Catalog refresh to retry loading your shelves.")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ImportStage.Error("Couldn’t finish the import. Check the Add-ons list before trying again.")
}
