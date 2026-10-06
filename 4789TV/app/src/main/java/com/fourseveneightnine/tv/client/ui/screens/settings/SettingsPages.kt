@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as keyOf
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.player.ReceiverEnginePolicy
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.player.upscale.UpscalePolicy
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.transport.ReceiverPorts
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsPane(page: SettingsPage, actions: PaneActions) {
    when (page) {
        SettingsPage.Pair -> PairPage(actions)
        SettingsPage.Profiles -> ProfilesPage(actions)
        SettingsPage.Addons -> AddonsPage(actions)
        SettingsPage.Accounts -> AccountsPage(actions)
        SettingsPage.Playback -> PlaybackPage(actions)
        SettingsPage.Look -> LookPage(actions)
        SettingsPage.Jobs -> JobsPage(actions)
        SettingsPage.About -> AboutPage(actions)
    }
}

// ---------------------------------------------------------------------------- Pair & Sync

/** Spec §14.4. Ported from the old `TVSettingsSurface`; the coordinator calls are unchanged. */
@Composable
private fun PairPage(actions: PaneActions) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val scope = rememberCoroutineScope()
    val state by graph.pairing.state.collectAsState()

    fun newCode() = scope.launch {
        val host = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
        graph.pairing.beginPairing(host)
    }

    Column {
        PaneTitle("Pair & Sync")
        Spacer(Modifier.height(TvSpace.S))
        when (val current = state) {
            TVSettingsPairingState.Idle -> {
                Text(
                    "No phone paired yet",
                    style = TvType.PlateTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(TvSpace.XS))
                Text(
                    "Scan the code with the 4789 app on your iPhone.",
                    style = TvType.Body,
                    color = TvColor.TextSecondary,
                    maxLines = 2,
                )
            }

            is TVSettingsPairingState.Invitation -> {
                LaunchedEffect(current.pairingID, current.expiresAtUptimeMillis) {
                    val remaining = (current.expiresAtUptimeMillis - android.os.SystemClock.uptimeMillis())
                        .coerceAtLeast(0L)
                    kotlinx.coroutines.delay(remaining)
                    graph.pairing.expireInvitation()
                }
                val qr = remember(current.qrText) {
                    PairingQRCode.render(current.qrText).asImageBitmap()
                }
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(300.dp)
                            .clip(TvShape.CardProminent)
                            .background(TvColor.TextPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(bitmap = qr, contentDescription = "Pairing code", modifier = Modifier.size(240.dp))
                    }
                    Spacer(Modifier.width(TvSpace.M))
                    Column {
                        Text(
                            "Scan with 4789",
                            style = TvType.PlateTitle,
                            color = TvColor.TextPrimary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(TvSpace.XS))
                        Text(
                            "On your iPhone, open Settings, then TV, then Scan. This code lasts five minutes and works once.",
                            style = TvType.Body,
                            color = TvColor.TextSecondary,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(TvSpace.S))
                        Text(
                            "No key or password is inside the code.",
                            style = TvType.Meta,
                            color = TvColor.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }

            is TVSettingsPairingState.Staged -> StagedBlock(
                categories = current.receipt.categories.map { it.name to it.count },
                total = current.receipt.totalFields,
                firstControl = actions.firstControl,
                onApprove = { keepSync -> scope.launch { graph.pairing.approve(keepSync) } },
                onReject = { scope.launch { graph.pairing.reject() } },
            )

            is TVSettingsPairingState.Saving -> Text(
                "Saving securely. Keep 4789 open for a moment.",
                style = TvType.Body,
                color = TvColor.TextSecondary,
                maxLines = 2,
            )

            is TVSettingsPairingState.Saved -> {
                Text(
                    if (current.syncEnabled) "Trusted phone: connected" else "Settings imported",
                    style = TvType.PlateTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(TvSpace.XS))
                Text(current.message, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 2)
                Spacer(Modifier.height(TvSpace.S))
                ReceiptRows(current.receipt.categories.map { it.name to it.count })
            }

            is TVSettingsPairingState.Error -> {
                Text("Pairing needs attention", style = TvType.PlateTitle, color = TvColor.Error, maxLines = 1)
                Spacer(Modifier.height(TvSpace.XS))
                Text(current.message, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 3)
            }
        }

        if (state !is TVSettingsPairingState.Staged) {
            Spacer(Modifier.height(TvSpace.M))
            Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
                SettingRow(
                    label = "Show a new code",
                    value = "",
                    onClick = { newCode() },
                    modifier = Modifier.focusRequester(actions.firstControl),
                )
                SettingRow("Import from a file", "", onClick = actions.onImportFile)
                if (state is TVSettingsPairingState.Saved && (state as TVSettingsPairingState.Saved).syncEnabled) {
                    SettingRow(
                        label = "Disconnect this phone",
                        value = "",
                        onClick = { scope.launch { graph.pairing.disconnectSync() } },
                        valueColor = TvColor.Error,
                    )
                }
                SettingRow(
                    label = "Clear setup",
                    value = "",
                    onClick = actions.onConfirmClear,
                    note = "This removes your add-ons and keys from this TV. Your iPhone keeps everything.",
                )
            }
        }
    }
}

