@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.ui.RemoteButtonAction
import com.fourseveneightnine.tv.ui.RemoteButtonMappings
import com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.net.URI
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AddonDetailsPanel(manifestURL: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val client = context.clientGraph
    val services by client.services.collectAsState()
    val registry = services?.registry
    val empty = remember { MutableStateFlow(emptyList<Addon>()) }
    val installed by (registry?.addons ?: empty).collectAsState()
    val addonKey = remember(manifestURL) { AddonEndpoint.normalize(manifestURL) ?: manifestURL }
    val addon = installed.firstOrNull { it.key == addonKey }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun perform(progress: String, completed: String, failed: String, work: suspend () -> Boolean) {
        if (busy) return
        busy = true
        message = progress
        scope.launch {
            try {
                message = if (withContext(Dispatchers.IO) { work() }) completed else failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = failed
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        if (busy) return
        if (removing) removing = false else onClose()
    }

    val name = addon?.displayName?.take(80) ?: "Add-on details"
    SettingsModal(if (removing && addon != null) "Remove $name?" else name, ::close, width = 940.dp) {
        LaunchedEffect(removing, addon == null) { first.requestFocus() }
        if (addon == null || registry == null) {
            PaneNote(message ?: "This add-on is no longer installed on the TV.")
            SettingsDialogButton("Done", onClose, Modifier.focusRequester(first))
            return@SettingsModal
        }
        if (removing) {
            PaneNote("This removes the add-on and its catalogs from this TV.")
            if (busy || message != null) PaneNote(message.orEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                SettingsDialogButton("Cancel", ::close, Modifier.focusRequester(first), enabled = !busy)
                SettingsDialogButton("Remove", {
                    perform("Removing…", "Add-on removed.", "Could not remove the add-on.") {
                        registry.remove(manifestURL)
                    }
                }, kind = ButtonKind.Destructive, enabled = !busy)
            }
            return@SettingsModal
        }

        val builtin = addonKey == AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST)
        val document = services?.document
        val install = remember(document, addonKey) {
            val direct = document?.visibleDirectAddons.orEmpty().map { it.source }
            (direct + document?.tvImportedSources.orEmpty()).firstOrNull {
                AddonEndpoint.normalize(it.url) == addonKey
            }
        }
        val manifest = addon.manifest
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AddonLogo(addon, Modifier.size(72.dp))
            PaneNote(manifest?.description?.take(400)?.takeIf(String::isNotBlank)
                ?: "Manifest details appear after the add-on responds.")
        }
        val address = remember(manifestURL) {
            val uri = runCatching { URI(manifestURL) }.getOrNull()
            if (uri?.host == null) "Link saved"
            else buildString {
                append(uri.scheme).append("://").append(uri.host)
                if (uri.port >= 0) append(":").append(uri.port)
                if (uri.rawPath == "/manifest.json" && uri.rawQuery == null) append("/manifest.json")
                else append("/…")
            }
        }
        ReadoutRow("Manifest link", address, height = 54.dp)
        val addedDate = install?.addedAtMillis?.let { DateFormat.getDateInstance().format(Date(it)) }
        ReadoutRow("Added on this TV", when {
            builtin -> "Built in"
            else -> addedDate ?: "Date unavailable"
        }, height = 54.dp)
        if (manifest != null) {
            manifest.version?.takeIf(String::isNotBlank)?.let { ReadoutRow("Version", it.take(60), height = 54.dp) }
            val resources = manifest.resources.joinToString(", ") {
                when (it) {
                    "catalog" -> "Catalogs"
                    "meta" -> "Title details"
                    "stream" -> "Streams"
                    "subtitles" -> "Subtitles"
                    else -> it
                }
            }.take(100)
            if (resources.isNotBlank()) ReadoutRow("Provides", resources, height = 54.dp)
            if (manifest.catalogs.isNotEmpty()) ReadoutRow("Catalogs", manifest.catalogs.size.toString(), height = 54.dp)
        }
        val health = when {
            !addon.enabled -> "Off"
            addon.health.failStreak > 0 -> "Could not reach add-on"
            manifest != null -> "Available"
            else -> "Waiting for manifest"
        }
        ReadoutRow("Status", health, height = 54.dp)
        message?.let {
            Text(it, style = TvType.Meta, color = TvColor.TextPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(TvSpace.S))
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            SettingRow("Refresh add-on", note = "Check the manifest for new details.",
                modifier = Modifier.focusRequester(first), enabled = !busy,
                onClick = {
                    perform("Checking the add-on…", "Add-on refreshed.", "Could not reach the add-on. Try again.") {
                        registry.refreshAddon(manifestURL)
                    }
                })
            SettingRow("Copy manifest link", note = "Copy the full link to this TV’s clipboard.", onClick = {
                message = runCatching {
                    val clip = ClipData.newPlainText("Add-on manifest", manifestURL)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    requireNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
                    "Link copied."
                }.getOrDefault("This TV could not copy the link. Use Manage from your phone.")
            })
            if (!builtin) {
                SettingRow("Use this add-on", checked = addon.enabled, enabled = !busy, onClick = {
                    perform("Saving…", "Add-on preferences saved.", "Could not save this choice.") {
                        val enabled = !addon.enabled
                        registry.setEnabled(manifestURL, enabled).also {
                            if (it && enabled) registry.refreshAddon(manifestURL)
                        }
                    }
                })
                val index = installed.indexOf(addon)
                if (index > 1) SettingRow("Move earlier", enabled = !busy, onClick = {
                    perform("Saving…", "Add-on moved earlier.", "Could not move the add-on.") {
                        registry.moveAddon(manifestURL, -1)
                    }
                })
                if (index < installed.lastIndex) SettingRow("Move later", enabled = !busy, onClick = {
                    perform("Saving…", "Add-on moved later.", "Could not move the add-on.") {
                        registry.moveAddon(manifestURL, 1)
                    }
                })
                SettingRow("Remove add-on", destructive = true, enabled = !busy, onClick = {
                    message = null
                    removing = true
                })
            }
            SettingsDialogButton("Close", ::close, enabled = !busy)
        }
    }
}

