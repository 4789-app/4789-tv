@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key as keyOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.catalog.EinthusanAccount
import com.fourseveneightnine.tv.catalog.EinthusanFailure
import com.fourseveneightnine.tv.catalog.EinthusanLanguage
import com.fourseveneightnine.tv.catalog.EinthusanProvider
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.screens.live.LiveFormField
import com.fourseveneightnine.tv.client.ui.theme.TvColor
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsPane(page: SettingsPage, actions: PaneActions) {
    when (page) {
        SettingsPage.Pair -> PairPage(actions)
        SettingsPage.Addons -> AddonsPage(actions)
        SettingsPage.Accounts -> AccountsPage(actions)
        SettingsPage.LiveTv -> SettingRow(
            "Add a Live TV source",
            note = "Choose a playlist or enter the details from your TV provider.",
            onClick = actions.onSetUpLiveTv,
            modifier = Modifier.focusRequester(actions.firstControl),
            icon = page.icon,
            tint = page.tint,
            prominent = true,
        )
        SettingsPage.Playback -> PlaybackPage(actions)
        SettingsPage.Look -> LookPage(actions)
        SettingsPage.Jobs -> JobsPage(actions)
        SettingsPage.About -> AboutPage(actions)
    }
}

// ---------------------------------------------------------------------------- Pair & Sync

@Composable
private fun PairPage(actions: PaneActions) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val scope = rememberCoroutineScope()
    val state by graph.pairing.state.collectAsState()

    val saved = state as? TVSettingsPairingState.Saved
    val hasSettings = saved != null || graph.receiverHost?.settingsConfigured?.collectAsState()?.value == true
    var profilesExpanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(
            "Sync from iPhone",
            value = if (saved?.syncEnabled == true) "Trusted iPhone" else "",
            note = "Send your add-ons, service keys and preferences together.",
            onClick = actions.onPhoneSync,
            modifier = Modifier.focusRequester(actions.firstControl),
            icon = SettingsPage.Pair.icon,
            prominent = true,
        )
        SettingRow(
            "Profiles",
            value = if (profilesExpanded) "Hide profiles" else "",
            note = "Give each person their own Watchlist and watch progress.",
            icon = R.drawable.ic_rail_collections,
            tint = SettingsPage.Look.tint,
            onClick = { profilesExpanded = !profilesExpanded },
        )
        if (profilesExpanded) {
            Column(Modifier.padding(start = 24.dp)) { ProfilesPage(actions) }
        }
        if (actions.welcome) {
            SettingRow("Browse Home", note = "You can finish setup later.",
                icon = R.drawable.ic_rail_home, tint = SettingsPage.Jobs.tint, onClick = actions.nav::openHome)
        }
        if (hasSettings) {
            Spacer(Modifier.height(8.dp))
            PaneTitle("Manage this TV")
            if (saved?.syncEnabled == true) {
                SettingRow(
                    label = "Disconnect iPhone",
                    note = "Stop iPhone updates and keep the settings already saved here.",
                    onClick = {
                        actions.onConfirm(ConfirmRequest(
                            title = "Disconnect this iPhone?",
                            body = "The iPhone can no longer send settings updates to this TV. Your saved settings stay on the TV.",
                            destructiveLabel = "Disconnect",
                            destructive = false,
                            onConfirm = { scope.launch { graph.pairing.disconnectSync() } },
                        ))
                    },
                )
            }
            SettingRow(
                label = "Clear TV setup",
                destructive = true,
                onClick = {
                    actions.onConfirm(ConfirmRequest(
                        title = "Clear this TV’s setup?",
                        body = "This removes synced settings, add-ons, service keys and private catalog data from this TV. It also disconnects your iPhone. Your iPhone keeps its settings.",
                        destructiveLabel = "Clear setup",
                        onConfirm = { scope.launch { graph.pairing.clearReceiverSetup() } },
                    ))
                },
            )
        }
    }
}