@Composable
private fun StagedBlock(
    categories: List<Pair<String, Int>>,
    total: Int,
    firstControl: FocusRequester,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit,
) {
    var keepSync by remember { mutableStateOf(true) }
    Column {
        Text("Review before saving", style = TvType.PlateTitle, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(TvSpace.XS))
        Text(
            "The TV read the transfer in memory. Only counts are shown; no value is ever drawn.",
            style = TvType.Body,
            color = TvColor.TextSecondary,
            maxLines = 2,
        )
        Spacer(Modifier.height(TvSpace.S))
        ReceiptRows(categories)
        Spacer(Modifier.height(TvSpace.M))
        SettingRow(
            label = "Keep this phone trusted",
            value = if (keepSync) "On" else "Off",
            onClick = { keepSync = !keepSync },
            note = "On, this phone can refresh settings while 4789 is open.",
        )
        Spacer(Modifier.height(TvSpace.S))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            TvButton(
                "Save $total settings",
                { onApprove(keepSync) },
                kind = ButtonKind.Primary,
                modifier = Modifier.focusRequester(firstControl),
            )
            TvButton("Reject", onReject, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun ReceiptRows(categories: List<Pair<String, Int>>) {
    Column {
        categories.forEach { (name, count) ->
            key(name) { ReadoutRow(name, "$count", height = 48.dp) }
        }
    }
}

// ---------------------------------------------------------------------------- Add-ons

/** Spec §14.5. Add-on choices are receiver-local and survive later phone syncs. */
@Composable
private fun AddonsPage(actions: PaneActions) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val noAddons = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<Addon>()) }
    val services by client.services.collectAsState()
    val registry = services?.registry
    val addons by (registry?.addons ?: noAddons).collectAsState()
    val scope = rememberCoroutineScope()

    val catalogsByAddon = remember(addons) {
        addons.associate { addon ->
            addon.key to addon.manifest?.catalogs.orEmpty().map { it.uid(addon.manifestURL) }
        }
    }

    fun move(addon: Addon, delta: Int) {
        scope.launch {
            val moved = runCatching {
                withContext(Dispatchers.IO) { registry?.moveAddon(addon.manifestURL, delta) == true }
            }.getOrDefault(false)
            if (moved) actions.nav.toast("Moved ${addon.displayName}.")
            else actions.nav.toast("Could not save the new add-on order.")
        }
    }

    fun addByURL() {
        val target = registry
        if (target == null) {
            actions.nav.toast("Import settings once before adding an add-on here.")
            return
        }
        actions.onKeyboard(
            addByUrlRequest { typed ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { target.install(typed) } }.fold(
                        onSuccess = { actions.nav.toast("Added ${it.displayName}.") },
                        onFailure = { actions.nav.toast("Couldn't add that address. Check the URL and try again.") },
                    )
                }
            },
        )
    }

    Column {
        PaneTitle("Add-ons & catalogs")
        Spacer(Modifier.height(TvSpace.S))
        if (addons.isEmpty()) {
            EmptyState(
                headline = "No add-ons yet",
                line = "Add one by URL, or send them from your iPhone.",
                actionLabel = "Add by URL",
                onAction = ::addByURL,
                actionModifier = Modifier.focusRequester(actions.firstControl),
            )
            Spacer(Modifier.height(TvSpace.S))
            TvButton("Import from Stremio", actions.onStremioImport, kind = ButtonKind.Secondary)
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            addons.forEachIndexed { index, addon ->
                key(addon.key) {
                    AddonRow(
                        addon = addon,
                        catalogs = catalogsByAddon[addon.key].orEmpty().size,
                        modifier = if (index == 0) Modifier.focusRequester(actions.firstControl) else Modifier,
                        onOpen = {
                            val protected = addon.key == AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST)
                            val options = buildList {
                                if (index > 1) add(MOVE_UP)
                                if (index < addons.lastIndex) add(MOVE_DOWN)
                                if (!protected) {
                                    add(if (addon.enabled) TURN_OFF else TURN_ON)
                                    add(REMOVE)
                                }
                            }
                            if (options.isEmpty()) {
                                actions.nav.toast("Cinemeta stays on so title details keep working.")
                            } else {
                                actions.onChoice(
                                    PanelRequest(
                                        header = addon.displayName,
                                        options = options,
                                        selected = null,
                                        onPick = { choice ->
                                            when (choice) {
                                                MOVE_UP -> move(addon, -1)
                                                MOVE_DOWN -> move(addon, 1)
                                                TURN_ON, TURN_OFF -> {
                                                    val enabled = choice == TURN_ON
                                                    scope.launch {
                                                        val changed = runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                registry?.setEnabled(addon.manifestURL, enabled) == true
                                                            }
                                                        }.getOrDefault(false)
                                                        if (changed) {
                                                            actions.nav.toast(
                                                                "${addon.displayName} is ${if (enabled) "on" else "off"}.",
                                                            )
                                                            if (enabled) scope.launch { registry?.refresh() }
                                                        } else actions.nav.toast("Could not save that add-on change.")
                                                    }
                                                }
                                                REMOVE -> actions.onConfirm(
                                                    ConfirmRequest(
                                                        title = "Remove ${addon.displayName}?",
                                                        body = "This removes the add-on from this receiver only. You can add it again later.",
                                                        destructiveLabel = "Remove",
                                                        onConfirm = {
                                                            scope.launch {
                                                                val removed = runCatching {
                                                                    withContext(Dispatchers.IO) {
                                                                        registry?.remove(addon.manifestURL) == true
                                                                    }
                                                                }.getOrDefault(false)
                                                                if (removed) actions.nav.toast("Removed ${addon.displayName}.")
                                                                else actions.nav.toast("Could not remove that add-on.")
                                                            }
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(TvSpace.M))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            TvButton(
                "Add by URL",
                ::addByURL,
                kind = ButtonKind.Secondary,
            )
            TvButton(
                "Import from Stremio",
                actions.onStremioImport,
                kind = ButtonKind.Secondary,
            )
        }
        Spacer(Modifier.height(TvSpace.S))
        PaneNote("Changes apply only to this receiver. Your phone's add-on list stays unchanged.")
    }
}

private const val MOVE_UP = "Move up"
private const val MOVE_DOWN = "Move down"
private const val TURN_ON = "Turn on"
private const val TURN_OFF = "Turn off"
private const val REMOVE = "Remove"

private fun addByUrlRequest(
    onAdd: (String) -> Unit,
) = KeyboardRequest(
    header = "Add an add-on by URL",
    hint = "Type the manifest address. Space types a dot.",
    onDone = onAdd,
)

@Composable
private fun AddonRow(addon: Addon, catalogs: Int, modifier: Modifier, onOpen: () -> Unit) {
    val dot = when {
        !addon.enabled -> TvColor.TextMuted
        !addon.health.healthy -> TvColor.Error
        addon.health.failStreak > 0 -> TvColor.Warning
        else -> TvColor.Cached
    }
    val host = remember(addon.manifestURL) {
        addon.manifestURL.substringAfter("//").substringBefore("/")
    }
    Column {
        com.fourseveneightnine.tv.client.ui.components.TvFocusable(
            onClick = onOpen,
            modifier = modifier,
            cornerRadius = 10.dp,
            focusScale = 1f,
        ) { focused ->
            Row(
                modifier = Modifier
                    .width(PaneWidth)
                    .heightIn(min = 76.dp)
                    .clip(TvShape.Control)
                    .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                    .padding(horizontal = TvSpace.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(dot)
                Spacer(Modifier.width(TvSpace.S))
                Column(Modifier.weight(1f)) {
                    Text(
                        addon.displayName,
                        style = TvType.ControlLabel,
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(host, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
                }
                Text(
                    if (addon.enabled) "On" else "Off",
                    style = TvType.Meta,
                    color = if (addon.enabled) TvColor.TextPrimary else TvColor.TextMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(TvSpace.M))
                Text(
                    if (catalogs == 1) "1 catalog" else "$catalogs catalogs",
                    style = TvType.data(20),
                    color = TvColor.TextMuted,
                    maxLines = 1,
                )
            }
        }
        if (!addon.health.healthy) {
            val last = addon.health.lastError?.takeIf { it.isNotBlank() } ?: "no answer"
            PaneNote("Failed ${addon.health.failStreak} times. Last error: $last.")
        }
    }
}

// ---------------------------------------------------------------------------- Accounts & keys

/** Spec §14.6. Presence only. No value is ever drawn, in any state. */
@Composable
private fun AccountsPage(actions: PaneActions) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val services by client.services.collectAsState()
    val document = services?.document

    Column {
        PaneTitle("Accounts & keys")
        Spacer(Modifier.height(TvSpace.S))
        ReadoutRow("Real-Debrid key", presence(document?.realDebridAPIKey), presenceColor(document?.realDebridAPIKey))
        ReadoutRow("TorBox key", presence(document?.torboxAPIKey), presenceColor(document?.torboxAPIKey))
        ReadoutRow("TMDB key", presence(document?.tmdbAPIKey), presenceColor(document?.tmdbAPIKey))
        ReadoutRow("MDBList key", presence(document?.mdbListAPIKey), presenceColor(document?.mdbListAPIKey))
        ReadoutRow("Trakt", "not connected", TvColor.TextMuted)
        Spacer(Modifier.height(TvSpace.S))
        document?.redacted()?.let { receipt ->
            ReadoutRow("Settings received", "${receipt.totalFields}", height = 48.dp)
            ReadoutRow("Credentials received", "${receipt.credentials}", height = 48.dp)
        }
        Spacer(Modifier.height(TvSpace.S))
        PaneNote("Keys are set on your iPhone and sent here. This TV never shows them.")
        Spacer(Modifier.height(TvSpace.S))
        TvButton(
            "Open Pair & Sync",
            { actions.nav.openSettings(SettingsPage.Pair.slug) },
            kind = ButtonKind.Secondary,
            modifier = Modifier.focusRequester(actions.firstControl),
        )
    }
}

private fun presence(value: String?): String = if (value.isNullOrBlank()) "not set" else "set"

private fun presenceColor(value: String?) =
    if (value.isNullOrBlank()) TvColor.TextMuted else TvColor.Cached

// ---------------------------------------------------------------------------- Playback

/** Spec §14.7. The picking rules belong to the phone; the engine and the picture are the TV's. */
@Composable
private fun PlaybackPage(actions: PaneActions) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val client = remember(context) { context.clientGraph }
    val prefs = remember(graph) { graph.presentationPreferences }
    val services by client.services.collectAsState()
    val rules = services?.document?.playbackRules ?: PlaybackRules.DEFAULT
    val subtitleStyle by graph.playback.subtitleStyle.collectAsState()

    var audioLanguage by remember {
        mutableStateOf(prefs.getString(SettingsKeys.AUDIO_LANGUAGE, null) ?: SettingsKeys.AUDIO_LANGUAGES.first())
    }
    var engine by remember {
        mutableStateOf(
            when (graph.enginePreferences.getString(ENGINE_OVERRIDE_KEY, null)) {
                ReceiverEnginePolicy.OVERRIDE_EXO -> ENGINE_EXO
                else -> ENGINE_AUTOMATIC
            },
        )
    }
    var upscale by remember {
        mutableStateOf(
            UpscalePolicy.loadMode(
                context.getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE),
            ),
        )
    }
    var frameRate by remember { mutableStateOf(actions.autoFrameRate()) }
    var autoNext by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.AUTO_NEXT, true)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneTitle("Playback")
        Spacer(Modifier.height(TvSpace.S))

        ReadoutRow("Preferred quality", StreamFacts.qualityLabel(rules.minQualityRank), height = 60.dp)
        ReadoutRow(
            "Largest size",
            rules.maxSizeGB?.let { PlaybackRules.sizeText(it) } ?: "Any",
            height = 60.dp,
        )
        ReadoutRow("Prefer cached", if (rules.readyOnly) "On" else "Off", height = 60.dp)
        ReadoutRow("Play best without asking", if (rules.autoPlay) "On" else "Off", height = 60.dp)
        ReadoutRow("Words to avoid", "${rules.normalizedKeywords.size} set", height = 60.dp)
        PaneNote("The picking rules are set on your iPhone. This TV follows them.")
        Spacer(Modifier.height(TvSpace.S))

        SettingRow(
            label = "Audio language",
            value = audioLanguage,
            modifier = Modifier.focusRequester(actions.firstControl),
            onClick = {
                actions.onChoice(
                    PanelRequest("Audio language", SettingsKeys.AUDIO_LANGUAGES, audioLanguage) { picked ->
                        audioLanguage = picked
                        prefs.edit().putString(SettingsKeys.AUDIO_LANGUAGE, picked).apply()
                        context.getSharedPreferences("tv_player_preferences", 0).edit()
                            .remove("audioLanguage").remove("audioSetting").apply()
                    },
                )
            },
        )
        ReadoutRow(
            "Subtitle style",
            "${subtitleStyle.size.toInt()} pt · ${colourName(subtitleStyle.colorHex)}",
            height = 60.dp,
        )
        PaneNote("Subtitle size and colour come from your iPhone.")

        SettingRow(
            label = "Engine",
            value = engine,
            note = "Automatic uses Exo and falls back to mpv once after a codec failure.",
            onClick = {
                val options = buildList {
                    add(ENGINE_AUTOMATIC)
                    add(ENGINE_EXO)
                    if (ReceiverEnginePolicy.MPV_SELECTABLE) add(ENGINE_MPV)
                }
                actions.onChoice(PanelRequest("Engine", options, engine) { picked ->
                    engine = picked
                    val normalized = when (picked) {
                        ENGINE_EXO -> ReceiverEnginePolicy.OVERRIDE_EXO
                        ENGINE_MPV -> ReceiverEnginePolicy.OVERRIDE_MPV
                        else -> ReceiverEnginePolicy.OVERRIDE_AUTO
                    }
                    graph.enginePreferences.edit()
                        .putString(ENGINE_OVERRIDE_KEY, ReceiverEnginePolicy.storedValue(normalized))
                        .apply()
                    actions.nav.toast("The engine changes the next time 4789 starts.")
                })
            },
        )
        SettingRow(
            label = "AI upscaling",
            value = upscaleLabel(upscale),
            onClick = {
                val options = UPSCALE_LABELS.values.toList()
                actions.onChoice(PanelRequest("AI upscaling", options, upscaleLabel(upscale)) { picked ->
                    val mode = UPSCALE_LABELS.entries.first { it.value == picked }.key
                    upscale = mode
                    UpscalePolicy.saveMode(
                        context.getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE),
                        mode,
                    )
                    graph.playback.setUpscale(mode)
                })
            },
        )
        SettingRow(
            label = "Auto frame rate",
            value = if (frameRate) "On" else "Off",
            onClick = {
                frameRate = !frameRate
                actions.onAutoFrameRate(frameRate)
            },
        )
        SettingRow(
            label = "Auto-next episode",
            value = if (autoNext) "On" else "Off",
            onClick = {
                autoNext = !autoNext
                prefs.edit().putBoolean(SettingsKeys.AUTO_NEXT, autoNext).apply()
            },
        )
    }
}

private const val ENGINE_OVERRIDE_KEY = "override"
private const val ENGINE_AUTOMATIC = "Automatic"
private const val ENGINE_EXO = "Exo"
private const val ENGINE_MPV = "mpv"

private val UPSCALE_LABELS = linkedMapOf(
    UpscaleMode.OFF to "Off",
    UpscaleMode.AUTO to "Automatic",
    UpscaleMode.FORCE_1080P to "Force 1080p",
)

private fun upscaleLabel(mode: UpscaleMode): String = UPSCALE_LABELS[mode] ?: "Off"

// ---------------------------------------------------------------------------- Look

/** Visual choices that are applied immediately by the active shell. */
@Composable
private fun LookPage(actions: PaneActions) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val prefs = remember(graph) { graph.presentationPreferences }

    var canvas by remember {
        mutableStateOf(prefs.getString(SettingsKeys.CANVAS, SettingsKeys.CANVAS_SLATE))
    }
    var rows by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.DISCOVER_ROWS, false)) }
    var reduceMotion by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.REDUCE_MOTION, false)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneTitle("Look")
        Spacer(Modifier.height(TvSpace.S))
        SettingRow(
            label = "Canvas",
            value = if (canvas == SettingsKeys.CANVAS_BLACK) "Black" else "Slate",
            modifier = Modifier.focusRequester(actions.firstControl),
            onClick = {
                actions.onChoice(
                    PanelRequest(
                        "Canvas",
                        listOf("Slate", "Black"),
                        if (canvas == SettingsKeys.CANVAS_BLACK) "Black" else "Slate",
                    ) { picked ->
                        canvas = if (picked == "Black") SettingsKeys.CANVAS_BLACK else SettingsKeys.CANVAS_SLATE
                        prefs.edit().putString(SettingsKeys.CANVAS, canvas).apply()
                    },
                )
            },
        )
        SettingRow(
            label = "Discover layout",
            value = if (rows) "Rows" else "Grid",
            onClick = {
                actions.onChoice(
                    PanelRequest("Discover layout", listOf("Grid", "Rows"), if (rows) "Rows" else "Grid") { picked ->
                        rows = picked == "Rows"
                        prefs.edit().putBoolean(SettingsKeys.DISCOVER_ROWS, rows).apply()
                    },
                )
            },
        )
        SettingRow(
            label = "Reduce motion",
            value = if (reduceMotion) "On" else "Off",
            note = "On, every move is a snap. Nothing slides or fades.",
            onClick = {
                reduceMotion = !reduceMotion
                prefs.edit().putBoolean(SettingsKeys.REDUCE_MOTION, reduceMotion).apply()
            },
        )
    }
}