/** Capture runs at the Activity boundary, before focus or playback can consume a delivered key. */
@Composable
internal fun RemoteButtonsPanel(onClose: () -> Unit) {
    val shell = LocalShellState.current
    val context = LocalContext.current
    val prefs = context.appGraph.presentationPreferences
    val extraButtons by RemoteButtonAccessibilityService.connected.collectAsState()
    val owner = LocalLifecycleOwner.current
    val first = remember { FocusRequester() }
    val received = remember { mutableStateListOf<Int>() }
    var revision by remember { mutableStateOf(0) }
    val bindings = remember(revision) { RemoteButtonMappings.read(prefs) }
    var playback by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var selectedKey by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }

    fun closeStep() {
        when {
            capturing -> { capturing = false; pending = null }
            selectedKey != null -> selectedKey = null
            else -> onClose()
        }
    }
    BackHandler { closeStep() }
    DisposableEffect(shell, owner) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            if (!capturing || event.keyCode == KeyEvent.KEYCODE_BACK) return@handler false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                status = "Received: ${RemoteButtonMappings.label(event.keyCode)}"
                android.util.Log.i("4789Remote", "received code=${event.keyCode} scan=${event.scanCode} device=${event.deviceId}")
                if (RemoteButtonMappings.canMap(event.keyCode, true) && event.keyCode !in received) received.add(event.keyCode)
                if (!testing && pending == null) pending = event.keyCode
            }
            if (!testing && event.action == KeyEvent.ACTION_UP && pending == event.keyCode) {
                pending = null
                capturing = false
                if (RemoteButtonMappings.canMap(event.keyCode, true)) selectedKey = event.keyCode
                else status = "This button keeps its normal device action."
            }
            true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { capturing = false; pending = null }
        }
        shell.remoteButtonCapture = handler
        owner.lifecycle.addObserver(observer)
        onDispose {
            if (shell.remoteButtonCapture === handler) shell.remoteButtonCapture = null
            owner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(capturing, testing) {
        if (capturing && !testing) {
            kotlinx.coroutines.delay(15_000)
            capturing = false
            pending = null
            status = "No button received. The TV may handle this button."
        }
    }
    LaunchedEffect(capturing, selectedKey, playback, revision) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    SidePanel(header = "Remote buttons", onClose = ::closeStep, width = 840.dp,
        closeOnLeft = !capturing, floatingGlass = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            if (capturing) {
                Text(if (testing) "Press buttons to test. Back ends the test." else "Press the button to assign. Back cancels.",
                    style = TvType.Body, color = TvColor.TextPrimary,
                    modifier = Modifier.focusRequester(first).focusable())
                Text(status, style = TvType.Body, color = TvColor.Selection,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("Home, voice and app shortcuts may leave 4789. Return here to continue.",
                    style = TvType.Meta, color = TvColor.TextMuted)
                if (received.size > 1) Text("Buttons seen: " + received.joinToString { RemoteButtonMappings.label(it) },
                    style = TvType.Meta, color = TvColor.TextSecondary)
            } else if (selectedKey != null) {
                val code = requireNotNull(selectedKey)
                Text(RemoteButtonMappings.label(code), style = TvType.Body, color = TvColor.TextPrimary)
                SidePanelRow("When: ${if (playback) "Playing video" else "Browsing"}", false,
                    { playback = !playback }, Modifier.focusRequester(first), width = 760.dp)
                if (!RemoteButtonMappings.canMap(code, playback)) {
                    Text("Arrows and OK keep normal browsing navigation. Assign them while playing video instead.",
                        style = TvType.Body, color = TvColor.TextSecondary)
                } else RemoteButtonMappings.actions(playback).forEach { action ->
                    val current = bindings.firstOrNull { it.keyCode == code && it.playback == playback }?.action
                        ?: RemoteButtonAction.Default
                    SidePanelRow(action.title, action == current, {
                        RemoteButtonMappings.save(prefs, code, playback, action)
                        revision++
                        selectedKey = null
                        status = "${RemoteButtonMappings.label(code)}: ${action.title}"
                    }, width = 760.dp)
                }
            } else {
                Text("Assign actions for browsing or playback. Test a button to see whether 4789 receives it.",
                    style = TvType.Meta, color = TvColor.TextSecondary)
                val shortcut = RemoteButtonMappings.moviePipKey(bindings, extraButtons)?.let(RemoteButtonMappings::label)
                    ?: "Hold Channel up"
                Text("PiP · Guide   •   Movie and episode shortcut · $shortcut",
                    style = TvType.Meta, color = TvColor.TextSecondary)
                SidePanelRow("Test buttons", false, {
                    status = "Waiting for a button…"; testing = true; capturing = true
                }, Modifier.focusRequester(first), width = 760.dp)
                SidePanelRow("Assign a button", false, {
                    status = "Waiting for a button…"; testing = false; capturing = true
                }, width = 760.dp)
                SidePanelRow("Extra buttons: ${if (extraButtons) "On" else "Off"}", false, {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, width = 760.dp)
                Text("Keep 4789 Remote buttons enabled in Android Accessibility settings. Guide opens and closes the PiP popup while you browse. Profile hides and mutes PiP.",
                    style = TvType.Meta, color = TvColor.TextMuted)
                if (status.isNotEmpty()) Text(status, style = TvType.Meta, color = TvColor.TextSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                (received + bindings.map { it.keyCode }).distinct().forEach { code ->
                    val description = bindings.filter { it.keyCode == code }.joinToString(" · ") {
                        "${if (it.playback) "Video" else "Browse"}: ${it.action.title}"
                    }.ifEmpty { "Default" }
                    SidePanelRow("${RemoteButtonMappings.label(code)} — $description", false,
                        { selectedKey = code }, width = 760.dp)
                }
                if (bindings.isNotEmpty()) SidePanelRow("Restore default buttons", false, {
                    prefs.edit().remove(RemoteButtonMappings.PREFERENCE_KEY).apply()
                    revision++
                    status = "Default buttons restored."
                }, width = 760.dp)
            }
        }
    }
}

/** Remote entry for a profile name or public Letterboxd username. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModalKeyboard(
    header: String,
    hint: String,
    initialText: String,
    usernameInput: Boolean,
    secret: Boolean = false,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var upperCase by remember(usernameInput, secret) { mutableStateOf(!usernameInput && !secret) }
    var validation by remember { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }

    fun type(value: String) {
        if (secret) text = (text + value).take(128)
        else if (!usernameInput || text.length + value.length <= 32) text += value
        validation = null
    }

    fun submit() {
        val value = text.trim()
        validation = when {
            secret && value.isBlank() -> "Enter a key before saving."
            usernameInput && value.isNotEmpty() &&
                !com.fourseveneightnine.tv.catalog.TVLetterboxdClient.validUsername(value) ->
                "Enter a Letterboxd username with letters, numbers or underscores."
            !usernameInput && value.isBlank() -> "Enter a name before saving."
            else -> null
        }
        if (validation == null) onDone(value)
    }

    SettingsModal(header, onCancel, width = 850.dp) {
        LaunchedEffect(Unit) { firstKey.requestFocus() }
        Text(validation ?: hint, style = TvType.Body,
            color = if (validation == null) TvColor.TextSecondary else TvColor.Error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(TvSpace.S))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 65.dp)
                .clip(TvShape.CardProminent).background(TvColor.CanvasBlack)
                .semantics {
                    contentDescription = "Entered text"
                    stateDescription = if (secret) "${text.length} characters entered" else text.ifEmpty { "Empty" }
                }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val display = if (secret) "•".repeat(text.length).ifEmpty { "API key" }
                else text.ifEmpty { if (usernameInput) "Letterboxd username" else "Profile name" }
            Text(display, style = TvType.data(24),
                color = if (text.isEmpty()) TvColor.TextMuted else TvColor.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(TvSpace.S))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TvKeyboard(
                modal = true,
                keyWidth = 112.dp,
                keyHeight = 64.dp,
                gap = 10.dp,
                lowercase = !upperCase,
                usernameInput = usernameInput,
                commitLabel = "Save",
                firstKeyModifier = Modifier.focusRequester(firstKey),
                onKey = { stroke ->
                    when (stroke) {
                        is KeyStroke.Character -> type(if (upperCase) stroke.value else stroke.value.lowercase())
                        KeyStroke.Space -> if (!usernameInput) type(" ")
                        KeyStroke.Backspace -> { text = text.dropLast(1); validation = null }
                        KeyStroke.Commit -> submit()
                    }
                },
            )
        }
        if (!usernameInput) {
            Spacer(Modifier.height(TvSpace.S))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                ProfileNameKey("-") { type("-") }
                ProfileNameKey("'") { type("'") }
                ProfileNameKey(if (upperCase) "abc" else "ABC") { upperCase = !upperCase }
            }
        }
        Spacer(Modifier.height(TvSpace.M))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsDialogButton("Cancel", onCancel)
        }
    }
}

@Composable
private fun ProfileNameKey(label: String, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        accessibleLabel = if (label == "abc" || label == "ABC") "Change letter case, $label" else "Type $label",
        cornerRadius = 8.dp,
        focusScale = 1f,
    ) { focused ->
        Box(
            Modifier
                .width(72.dp)
                .height(58.dp)
                .clip(TvShape.Chip)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TvType.Meta, color = TvColor.TextPrimary, maxLines = 1)
        }
    }
}

/** Spec §14.10: the borrowed files and their licences, read from the app's own assets. */
@Composable
internal fun LicencesPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val body = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text by produceState(initialValue = "Reading licences…", context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(LICENCES_ASSET).bufferedReader().use { it.readText() }
            }.getOrElse { "The licence file is missing from this build." }
        }
    }
    SidePanel(header = "Licences", onClose = onClose, width = TvGeom.SidePanelWideWidth, floatingGlass = true) {
        // A scroll container only ever sees a D-pad key when something inside it holds the ring.
        // One focusable on the column itself is what makes UP and DOWN scroll the text.
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(body)
                .tvFocusRing(focused, 10.dp)
                .semantics {
                    contentDescription = "Licence text. Use up and down to scroll."
                    liveRegion = LiveRegionMode.Polite
                }
                .focusable(interactionSource = interaction)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text, style = TvType.Meta, color = TvColor.TextSecondary)
            Spacer(Modifier.height(TvSpace.M))
        }
    }
    LaunchedEffect(Unit) { runCatching { body.requestFocus() } }
}

internal const val LICENCES_ASSET = "licences.txt"