@Composable
internal fun PhoneSyncPanel(forKeys: Boolean, onClose: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val scope = rememberCoroutineScope()
    val state by graph.pairing.state.collectAsState()
    val first = remember { FocusRequester() }
    var attempt by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }

    fun close() {
        if (closing || graph.pairing.state.value is TVSettingsPairingState.Saving) return
        closing = true
        scope.launch {
            when (graph.pairing.state.value) {
                TVSettingsPairingState.Idle, is TVSettingsPairingState.Saved -> Unit
                else -> graph.pairing.reject()
            }
            onClose()
        }
    }

    LaunchedEffect(attempt, closing) {
        if (closing) return@LaunchedEffect
        val host = withContext(Dispatchers.IO) { LocalNetworkAddress.currentIPv4() }
        graph.pairing.beginPairing(host)
        creating = false
    }

    SettingsModal(
        title = if (forKeys) "Send keys from iPhone" else "Sync from iPhone",
        onClose = ::close,
        width = 1060.dp,
    ) {
        val current = state
        if (creating) {
            PaneNote("Creating a code…")
        } else when (current) {
            TVSettingsPairingState.Idle -> Unit
            is TVSettingsPairingState.Invitation -> {
                LaunchedEffect(current.pairingID) {
                    val remaining = (current.expiresAtUptimeMillis - android.os.SystemClock.uptimeMillis())
                        .coerceAtLeast(0L)
                    kotlinx.coroutines.delay(remaining)
                    graph.pairing.expireInvitation()
                }
                val qr = remember(current.qrText) { PairingQRCode.render(current.qrText).asImageBitmap() }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(qr, "Pairing code for the 4789 iPhone app", Modifier.size(278.dp).clip(TvShape.Control)
                        .background(TvColor.TextPrimary).padding(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PaneTitle("Scan with 4789 on your iPhone")
                        Text(
                            if (forKeys) "1. In 4789, add your key in Settings → Accounts & keys."
                            else "1. Open 4789.", style = TvType.Body, color = TvColor.TextPrimary,
                        )
                        Text(
                            "2. Go to Settings → TV & devices → Send my setup to the TV. Tap Scan the code on the TV.",
                            style = TvType.Body, color = TvColor.TextPrimary,
                        )
                        Text("3. On this TV, review the transfer and choose Save settings.",
                            style = TvType.Body, color = TvColor.TextPrimary)
                    }
                }
                PaneNote("Use the same Wi-Fi on both devices. The code expires in five minutes.")
            }
            is TVSettingsPairingState.Staged -> StagedBlock(
                categories = current.receipt.categories.map { it.name to it.count },
                total = current.receipt.totalFields,
                firstControl = first,
                onApprove = { keepSync -> scope.launch { graph.pairing.approve(keepSync) } },
                onReject = ::close,
            )
            is TVSettingsPairingState.Saving -> PaneNote("Saving settings…")
            is TVSettingsPairingState.Saved -> {
                PaneTitle("Settings saved on this TV")
                if (current.syncEnabled) PaneNote("This iPhone can send updates while 4789 is open.")
            }
            is TVSettingsPairingState.Error -> PaneNote(current.message)
        }
        if (creating || (current !is TVSettingsPairingState.Staged && current !is TVSettingsPairingState.Saving)) {
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                if (!creating && current is TVSettingsPairingState.Error) {
                    SettingsDialogButton("New code", {
                        creating = true
                        attempt++
                    }, modifier = Modifier.focusRequester(first), kind = ButtonKind.Primary)
                }
                SettingsDialogButton(
                    if (!creating && current is TVSettingsPairingState.Saved) "Done" else "Close",
                    ::close,
                    modifier = if (!creating && current is TVSettingsPairingState.Error) Modifier
                        else Modifier.focusRequester(first),
                )
            }
        }
        LaunchedEffect(creating, current::class) {
            if (creating || current !is TVSettingsPairingState.Saving) first.requestFocus()
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
        PaneTitle("Review settings")
        Spacer(Modifier.height(TvSpace.XS))
        Text(
            "Review the ${if (total == 1) "item" else "$total items"} before saving. Keys and passwords stay hidden.",
            style = TvType.Body,
            color = TvColor.TextSecondary,
            maxLines = 2,
        )
        Spacer(Modifier.height(TvSpace.S))
        ReceiptRows(categories)
        Spacer(Modifier.height(TvSpace.M))
        SettingRow(
            label = "Keep this iPhone trusted",
            checked = keepSync,
            onClick = { keepSync = !keepSync },
            note = "Allow updates from this iPhone while 4789 is open.",
        )
        Spacer(Modifier.height(TvSpace.S))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            SettingsDialogButton(
                "Save settings",
                { onApprove(keepSync) },
                kind = ButtonKind.Primary,
                modifier = Modifier.focusRequester(firstControl),
            )
            SettingsDialogButton("Discard", onReject)
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
    val pairing = remember(context) { context.appGraph.pairing }
    val noAddons = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<Addon>()) }
    val services by client.services.collectAsState()
    val registry = services?.registry
    val addons by (registry?.addons ?: noAddons).collectAsState()
    val scope = rememberCoroutineScope()
    val phoneAddonsHidden = services?.document?.tvReplaceSources == true
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow("Manage from your phone", note = "Scan a code to paste a link or manage installed add-ons.",
            onClick = actions.onAddonLink, prominent = true,
            icon = SettingsPage.Pair.icon,
            modifier = Modifier.focusRequester(actions.firstControl))
        SettingRow("Import from Stremio", note = "Bring over the add-ons in your Stremio account.",
            icon = SettingsPage.Addons.icon, tint = SettingsPage.Addons.tint, onClick = actions.onStremioImport)
        Spacer(Modifier.height(TvSpace.XS))
        PaneTitle("Installed add-ons")
        if (addons.isEmpty()) {
            PaneNote("No add-ons yet. Use your phone or import from Stremio to add one.")
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 14.dp
                val columns = 4
                val cell = (maxWidth - gap * (columns - 1)) / columns
                val visibleRows = ((addons.size + columns - 1) / columns).coerceAtMost(2)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.height(cell * visibleRows + gap * (visibleRows - 1)),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    items(addons, key = Addon::key) { addon ->
                        AddonTile(addon, actions.onAddonDetails)
                    }
                }
            }
        }
        if (services?.document?.tvImportedSources?.isNotEmpty() == true || phoneAddonsHidden) {
            SettingRow("Delete Stremio imports", destructive = true, onClick = {
                actions.onConfirm(ConfirmRequest(
                    title = "Delete Stremio imports?",
                    body = if (phoneAddonsHidden) {
                        "Remove the Stremio imports from this TV and show your synced iPhone add-ons again."
                    } else {
                        "Remove the imported Stremio add-ons and their catalogs from this TV."
                    },
                    destructiveLabel = "Delete imports",
                    onConfirm = {
                        scope.launch {
                            runCatching { pairing.deleteStremioImports() }.fold(
                                onSuccess = { actions.nav.toast("Stremio imports deleted.") },
                                onFailure = { actions.nav.toast("Could not delete the imports. Try again.") },
                            )
                        }
                    },
                ))
            })
        }
        if (phoneAddonsHidden) {
            SettingRow(
                "Include iPhone add-ons",
                note = "The current Stremio import hides add-ons synced from your iPhone.",
                onClick = {
                    scope.launch {
                        if (pairing.includePhoneAddons()) {
                            actions.nav.toast("iPhone add-ons included.")
                        }
                        else actions.nav.toast("Couldn't include iPhone add-ons. Try again.")
                    }
                },
            )
        }
    }
}

