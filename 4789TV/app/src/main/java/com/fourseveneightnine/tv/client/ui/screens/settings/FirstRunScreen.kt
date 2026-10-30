@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.transport.ReceiverPorts
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First run, spec §2. The only screen an unconfigured receiver shows.
 *
 * It gives the phone a code, then reports the first refresh. There is no rail here: a viewer with
 * nothing paired has nowhere else to be, and "Skip for now" is the way out.
 */
@Composable
internal fun FirstRunScreen(nav: ClientNav, onImportFile: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()

    val pairing by graph.pairing.state.collectAsState()
    var stremioImport by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        address = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
    }

    // The QR shows at once on launch. It never waits for a network probe (spec §2.8.1).
    LaunchedEffect(pairing) {
        if (pairing == TVSettingsPairingState.Idle) {
            graph.pairing.beginPairing(withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() })
        }
    }

    val approved = pairing is TVSettingsPairingState.Saved

    BackHandler(enabled = !approved && !stremioImport) {
        nav.toast("Choose Stremio, Live TV, an import file, or iPhone setup.")
    }

    Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = TvGeom.ContentLeft, top = 126.dp, end = 96.dp),
        ) {
            Column(Modifier.width(640.dp)) {
                Text(
                    if (approved) "Setting up" else "Set up your TV",
                    style = TvType.HeroTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    if (approved) {
                        "Your iPhone sent its add-ons and keys. Filling the shelves now."
                    } else {
                        "Import Stremio add-ons, set up Live TV, or sync from 4789 on your iPhone."
                    },
                    style = TvType.Body,
                    color = TvColor.TextSecondary,
                    maxLines = 2,
                )
                if (!approved) {
                    Spacer(Modifier.height(16.dp))
                    Text("To sync from iPhone, open Settings → TV and scan the code.",
                        style = TvType.Meta, color = TvColor.TextMuted, maxLines = 2)
                }
                Spacer(Modifier.height(36.dp))
                PairingActions(
                    approved = approved,
                    staged = pairing as? TVSettingsPairingState.Staged,
                    onNewCode = {
                        scope.launch {
                            val host = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
                            graph.pairing.beginPairing(host)
                        }
                    },
                    onImportFile = onImportFile,
                    onStremio = { stremioImport = true },
                    onApprove = { scope.launch { graph.pairing.approve(true) } },
                    onReject = { scope.launch { graph.pairing.reject() } },
                    onSkip = { nav.openHome() },
                    onLiveTv = {
                        context.getSharedPreferences("iptv-shell", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("open-live-tv", true).apply()
                        nav.openLiveTv()
                    },
                )
                Spacer(Modifier.height(40.dp))
                Text(
                    receiverLine(graph.deviceIdentity.displayName, address),
                    style = TvType.data(20),
                    color = TvColor.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(120.dp))
            Box(Modifier.size(560.dp)) {
                if (approved) {
                    FirstRefreshCard(client = client, onDone = { nav.openHome() })
                } else {
                    QrCard(pairing)
                }
            }
        }
        if (stremioImport) {
            StremioImportPanel(onClose = { stremioImport = false })
        }
    }
}