// ---------------------------------------------------------------------------- Jobs

/** Spec §14.9. "Refresh now" runs every source, not the focused one. One button, one meaning. */
@Composable
private fun JobsPage(actions: PaneActions) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val jobs by client.globalLibrary.jobs().collectAsState(initial = emptyList())
    val snapshots by client.snapshots.status().collectAsState()
    // One clock read per composition of the page, not one per row.
    val now = remember(jobs, snapshots) { System.currentTimeMillis() }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaneTitle("Jobs")
            Spacer(Modifier.width(TvSpace.M))
            Box(Modifier.weight(1f))
            TvButton(
                "Refresh now",
                { client.scheduler.manual() },
                kind = ButtonKind.Primary,
                modifier = Modifier.focusRequester(actions.firstControl),
            )
        }
        Spacer(Modifier.height(TvSpace.S))
        Row(Modifier.width(PaneWidth).padding(horizontal = TvSpace.M)) {
            // Five columns. A "Generation" column showed internal snapshot ids and ran its header
            // into "Next run"; it told a viewer nothing.
            JobHeader("Source", 420.dp)
            JobHeader("Last run", 130.dp)
            JobHeader("Result", 130.dp)
            JobHeader("Items", 110.dp)
            JobHeader("Next run", 160.dp)
        }
        Spacer(Modifier.height(TvSpace.XS))
        if (jobs.isEmpty() && snapshots.sources.isEmpty()) {
            PaneNote("No job has run yet. Press Refresh now to fill the shelves.")
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshots.sources.forEach { source ->
                key(source.source.name) {
                    JobRow(
                        name = sourceLabel(source.source.name),
                        // When the server made this data, not when the TV fetched it. "Last run OK"
                        // alone hid a Tamil MV list that was four weeks old.
                        dataDate = source.generatedAtMillis?.takeIf { it > 0 }?.let { dataAge(it, now) },
                        lastRun = source.lastRunMillis?.takeIf { it > 0 }?.let(::clockTime) ?: "\u2014",
                        result = when (source.state) {
                            SnapshotState.LOADING -> "Running"
                            SnapshotState.FAILED -> "Failed"
                            SnapshotState.READY -> "OK"
                            SnapshotState.IDLE -> "Idle"
                        },
                        items = source.itemCount.takeIf { it > 0 }?.toString() ?: "\u2014",
                        nextRun = JobSchedule.nextRunLabel(source.lastRunMillis, now),
                        running = source.state == SnapshotState.LOADING,
                    )
                }
            }
            jobs.forEach { job ->
                key(job.name) {
                    JobRow(
                        name = job.name,
                        lastRun = (job.finishedAtMillis ?: job.startedAtMillis)?.let(::clockTime) ?: "\u2014",
                        result = when (job.state) {
                            JobState.RUNNING -> "Running"
                            JobState.FAILED -> "Failed"
                            JobState.OK -> "OK"
                            JobState.IDLE -> "Idle"
                        },
                        items = "\u2014",
                        nextRun = JobSchedule.nextRunLabel(job.finishedAtMillis, now),
                        running = job.state == JobState.RUNNING,
                    )
                }
            }
        }
    }
}