@Composable
private fun AddonTile(addon: Addon, onOpen: (String, FocusRequester) -> Unit) {
    val opener = remember { FocusRequester() }
    val status = when {
        !addon.enabled -> "Off"
        addon.health.failStreak > 0 -> "Check connection"
        else -> null
    }
    TvFocusable(
        onClick = { onOpen(addon.manifestURL, opener) },
        modifier = Modifier.aspectRatio(1f).focusRequester(opener),
        stateDescription = status,
        cornerRadius = 16.dp,
        focusScale = 1f,
    ) { focused ->
        Column(
            Modifier.fillMaxSize().clip(TvShape.CardProminent)
                .background(if (focused) SettingsFocusFill else TvColor.TextPrimary.copy(alpha = 0.05f))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AddonLogo(addon, Modifier.size(78.dp))
            Spacer(Modifier.height(14.dp))
            Text(addon.displayName, style = TvType.ControlLabel, color = TvColor.TextPrimary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            status?.let {
                Text(it, style = TvType.Meta, color = if (addon.enabled) TvColor.Warning else TvColor.TextSecondary,
                    maxLines = 1)
            }
        }
    }
}

@Composable
internal fun AddonLogo(addon: Addon, modifier: Modifier = Modifier) {
    val logo = remember(addon.manifest?.logo) {
        addon.manifest?.logo?.takeIf { url ->
            runCatching {
                val uri = java.net.URI(url)
                uri.scheme == "https" && uri.host != null && uri.userInfo == null
            }.getOrDefault(false)
        }
    }
    var hasLogo by remember(logo) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!hasLogo) {
            val initials = addon.displayName.filter(Char::isLetterOrDigit).take(2).uppercase()
            val tint = SettingsPage.Addons.tint
            Box(Modifier.fillMaxSize().clip(TvShape.CardProminent).background(tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center) {
                Text(initials.ifEmpty { "+" }, style = TvType.PlateTitle, color = tint)
            }
        }
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { hasLogo = true },
                onError = { hasLogo = false },
            )
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
    val fanartSaved by client.fanartConfigured.collectAsState()
    val scope = rememberCoroutineScope()

    Column {
        SettingRow(
            "Send keys from iPhone",
            note = "Paste keys in 4789 on your iPhone, then scan a code to send your setup here.",
            onClick = actions.onPhoneSync,
            prominent = true,
            icon = SettingsPage.Pair.icon,
            tint = SettingsPage.Accounts.tint,
            modifier = Modifier.focusRequester(actions.firstControl),
        )
        Spacer(Modifier.height(TvSpace.M))
        PaneTitle("Playback services")
        Spacer(Modifier.height(TvSpace.XS))
        ServiceStatus("TorBox", "Play files from your TorBox account.", R.drawable.logo_settings_torbox,
            saved = !document?.torboxAPIKey.isNullOrBlank())
        ServiceStatus("Real-Debrid", "Play files from your Real-Debrid account.", R.drawable.logo_settings_realdebrid,
            saved = !document?.realDebridAPIKey.isNullOrBlank())
        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("Artwork and ratings")
        Spacer(Modifier.height(TvSpace.XS))
        ServiceStatus("TMDB", "Browse popular titles and see cast, artwork and details.", R.drawable.ic_rating_tmdb,
            saved = !document?.tmdbAPIKey.isNullOrBlank())
        ServiceStatus("MDBList", "Show ratings from more sites.", R.drawable.logo_settings_mdblist,
            saved = !document?.mdbListAPIKey.isNullOrBlank())
        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("Other accounts")
        Spacer(Modifier.height(TvSpace.XS))
        if (EinthusanProvider.available) EinthusanSettings(EinthusanAccount.from(document))
        ReadoutRow("Private catalog server", if (document?.catalogServerToken.isNullOrBlank()) "No token saved" else "Token saved")
        Spacer(Modifier.height(TvSpace.M))
        PaneTitle("Poster artwork")
        SettingRow(
            label = "Fanart.tv personal key",
            value = if (fanartSaved) "Saved on this TV" else "Add key",
            note = "Use Fanart.tv artwork when a poster is missing or broken.",
            onClick = {
                actions.onKeyboard(KeyboardRequest(
                    header = "Fanart.tv personal key",
                    hint = "Enter your personal key from Fanart.tv.",
                    initialText = "",
                    secret = true,
                    onDone = { value ->
                        scope.launch {
                            val saved = client.setFanartKey(value)
                            actions.nav.toast(if (saved) "Fanart.tv key saved on this TV." else "Could not save Fanart.tv key.")
                        }
                    },
                ))
            },
        )
        if (fanartSaved) SettingRow("Remove Fanart.tv key", onClick = {
            scope.launch {
                val removed = client.setFanartKey(null)
                actions.nav.toast(if (removed) "Fanart.tv key removed." else "Could not remove Fanart.tv key.")
            }
        })
        PaneNote("Key values stay hidden on this TV.")
    }
}