@Composable
private fun StepList() {
    val steps = listOf(
        "Open Settings on your iPhone",
        "Tap TV, then Scan",
        "Point the camera at this code",
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        steps.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(TvShape.Badge).background(TvColor.Elevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = TvType.data(22), color = TvColor.TextSecondary, maxLines = 1)
                }
                Spacer(Modifier.width(16.dp))
                Text(label, style = TvType.Body, color = TvColor.TextPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PairingActions(
    approved: Boolean,
    staged: TVSettingsPairingState.Staged?,
    onNewCode: () -> Unit,
    onImportFile: () -> Unit,
    onStremio: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSkip: () -> Unit,
    onLiveTv: () -> Unit,
) {
    val entry = remember { FocusRequester() }
    // The first setup method is an ordinary remote action; approval keeps its own entry target.
    LaunchedEffect(staged != null, approved) { runCatching { entry.requestFocus() } }

    if (approved) return

    if (staged != null) {
        Column {
            Text(
                "Review before saving",
                style = TvType.PlateTitle,
                color = TvColor.TextPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(TvSpace.XS))
            staged.receipt.categories.forEach { category ->
                key(category.name) {
                    Text(
                        "${category.name}: ${category.count}",
                        style = TvType.Meta,
                        color = TvColor.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                TvButton(
                    "Save ${staged.receipt.totalFields} settings",
                    onApprove,
                    kind = ButtonKind.Primary,
                    height = TvGeom.ButtonHeightFirstRun,
                    modifier = Modifier.focusRequester(entry),
                )
                TvButton("Reject", onReject, kind = ButtonKind.Ghost, height = TvGeom.ButtonHeightFirstRun)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
        TvButton("Import from Stremio", onStremio, kind = ButtonKind.Primary,
            height = TvGeom.ButtonHeightFirstRun, modifier = Modifier.focusRequester(entry))
        TvButton("Set up Live TV", onLiveTv, kind = ButtonKind.Secondary,
            height = TvGeom.ButtonHeightFirstRun)
        Text("Or sync your saved 4789 settings", style = TvType.Meta, color = TvColor.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            TvButton("Import settings file", onImportFile, kind = ButtonKind.Secondary,
                height = TvGeom.ButtonHeightFirstRun)
            TvButton("New iPhone code", onNewCode, kind = ButtonKind.Ghost,
                height = TvGeom.ButtonHeightFirstRun)
        }
        TvButton("Skip for now", onSkip, kind = ButtonKind.Ghost)
    }
}

@Composable
private fun QrCard(state: TVSettingsPairingState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(560.dp).clip(TvShape.Panel).background(TvColor.TextPrimary),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is TVSettingsPairingState.Invitation -> {
                    val qr = remember(state.qrText) { PairingQRCode.render(state.qrText).asImageBitmap() }
                    Image(bitmap = qr, contentDescription = "Pairing code", modifier = Modifier.size(448.dp))
                }
                is TVSettingsPairingState.Error -> Text(
                    state.message,
                    style = TvType.Body,
                    color = TvColor.CanvasBlack,
                    maxLines = 4,
                    modifier = Modifier.padding(40.dp),
                )
                else -> Text(
                    "Waiting for your iPhone.",
                    style = TvType.Body,
                    color = TvColor.CanvasBlack,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "This code changes every five minutes.",
            style = TvType.Meta,
            color = TvColor.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Spec §2.3. The rows never disappear, and a failed one stays red. */
@Composable
private fun FirstRefreshCard(
    client: com.fourseveneightnine.tv.client.ClientGraph,
    onDone: () -> Unit,
) {
    val noAddons = remember { MutableStateFlow(emptyList<Addon>()) }
    val services by client.services.collectAsState()
    val addons by (services?.registry?.addons ?: noAddons).collectAsState()
    val snapshots by client.snapshots.status().collectAsState()
    val shelves by client.snapshots.shelves().collectAsState()

    val catalogs = when {
        snapshots.sources.isEmpty() -> SourcePhase.Idle
        snapshots.sources.any { it.state == SnapshotState.LOADING } -> SourcePhase.Loading
        snapshots.sources.all { it.state == SnapshotState.FAILED } -> SourcePhase.Failed
        snapshots.sources.any { it.state == SnapshotState.READY } -> SourcePhase.Ready
        else -> SourcePhase.Idle
    }
    val progress = FirstRunSteps.derive(
        settingsSaved = services != null,
        addonsTotal = addons.size,
        addonsResolved = addons.count { it.manifest != null },
        addonsFailed = addons.count { it.manifest == null && !it.health.healthy },
        catalogs = catalogs,
        posterItems = shelves.sumOf { it.items.size },
    )

    // A first run that never settles is still a first run. The button opens after 25 seconds so a
    // silent add-on cannot hold a viewer on this screen.
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(FIRST_RUN_ESCAPE_MILLIS)
        timedOut = true
    }
    val ready = progress.settled || timedOut

    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }

    Column(
        Modifier
            .size(560.dp)
            .clip(TvShape.Panel)
            .background(TvColor.Elevated)
            .border(1.dp, TvColor.Border, TvShape.Panel)
            .padding(40.dp),
    ) {
        progress.steps.forEach { step ->
            key(step.label) {
                Row(
                    modifier = Modifier.height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepGlyph(step.state)
                    Spacer(Modifier.width(20.dp))
                    Text(
                        step.label,
                        style = TvType.Body,
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(TvSpace.M))
        Text(
            progress.note ?: "Your shelves appear as each source answers. You can continue while they load.",
            style = TvType.Meta,
            color = if (progress.note == null) TvColor.TextMuted else TvColor.Error,
            maxLines = 2,
        )
        Spacer(Modifier.height(TvSpace.M))
        TvButton(
            if (ready) "Start watching" else "Continue without waiting",
            onDone,
            kind = ButtonKind.Primary,
            height = TvGeom.ButtonHeightFirstRun,
            modifier = Modifier.focusRequester(start),
        )
    }
}

/** Shape and text identify setup state independently of color. */
@Composable
private fun StepGlyph(state: StepState) {
    val (symbol, color) = when (state) {
        StepState.Pending -> "○" to TvColor.TextMuted
        StepState.Running -> "…" to TvColor.InProgress
        StepState.Done -> "✓" to TvColor.Success
        StepState.Failed -> "!" to TvColor.Error
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Text(symbol, style = TvType.ControlLabel, color = color)
    }
}

private fun receiverLine(name: String, address: String?): String =
    "Receiver: $name · ${address ?: "waiting for the network"} · ports " +
        "${ReceiverPorts.HTTP} / ${ReceiverPorts.WEB_SOCKET}"

private const val FIRST_RUN_ESCAPE_MILLIS = 25_000L