private fun sourceLabel(raw: String): String = when (raw) {
    "PUBLIC_TMDB" -> "Public catalog"
    "PRIVATE_CATALOG" -> "Private catalog"
    else -> raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun JobHeader(label: String, width: Dp) {
    Box(Modifier.width(width)) {
        Text(label, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
    }
}

/** A running row is not focusable while it runs (spec §14.9). */
@Composable
private fun JobRow(
    name: String,
    dataDate: DataDate? = null,
    lastRun: String,
    result: String,
    items: String,
    nextRun: String,
    running: Boolean,
) {
    val resultColor = when (result) {
        "Running" -> TvColor.Focus
        "Failed" -> TvColor.Error
        "OK" -> TvColor.Cached
        else -> TvColor.TextMuted
    }
    val body: @Composable (Boolean) -> Unit = { focused ->
        Row(
            modifier = Modifier
                .width(PaneWidth)
                .heightIn(min = 72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                .padding(horizontal = TvSpace.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(420.dp)) {
                Text(
                    name,
                    style = TvType.ControlLabel,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dataDate != null) {
                    Text(dataDate.text, style = TvType.Meta, color = if (dataDate.stale) TvColor.Warning else TvColor.TextMuted, maxLines = 1)
                }
            }
            Box(Modifier.width(130.dp)) {
                Text(lastRun, style = TvType.data(20), color = TvColor.TextSecondary, maxLines = 1)
            }
            Box(Modifier.width(130.dp)) {
                Text(result, style = TvType.Meta, color = resultColor, maxLines = 1)
            }
            Box(Modifier.width(110.dp)) {
                Text(items, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
            }
            Box(Modifier.width(160.dp)) {
                Text(nextRun, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
            }
        }
    }
    if (running) {
        Box { body(false) }
    } else {
        com.fourseveneightnine.tv.client.ui.components.TvFocusable(
            onClick = {},
            cornerRadius = 10.dp,
            focusScale = 1f,
            content = body,
        )
    }
}

/**
 * `SimpleDateFormat` is not safe to share between threads, and building one per row rebuilt it on
 * every recomposition of the Jobs page. One per thread is both.
 */
private val clockFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.ENGLISH)
}

private class DataDate(val text: String, val stale: Boolean)

/** "Data from 27 Aug · 28 days old". Older than two days turns amber. */
private fun dataAge(generatedAtMillis: Long, nowMillis: Long): DataDate {
    val days = ((nowMillis - generatedAtMillis).coerceAtLeast(0L) / 86_400_000L).toInt()
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(generatedAtMillis))
    val age = when (days) {
        0 -> "today"
        1 -> "1 day old"
        else -> "$days days old"
    }
    return DataDate("Data from $date · $age", stale = days >= STALE_DATA_DAYS)
}

private const val STALE_DATA_DAYS = 2

private fun clockTime(millis: Long): String =
    checkNotNull(clockFormat.get()).format(Date(millis))

// ---------------------------------------------------------------------------- About

/** Spec §14.10. The box and the panel are named apart, because they differ. */
@Composable
private fun AboutPage(actions: PaneActions) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val scope = rememberCoroutineScope()

    val version = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("unknown")
    }
    val receiverId = remember(graph) { graph.advertiser.receiverUuid }
    val panel = remember(context) {
        val metrics = context.resources.displayMetrics
        val hz = runCatching {
            val display = (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager).defaultDisplay
            "${display.refreshRate.toInt()} Hz"
        }.getOrDefault("")
        "${metrics.widthPixels} × ${metrics.heightPixels}${if (hz.isEmpty()) "" else " · $hz"}"
    }

    Column {
        PaneTitle("About")
        Spacer(Modifier.height(TvSpace.S))
        ReadoutRow("Version", version, height = 48.dp)
        ReadoutRow("Receiver id", shortId(receiverId), height = 48.dp)
        ReadoutRow("Ports", "${ReceiverPorts.HTTP} / ${ReceiverPorts.WEB_SOCKET}", height = 48.dp)
        ReadoutRow("Box", "${Build.MODEL}, Android ${Build.VERSION.RELEASE}", height = 48.dp)
        ReadoutRow("Panel", panel, height = 48.dp)
        ReadoutRow("Engine", graph.controller.engine.name.uppercase(), height = 48.dp)
        PaneNote("Ratings and brand marks: IMDb, Trakt, TMDB, and Letterboxd. This product uses the TMDB API but is not endorsed or certified by TMDB.")
        Spacer(Modifier.height(TvSpace.M))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            TvButton(
                "Licences",
                actions.onLicences,
                kind = ButtonKind.Secondary,
                modifier = Modifier.focusRequester(actions.firstControl),
            )
            TvButton(
                "Export diagnostics",
                {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { exportDiagnostics(context) }
                        actions.nav.toast(path ?: "Couldn't write the log.")
                    }
                },
                kind = ButtonKind.Secondary,
            )
        }
    }
}