@Composable
private fun ServiceStatus(name: String, note: String, logo: Int, saved: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(TvShape.CardProminent)
            .background(TvColor.TextPrimary.copy(alpha = 0.035f))
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(96.dp, 48.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(logo), null, Modifier.size(
                if (logo == R.drawable.ic_rating_tmdb) 88.dp else 42.dp, 42.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = TvType.ControlLabel, color = TvColor.TextPrimary)
            Text(note, style = TvType.Meta, color = TvColor.TextSecondary)
        }
        val statusColor = if (saved) TvColor.Success else TvColor.TextSecondary
        Text(
            if (saved) "Key saved" else "No key saved",
            style = TvType.Meta,
            color = statusColor,
            modifier = Modifier.clip(TvShape.Card).background(statusColor.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EinthusanSettings(account: EinthusanAccount) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val client = remember(context) { context.clientGraph }
    var editing by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    SettingRow(
        label = "Einthusan",
        value = when {
            !account.enabled -> "Off"
            account.hasCredentials -> "Account saved"
            else -> "Set up"
        },
        note = "Find movies in Sources with your Einthusan account.",
        modifier = Modifier.focusRequester(opener),
        onClick = { editing = true },
    )
    if (!editing) return

    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var enabled by remember { mutableStateOf(account.enabled) }
    var email by remember { mutableStateOf(account.email) }
    var password by remember { mutableStateOf(account.password) }
    var language by remember { mutableStateOf(account.language) }
    var chooseLanguage by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun draft() = EinthusanAccount(email.trim(), password, enabled, language)
    fun close() {
        editing = false
        runCatching { opener.requestFocus() }
    }
    SettingsModal("Einthusan", onClose = ::close) {
        LaunchedEffect(Unit) { first.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingRow("Include in Sources", checked = enabled,
                enabled = !busy, modifier = Modifier.focusRequester(first), onClick = { enabled = !enabled })
            LiveFormField("Email", email, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)) {
                if (!busy && it.length <= 512) { email = it; message = null }
            }
            LiveFormField("Password", password, password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) {
                if (!busy && it.length <= 512) { password = it; message = null }
            }
            SettingRow("Default language", language.label, enabled = !busy, onClick = { chooseLanguage = true })
            if (chooseLanguage) {
                SettingsModal("Default language", onClose = { chooseLanguage = false }) {
                    val selected = remember { FocusRequester() }
                    LaunchedEffect(Unit) { selected.requestFocus() }
                    EinthusanLanguage.entries.forEach { option ->
                        SettingRow(option.label, modifier = if (option == language) Modifier.focusRequester(selected) else Modifier,
                            onClick = { language = option; chooseLanguage = false; message = null })
                    }
                }
            }
            message?.let {
                Text(it, color = TvColor.TextSecondary, style = TvType.Meta,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsDialogButton("Verify login", enabled = !busy && draft().hasCredentials, onClick = {
                    val entry = draft()
                    busy = true
                    message = "Verifying login…"
                    scope.launch {
                        try {
                            client.einthusan.verify(entry)
                            message = "Signed in to Einthusan."
                        } catch (failure: EinthusanFailure) {
                            message = failure.message
                        } finally {
                            busy = false
                        }
                    }
                })
                SettingsDialogButton("Save", enabled = !busy, onClick = {
                    val entry = draft()
                    busy = true
                    scope.launch {
                        try {
                            graph.pairing.saveEinthusan(entry)
                            client.reloadSettingsNow()
                            close()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "Could not save this account. Try again."
                        } finally {
                            busy = false
                        }
                    }
                })
            }
        }
    }
}

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
    var feedbackSounds by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.FEEDBACK_SOUNDS, false)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            label = "Remote buttons",
            note = "Test buttons and change what they do.",
            onClick = actions.onRemoteButtons,
            modifier = Modifier.focusRequester(actions.firstControl),
        )
        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("Audio and subtitles")
        SettingRow(
            label = "Feedback sounds",
            note = "Play a soft click while browsing and a cue when a source starts or fails.",
            checked = feedbackSounds,
            onClick = {
                feedbackSounds = !feedbackSounds
                prefs.edit().putBoolean(SettingsKeys.FEEDBACK_SOUNDS, feedbackSounds).apply()
            },
        )
        SettingRow(
            label = "Audio language",
            value = audioLanguage,
            icon = R.drawable.ic_player_audio,
            tint = SettingsPage.Playback.tint,
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
        PaneNote("Sync subtitle size and colour from your iPhone.")

        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("Picture and playback")
        SettingRow(
            label = "Player engine",
            value = engine,
            note = "Automatic chooses the player. Changes apply after you restart 4789.",
            onClick = {
                val options = buildList {
                    add(ENGINE_AUTOMATIC)
                    add(ENGINE_EXO)
                    if (ReceiverEnginePolicy.MPV_SELECTABLE) add(ENGINE_MPV)
                }
                actions.onChoice(PanelRequest("Player engine", options, engine) { picked ->
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
            note = "Improve lower-resolution video on supported equipment.",
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
            label = "Match frame rate",
            note = "Let the TV match the video’s frame rate when supported.",
            checked = frameRate,
            tint = SettingsPage.Playback.tint,
            onClick = {
                frameRate = !frameRate
                actions.onAutoFrameRate(frameRate)
            },
        )
        SettingRow(
            label = "Play next episode",
            note = "Start the next episode when the current one ends.",
            checked = autoNext,
            tint = SettingsPage.Playback.tint,
            onClick = {
                autoNext = !autoNext
                prefs.edit().putBoolean(SettingsKeys.AUTO_NEXT, autoNext).apply()
            },
        )
        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("Source selection")
        SettingRow("Source selection from iPhone",
            note = "View the rules used to choose sources.",
            onClick = {
                actions.onReadout("Source selection from iPhone", listOf(
                    "Preferred quality" to StreamFacts.qualityLabel(rules.minQualityRank),
                    "Maximum file size" to (rules.maxSizeGB?.let { PlaybackRules.sizeText(it) } ?: "Any"),
                    "Prefer cached" to if (rules.readyOnly) "On" else "Off",
                    "Play best without asking" to if (rules.autoPlay) "On" else "Off",
                    "Words to avoid" to "${rules.normalizedKeywords.size} set",
                ))
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
    var reduceMotion by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.REDUCE_MOTION, false)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            label = "Background",
            value = if (canvas == SettingsKeys.CANVAS_BLACK) "Black" else "Slate",
            note = "Choose the canvas behind menus and titles.",
            icon = SettingsPage.Look.icon,
            tint = SettingsPage.Look.tint,
            modifier = Modifier.focusRequester(actions.firstControl),
            onClick = {
                actions.onChoice(
                    PanelRequest(
                        "Background",
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
            label = "Reduce motion",
            checked = reduceMotion,
            tint = SettingsPage.Look.tint,
            note = "Reduce motion in navigation and screen transitions.",
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
    val prefs = remember(context) { context.appGraph.presentationPreferences }
    val services by client.services.collectAsState()
    var rows by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.DISCOVER_ROWS, false)) }
    var usernameOverride by remember { mutableStateOf(prefs.getString(SettingsKeys.LETTERBOXD_LIKES_USERNAME, null)) }
    val username = usernameOverride ?: services?.document?.letterboxdUsernames?.firstOrNull().orEmpty()
    val jobs by client.globalLibrary.jobs().collectAsState(initial = emptyList())
    val snapshots by client.snapshots.status().collectAsState()
    // One clock read per composition of the page, not one per row.
    val now = remember(jobs, snapshots) { System.currentTimeMillis() }

    Column {
        SettingRow(
            "Discover layout",
            value = if (rows) "Rows" else "Grid",
            note = "Browse posters in horizontal rows or a grid.",
            icon = SettingsPage.Jobs.icon,
            tint = SettingsPage.Jobs.tint,
            modifier = Modifier.focusRequester(actions.firstControl),
            onClick = {
                actions.onChoice(
                    PanelRequest("Discover layout", listOf("Grid", "Rows"), if (rows) "Rows" else "Grid") { picked ->
                        rows = picked == "Rows"
                        prefs.edit().putBoolean(SettingsKeys.DISCOVER_ROWS, rows).apply()
                    },
                )
            },
        )
        Spacer(Modifier.height(TvSpace.XS))
        SettingRow(
            "Letterboxd lists",
            value = username.ifBlank { "Add username" },
            note = "Use a public username to browse liked lists in Discover.",
            icon = R.drawable.ic_rating_letterboxd,
            tint = SettingsPage.Jobs.tint,
            tintIcon = false,
            onClick = {
                actions.onKeyboard(KeyboardRequest(
                    header = "Letterboxd username",
                    hint = "Enter a public username. Clear the field to use your synced profile.",
                    initialText = username,
                    usernameInput = true,
                    onDone = { name ->
                        usernameOverride = name.ifEmpty { null }
                        prefs.edit().putString(SettingsKeys.LETTERBOXD_LIKES_USERNAME, usernameOverride).apply()
                    },
                ))
            },
        )
        Spacer(Modifier.height(TvSpace.M))
        PaneTitle("Imported Letterboxd lists")
        Spacer(Modifier.height(TvSpace.XS))
        LetterboxdPage(actions)
        Spacer(Modifier.height(TvSpace.M))
        PaneTitle("Catalog refresh")
        Spacer(Modifier.height(TvSpace.XS))
        val refreshing = snapshots.sources.any { it.state == SnapshotState.LOADING } ||
            jobs.any { it.state == JobState.RUNNING }
        SettingRow(
            if (refreshing) "Refreshing catalogs…" else "Refresh now",
            note = "Update shelves now or wait for the next automatic refresh.",
            onClick = { client.scheduler.manual() },
            enabled = !refreshing,
            prominent = true,
            icon = R.drawable.ic_catalog_refresh,
            tint = SettingsPage.Jobs.tint,
        )
        Spacer(Modifier.height(TvSpace.M))
        if (jobs.isEmpty() && snapshots.sources.isEmpty()) {
            PaneNote("No refreshes yet. Choose Refresh now to load your shelves.")
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = TvSpace.M),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            JobHeader("Source", Modifier.weight(4f))
            JobHeader("Last run", Modifier.weight(1.2f))
            JobHeader("Status", Modifier.weight(1.4f))
            JobHeader("Items", Modifier.weight(0.8f))
            JobHeader("Next run", Modifier.weight(1.2f))
        }
        Spacer(Modifier.height(TvSpace.XS))
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
                            SnapshotState.NOT_CONFIGURED -> "Not set"
                            SnapshotState.IDLE -> "Idle"
                        },
                        items = source.itemCount.takeIf { it > 0 }?.toString() ?: "\u2014",
                        nextRun = if (source.state == SnapshotState.NOT_CONFIGURED) "\u2014"
                            else JobSchedule.nextRunLabel(source.lastRunMillis, now),
                        message = source.message,
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
                        message = job.message,
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
private fun JobHeader(label: String, modifier: Modifier) {
    Box(modifier) {
        Text(label, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
    }
}

/** Readouts take focus for D-pad scrolling. */
@Composable
private fun JobRow(
    name: String,
    dataDate: DataDate? = null,
    lastRun: String,
    result: String,
    items: String,
    nextRun: String,
    message: String? = null,
) {
    val resultColor = when (result) {
        "Running" -> TvColor.InProgress
        "Failed" -> TvColor.Error
        "OK" -> TvColor.Cached
        else -> TvColor.TextMuted
    }
    val body: @Composable (Boolean) -> Unit = { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else TvColor.CanvasBlack.copy(alpha = 0.46f))
                .padding(horizontal = TvSpace.M, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(4f)) {
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
                message?.let { Text(it, style = TvType.Meta,
                    color = if (result == "Not set") TvColor.TextMuted else TvColor.Warning, maxLines = 3) }
            }
            Box(Modifier.weight(1.2f)) {
                Text(lastRun, style = TvType.data(20), color = TvColor.TextSecondary, maxLines = 1)
            }
            Box(Modifier.weight(1.4f)) {
                Text(result, style = TvType.Meta, color = resultColor, maxLines = 1)
            }
            Box(Modifier.weight(0.8f)) {
                Text(items, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
            }
            Box(Modifier.weight(1.2f)) {
                Text(nextRun, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
            }
        }
    }
    // Readouts may receive focus for remote scrolling and speech. They have no click action.
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.tvFocusRing(focused, 10.dp).onFocusChanged { focused = it.isFocused }
        .focusable().semantics(mergeDescendants = true) {}) { body(focused) }
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("Licences", note = "Read open-source licences and credits.",
            onClick = actions.onLicences, modifier = Modifier.focusRequester(actions.firstControl))
        SettingRow(
            "Export diagnostics",
            note = "Save a local log file for troubleshooting.",
            onClick = {
                scope.launch {
                    val path = withContext(Dispatchers.IO) { exportDiagnostics(context) }
                    actions.nav.toast(path ?: "Couldn’t save the log. Check TV storage and try again.")
                }
            },
        )
        Spacer(Modifier.height(TvSpace.S))
        PaneTitle("App and device")
        ReadoutRow("Version", version, height = 48.dp)
        if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
            ReadoutRow("Benchmark logging", "On", height = 48.dp)
        }
        ReadoutRow("Device", "${Build.MODEL}, Android ${Build.VERSION.RELEASE}", height = 48.dp)
        ReadoutRow("Display", panel, height = 48.dp)
        ReadoutRow("Active player engine", graph.controller.engine.name.uppercase(), height = 48.dp)
        ReadoutRow("Receiver ID", shortId(receiverId), height = 48.dp)
        ReadoutRow("Receiver ports", "${ReceiverPorts.HTTP} / ${ReceiverPorts.WEB_SOCKET}", height = 48.dp)
        PaneNote("Ratings and brand marks: IMDb, Trakt, TMDB, and Letterboxd. This product uses the TMDB API but is not endorsed or certified by TMDB.")
        PaneNote("Streaming availability is powered by JustWatch through TMDB. Detail icons are Lucide Icons, used under their ISC and MIT licences.")
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
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        ?: return null
    return runCatching {
        val target = File(directory, ReceiverDiagnostics.FILE_NAME)
        val report = ReceiverDiagnostics.read()
        check(!report.startsWith("Unable to read diagnostics:")) { "diagnostics_read_failed" }
        target.writeText(report)
        "Saved ${target.name} in 4789’s Downloads folder."
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