private fun shortId(id: String): String =
    if (id.length <= 10) id else "${id.take(4)}…${id.takeLast(4)}"

/**
 * Copies the receiver log where `adb pull` and a file manager can both reach it.
 *
 * The app's own external Downloads folder, not the shared one: this build declares no storage
 * permission and asking for one to write a log would be the wrong trade.
 */
private fun exportDiagnostics(context: android.content.Context): String? {
    ReceiverDiagnostics.flush()
    val source = File(context.filesDir, ReceiverDiagnostics.FILE_NAME)
    if (!source.exists()) return null
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        ?: return null
    return runCatching {
        val target = File(directory, ReceiverDiagnostics.FILE_NAME)
        source.copyTo(target, overwrite = true)
        "Saved to ${target.absolutePath}"
    }.getOrNull()
}

/** "#FFFFFF" means nothing from the couch. Common subtitle colours get a name; others keep hex. */
private fun colourName(hex: String): String = when (hex.trim().uppercase().removePrefix("#").takeLast(6)) {
    "FFFFFF" -> "White"
    "000000" -> "Black"
    "FFFF00", "FFEB3B", "FFD700" -> "Yellow"
    "00FFFF" -> "Cyan"
    "00FF00" -> "Green"
    "FF0000" -> "Red"
    else -> hex
}
