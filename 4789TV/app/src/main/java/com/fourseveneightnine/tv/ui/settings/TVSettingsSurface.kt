package com.fourseveneightnine.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.fourseveneightnine.tv.settings.PairingQRCode
import com.fourseveneightnine.tv.settings.TVSettingsImportSource
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.settings.TVSettingsReceipt
import com.fourseveneightnine.tv.ui.TvTokens
import com.fourseveneightnine.tv.ui.TVReceiverPresentationPolicy

private val Slate0 = TvTokens.Color.Black
private val Slate1 = TvTokens.Color.Slate
private val Elevated = TvTokens.Color.Elevated
private val Elevated2 = TvTokens.Color.ElevatedFocused
private val Accent = TvTokens.Color.BrandOrange
private val PrimaryText = TvTokens.Color.Text
private val SecondaryText = TvTokens.Color.Secondary
private val TertiaryText = TvTokens.Color.Tertiary

internal enum class TVSettingsDestination(val title: String, val subtitle: String) {
    Pair("Pair & Sync", "Move setup from your phone"),
    Addons("Addons & Catalogs", "Sources, subtitles, order"),
    Keys("Debrid & API Keys", "Playback credentials"),
    Metadata("Metadata & AI", "Ratings, Trakt, enrichment"),
    Playback("Playback", "TV-local picture and audio"),
    About("About this receiver", "Security and device scope"),
}

/** Redacted, UI-only facts. Raw settings values never enter Compose state. */
internal data class TVSettingsPresentationSnapshot(
    val pairedStatus: String,
    val engineLabel: String,
    val autoFrameRateEnabled: Boolean,
    val diagnosticsEnabled: Boolean,
) {
    val tmdbStatus: String
        get() = "Artwork settings are managed on your paired iPhone."

    companion object {
        fun from(
            paired: Boolean,
            syncEnabled: Boolean,
            engineLabel: String,
            autoFrameRateEnabled: Boolean,
            diagnosticsEnabled: Boolean,
        ): TVSettingsPresentationSnapshot {
            return TVSettingsPresentationSnapshot(
                pairedStatus = when {
                    !paired -> "NOT PAIRED"
                    syncEnabled -> "CONNECTED"
                    else -> "IMPORTED"
                },
                engineLabel = engineLabel,
                autoFrameRateEnabled = autoFrameRateEnabled,
                diagnosticsEnabled = diagnosticsEnabled,
            )
        }
    }
}

/** Credentials never cross the receiver presentation boundary. */
internal fun redactedCredentialLabel(): String = "REDACTED"

@Composable
internal fun TVSettingsSurface(
    state: TVSettingsPairingState,
    receiverName: String,
    pairingRequired: Boolean,
    presentation: TVSettingsPresentationSnapshot? = null,
    initialDestination: TVSettingsDestination = TVSettingsDestination.Pair,
    onClose: () -> Unit,
    onBeginPairing: () -> Unit,
    onImportFile: () -> Unit,
    onInvitationExpired: () -> Unit,
    onApprove: (keepSync: Boolean) -> Unit,
    onReject: () -> Unit,
    onDisconnectSync: () -> Unit,
    onClearSetup: () -> Unit,
    onRefreshTMDB: () -> Unit,
) {
    val platformDensity = LocalDensity.current
    val configuration = LocalConfiguration.current
    val designDensity = remember(configuration.screenWidthDp, configuration.screenHeightDp, platformDensity.density) {
        Density(
            TvTokens.designScale(
                (configuration.screenWidthDp * platformDensity.density).toInt(),
                (configuration.screenHeightDp * platformDensity.density).toInt(),
            ),
            platformDensity.fontScale,
        )
    }
    // The setup root still registers Back so the key cannot fall through to Activity and exit the
    // receiver. Once setup exists, the same handler resumes ordinary dismissible Settings behavior.
    BackHandler { if (!pairingRequired) onClose() }
    var destination by remember(initialDestination) { mutableStateOf(initialDestination) }
    LaunchedEffect(pairingRequired, state) {
        if (pairingRequired) {
            destination = TVSettingsDestination.Pair
            if (state == TVSettingsPairingState.Idle) onBeginPairing()
        }
    }
    val railFocusRequestKey: Any? = when (state) {
        is TVSettingsPairingState.Staged -> null
        else -> state
    }

    CompositionLocalProvider(LocalDensity provides designDensity) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Slate0,
            surface = Elevated,
            onSurface = PrimaryText,
            background = Slate0,
            onBackground = PrimaryText,
        ),
    ) {
        if (!pairingRequired && state is TVSettingsPairingState.Saved) {
            presentation?.let { snapshot ->
                ConfiguredSettingsContent(
                    snapshot = snapshot,
                    onClose = onClose,
                    onUpdateFromPhone = onBeginPairing,
                    onLoadArtwork = onRefreshTMDB,
                )
            } ?: SettingsPresentationLoading(onClose)
        } else Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Slate0, Slate1, Elevated)))
                .padding(
                    horizontal = TvTokens.Geometry.ScreenPaddingDp.dp,
                    vertical = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
                ),
        ) {
            NavigationRail(
                receiverName = receiverName,
                state = state,
                selected = destination,
                focusRequestKey = railFocusRequestKey,
                pairingRequired = pairingRequired,
                onSelect = { destination = it },
                onClose = onClose,
            )
            Spacer(Modifier.width(40.dp))
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Elevated.copy(alpha = 0.94f))
                    .padding(horizontal = 42.dp, vertical = 34.dp),
            ) {
                DestinationContent(
                    destination = destination,
                    state = state,
                    onDestinationRequested = { destination = it },
                    onClose = onClose,
                    onBeginPairing = onBeginPairing,
                    onImportFile = onImportFile,
                    onInvitationExpired = onInvitationExpired,
                    onApprove = onApprove,
                    onReject = onReject,
                    onDisconnectSync = onDisconnectSync,
                    onClearSetup = onClearSetup,
                )
            }
        }
    }
    }
}

@Composable
private fun SettingsPresentationLoading(onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Slate0, Slate1)))
            .padding(
                horizontal = TvTokens.Geometry.ScreenPaddingDp.dp,
                vertical = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Settings", color = PrimaryText, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, fontFamily = TvTokens.Type.Display)
        Spacer(Modifier.height(16.dp))
        Text("Reading redacted receiver status…", color = SecondaryText, fontSize = 18.sp)
        Spacer(Modifier.height(28.dp))
        FocusableAction("Close", secondary = true, focusRequester = closeFocus, onClick = onClose)
    }
}

@Composable
private fun NavigationRail(
    receiverName: String,
    state: TVSettingsPairingState,
    selected: TVSettingsDestination,
    focusRequestKey: Any?,
    pairingRequired: Boolean,
    onSelect: (TVSettingsDestination) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.width(276.dp).fillMaxHeight(),
    ) {
        Text("4789 TV", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = TvTokens.Type.Display)
        Text(receiverName, color = SecondaryText, fontSize = 14.sp, fontFamily = TvTokens.Type.UI)
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val destinations = if (pairingRequired || state !is TVSettingsPairingState.Saved) {
                listOf(TVSettingsDestination.Pair)
            } else {
                TVSettingsDestination.entries
            }
            destinations.forEach { item ->
                val subtitle = if (item == TVSettingsDestination.Pair) {
                    when (state) {
                        is TVSettingsPairingState.Saved -> if (state.syncEnabled) {
                            "iPhone connected · settings saved here"
                        } else {
                            "iPhone setup imported · settings saved here"
                        }
                        else -> item.subtitle
                    }
                } else {
                    item.subtitle
                }
                RailItem(
                    item = item,
                    subtitle = subtitle,
                    selected = selected == item,
                    requestInitialFocus = item == selected,
                    focusRequestKey = focusRequestKey,
                ) { onSelect(item) }
            }
            if (!pairingRequired && state is TVSettingsPairingState.Saved) {
                Spacer(Modifier.height(8.dp))
                FocusableAction("Close settings", secondary = true, onClick = onClose)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RailItem(
    item: TVSettingsDestination,
    subtitle: String,
    selected: Boolean,
    requestInitialFocus: Boolean,
    focusRequestKey: Any?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus, focusRequestKey) {
        if (requestInitialFocus && (focusRequestKey != null || !initialFocusRequested)) {
            focusRequester.requestFocus()
            initialFocusRequested = true
        }
    }
    val fill = when {
        focused -> Accent
        selected -> Elevated2
        else -> Color.Transparent
    }
    val textColor = if (focused) Slate0 else PrimaryText
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(focusRequester)
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(item.title, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = TvTokens.Type.UI)
        Text(
            subtitle,
            color = if (focused) Slate0.copy(alpha = 0.7f) else SecondaryText,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun DestinationContent(
    destination: TVSettingsDestination,
    state: TVSettingsPairingState,
    onDestinationRequested: (TVSettingsDestination) -> Unit,
    onClose: () -> Unit,
    onBeginPairing: () -> Unit,
    onImportFile: () -> Unit,
    onInvitationExpired: () -> Unit,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit,
    onDisconnectSync: () -> Unit,
    onClearSetup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(destination.title, color = PrimaryText, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, fontFamily = TvTokens.Type.Display)
        Text(destination.subtitle, color = SecondaryText, fontSize = 16.sp, fontFamily = TvTokens.Type.UI)
        Spacer(Modifier.height(28.dp))
        when (destination) {
            TVSettingsDestination.Pair -> PairAndSyncContent(
                state,
                onBeginPairing,
                onImportFile,
                onInvitationExpired,
                onApprove,
                onReject,
                onDisconnectSync,
                onClearSetup,
            )
            TVSettingsDestination.Addons -> CategoryContent(
                "Addons & Catalogs",
                listOf("Stremio manifest sources", "AIOStreams and subtitles", "MediaFusion", "Letterboxd profiles", "Catalog order"),
                state.receiptCount("Addons & Catalogs"),
                onOpenPairing = { onDestinationRequested(TVSettingsDestination.Pair) },
            )
            TVSettingsDestination.Keys -> CategoryContent(
                "Debrid & API Keys",
                listOf("TorBox", "Real-Debrid", "Bitsearch", "Catalog server", "Einthusan account"),
                state.receiptCount("Debrid & API Keys"),
                footer = "Catalog Server Token is copied from the iPhone during Pair & Sync. If Tamil MV says it is missing, choose Update from iPhone after unlocking the phone.",
                onOpenPairing = { onDestinationRequested(TVSettingsDestination.Pair) },
            )
            TVSettingsDestination.Metadata -> CategoryContent(
                "Metadata & AI",
                listOf("TMDB override", "MDBList", "Trakt", "DeepSeek", "OpenRouter"),
                state.receiptCount("Metadata & AI"),
                onOpenPairing = { onDestinationRequested(TVSettingsDestination.Pair) },
            )
            TVSettingsDestination.Playback -> CategoryContent(
                "Playback",
                listOf("Imported source limits", "Picture size", "Audio and subtitle track", "Playback speed", "AI upscaling"),
                state.receiptCount("Playback"),
                footer = "Picture size, track choice, speed and upscaling stay local to this television.",
                onOpenPairing = { onDestinationRequested(TVSettingsDestination.Pair) },
            )
            TVSettingsDestination.About -> AboutContent(onClose)
        }
    }
}

@Composable
private fun ConfiguredSettingsContent(
    snapshot: TVSettingsPresentationSnapshot,
    onClose: () -> Unit,
    onUpdateFromPhone: () -> Unit,
    onLoadArtwork: () -> Unit,
) {
    val rowFocus = remember { List(8) { FocusRequester() } }
    val artworkFocus = remember { FocusRequester() }
    val updateFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var lastSettingsRow by remember { mutableStateOf(0) }
    var initialFocusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialFocusPlaced && rowFocus.first().requestFocus()) initialFocusPlaced = true
    }
    val rows = listOf(
        "Paired phone" to snapshot.pairedStatus,
        "Playback engine" to snapshot.engineLabel,
        "Auto frame rate" to if (snapshot.autoFrameRateEnabled) "ON" else "OFF",
        "TMDB key" to redactedCredentialLabel(),
        "Torbox key" to redactedCredentialLabel(),
        "Real-Debrid key" to redactedCredentialLabel(),
        "Catalog server" to redactedCredentialLabel(),
        "Diagnostics" to if (snapshot.diagnosticsEnabled) "ON" else "OFF",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Slate0, Slate1, Elevated)))
            .padding(
                start = TvTokens.Geometry.ScreenPaddingDp.dp,
                top = 58.dp,
                end = TvTokens.Geometry.ScreenPaddingDp.dp,
                bottom = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Settings", color = PrimaryText, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, fontFamily = TvTokens.Type.Display)
                Text("Receiver configuration", color = SecondaryText, fontSize = 18.sp, fontFamily = TvTokens.Type.UI)
            }
            FocusableAction("Close", secondary = true, focusRequester = closeFocus, onClick = onClose)
        }
        Spacer(Modifier.height(34.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { index, (label, value) ->
                    SettingsFactRow(
                        label = label,
                        value = value,
                        redacted = index in 3..6 && value == "REDACTED",
                        focusRequester = rowFocus[index],
                        up = TVReceiverPresentationPolicy.settingsAdjacentIndex(index, -1, rows.size)?.let(rowFocus::get),
                        down = TVReceiverPresentationPolicy.settingsAdjacentIndex(index, 1, rows.size)?.let(rowFocus::get),
                        right = artworkFocus,
                        onFocused = { lastSettingsRow = index },
                        onActivate = { artworkFocus.requestFocus() },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .width(590.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Elevated)
                    .padding(32.dp),
            ) {
                Text("TMDB ARTWORK", color = TvTokens.Color.Warning, fontFamily = TvTokens.Type.Data, fontSize = 13.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(16.dp))
                Text("Artwork on this TV", color = PrimaryText, fontSize = 32.sp, fontFamily = TvTokens.Type.Display, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(snapshot.tmdbStatus, color = SecondaryText, fontSize = 17.sp, lineHeight = 26.sp)
                Spacer(Modifier.height(20.dp))
                Text(redactedCredentialLabel(), color = TertiaryText, fontFamily = TvTokens.Type.Data, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                FocusableAction(
                    "Load artwork",
                    focusRequester = artworkFocus,
                    modifier = Modifier.focusProperties { left = rowFocus[lastSettingsRow]; down = updateFocus },
                    onClick = onLoadArtwork,
                )
                Spacer(Modifier.height(12.dp))
                FocusableAction(
                    "Update from iPhone",
                    secondary = true,
                    focusRequester = updateFocus,
                    modifier = Modifier.focusProperties { up = artworkFocus; left = rowFocus[lastSettingsRow]; down = closeFocus },
                    onClick = onUpdateFromPhone,
                )
                Spacer(Modifier.height(8.dp))
                Text("Change keys on iPhone, then update this receiver.", color = TertiaryText, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SettingsFactRow(
    label: String,
    value: String,
    redacted: Boolean,
    focusRequester: FocusRequester,
    up: FocusRequester?,
    down: FocusRequester?,
    right: FocusRequester,
    onFocused: () -> Unit,
    onActivate: () -> Unit,
) {
    var focused by remember(label) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Elevated2 else Elevated)
            .border(
                TvTokens.Geometry.FocusRingDp.dp,
                if (focused) Accent else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .focusRequester(focusRequester)
            .focusProperties {
                up?.let { this.up = it }
                down?.let { this.down = it }
                this.right = right
            }
            .clickable(role = Role.Button, onClick = onActivate)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = if (redacted) TertiaryText else if (focused) Accent else SecondaryText, fontFamily = TvTokens.Type.Data, fontSize = 15.sp)
    }
}

@Composable
private fun PairAndSyncContent(
    state: TVSettingsPairingState,
    onBeginPairing: () -> Unit,
    onImportFile: () -> Unit,
    onInvitationExpired: () -> Unit,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit,
    onDisconnectSync: () -> Unit,
    onClearSetup: () -> Unit,
) {
    when (state) {
        TVSettingsPairingState.Idle -> {
            StatusCard("Not configured", "Copy your existing setup without typing keys on the TV.")
            Spacer(Modifier.height(20.dp))
            ActionRow(onBeginPairing, onImportFile)
            PrivacyNote()
        }
        is TVSettingsPairingState.Invitation -> InvitationContent(state, onInvitationExpired)
        is TVSettingsPairingState.Staged -> ReceiptContent(state, onApprove, onReject)
        is TVSettingsPairingState.Saving -> SavingContent(state)
        is TVSettingsPairingState.Saved -> SavedContent(
            state,
            onBeginPairing,
            onImportFile,
            onDisconnectSync,
            onClearSetup,
        )
        is TVSettingsPairingState.Error -> {
            StatusCard("Needs attention", state.message, attention = true)
            Spacer(Modifier.height(20.dp))
            ActionRow(onBeginPairing, onImportFile)
            PrivacyNote()
        }
    }
}

@Composable
private fun InvitationContent(
    state: TVSettingsPairingState.Invitation,
    onInvitationExpired: () -> Unit,
) {
    LaunchedEffect(state.pairingID, state.expiresAtUptimeMillis) {
        val remaining = (state.expiresAtUptimeMillis - android.os.SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        kotlinx.coroutines.delay(remaining)
        onInvitationExpired()
    }
    val qr = remember(state.qrText) { PairingQRCode.render(state.qrText).asImageBitmap() }
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = qr,
            contentDescription = "One-time settings pairing code",
            modifier = Modifier.size(286.dp).clip(RoundedCornerShape(18.dp)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan with 4789", color = PrimaryText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "On your iPhone, open Settings → Backup & Transfer → Send to TV. This code expires in five minutes and can be used once.",
                color = SecondaryText,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
            Text("No API key or password is inside the QR code.", color = Accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ReceiptContent(
    state: TVSettingsPairingState.Staged,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit,
) {
    var keepSync by remember(state.receipt) {
        mutableStateOf(state.source == TVSettingsImportSource.SecurePairing)
    }
    val approvalFocusRequester = remember(state.receipt) { FocusRequester() }
    LaunchedEffect(state.receipt) { approvalFocusRequester.requestFocus() }
    StatusCard(
        "Review before saving",
        "The receiver decrypted the transfer in memory. Values stay hidden; only category counts are shown.",
    )
    Spacer(Modifier.height(16.dp))
    ReceiptRows(state.receipt)
    Spacer(Modifier.height(18.dp))
    if (state.source == TVSettingsImportSource.SecurePairing) {
        FocusableAction(
            if (keepSync) "Foreground sync · On" else "Foreground sync · Off",
            secondary = true,
        ) { keepSync = !keepSync }
        Text(
            "On means this trusted phone can refresh settings while 4789 is open. Disconnecting later keeps this saved copy.",
            color = TertiaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FocusableAction(
            "Save ${state.receipt.totalFields} settings",
            focusRequester = approvalFocusRequester,
        ) { onApprove(keepSync) }
        FocusableAction("Reject", secondary = true, onClick = onReject)
    }
}

@Composable
private fun SavingContent(state: TVSettingsPairingState.Saving) {
    StatusCard(
        "Saving securely…",
        "Encrypting and verifying this setup in Android Keystore. Keep 4789 open for a moment.",
    )
    Spacer(Modifier.height(16.dp))
    ReceiptRows(state.receipt)
    Spacer(Modifier.height(18.dp))
    Text(
        "4789 stays responsive while the atomic save finishes. Save cannot be submitted twice.",
        color = Accent,
        fontSize = 14.sp,
    )
}

@Composable
private fun SavedContent(
    state: TVSettingsPairingState.Saved,
    onBeginPairing: () -> Unit,
    onImportFile: () -> Unit,
    onDisconnectSync: () -> Unit,
    onClearSetup: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val clearConfirmationFocusRequester = remember { FocusRequester() }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            clearConfirmationFocusRequester.requestFocus()
        }
    }
    StatusCard(
        if (state.syncEnabled) "iPhone connected" else "iPhone setup imported",
        if (state.syncEnabled) {
            "Trusted iPhone connected for foreground sync. Your settings are saved on this TV."
        } else {
            "Settings were imported from your iPhone and are saved on this TV. The phone does not need to stay online."
        },
    )
    Spacer(Modifier.height(16.dp))
    StatusCard(
        "Settings stored here",
        "Debrid keys, addon configuration, and metadata settings are kept in this receiver's encrypted local copy and power the TV library.",
    )
    Spacer(Modifier.height(16.dp))
    ReceiptRows(state.receipt)
    Spacer(Modifier.height(20.dp))
    ActionRow(
        onBeginPairing = onBeginPairing,
        onImportFile = onImportFile,
        pairingLabel = "Update from iPhone",
    )
    Spacer(Modifier.height(12.dp))
    if (state.syncEnabled) {
        FocusableAction("Disconnect trusted phone", secondary = true, onClick = onDisconnectSync)
        Text(
            "Disconnect removes future sync access. Imported settings remain encrypted on this TV.",
            color = TertiaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
    if (confirmClear) {
        StatusCard("Clear receiver setup?", "This removes the encrypted settings copy from this TV. It does not change your phone.", attention = true)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableAction(
                "Clear now",
                danger = true,
                focusRequester = clearConfirmationFocusRequester,
                onClick = onClearSetup,
            )
            FocusableAction("Cancel", secondary = true) { confirmClear = false }
        }
    } else {
        FocusableAction("Clear receiver setup", secondary = true) { confirmClear = true }
    }
}

@Composable
private fun ActionRow(
    onBeginPairing: () -> Unit,
    onImportFile: () -> Unit,
    pairingLabel: String = "Pair with iPhone",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FocusableAction(pairingLabel, onClick = onBeginPairing)
        FocusableAction("Import settings file", secondary = true, onClick = onImportFile)
    }
}

@Composable
private fun ReceiptRows(receipt: TVSettingsReceipt) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        receipt.categories.forEach { category ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Elevated2)
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(category.name, color = PrimaryText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${category.count} configured", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CategoryContent(
    title: String,
    fields: List<String>,
    configuredCount: Int,
    footer: String = "Values are hidden and stored inside the receiver's encrypted settings document.",
    onOpenPairing: () -> Unit,
) {
    StatusCard(
        if (configuredCount > 0) "$configuredCount configured" else "Waiting for setup",
        if (configuredCount > 0) "Imported from your approved 4789 settings transfer." else "Use Pair & Sync to copy this category from your phone.",
    )
    Spacer(Modifier.height(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { field ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Elevated2)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field, color = PrimaryText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("Update on iPhone", color = TertiaryText, fontSize = 13.sp)
            }
        }
    }
    Text(
        if (configuredCount > 0) {
            "$footer To change these values, update them on the iPhone and choose Update from iPhone."
        } else {
            footer
        },
        color = TertiaryText,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 16.dp),
    )
    Spacer(Modifier.height(16.dp))
    FocusableAction("Open Pair & Sync", secondary = true, onClick = onOpenPairing)
}

@Composable
private fun AboutContent(onClose: () -> Unit) {
    StatusCard("Ready for standalone phases", "The TV keeps an encrypted local copy, so the TV library and future catalog screens can use it without re-pairing or typing secrets.")
    Spacer(Modifier.height(14.dp))
    StatusCard("Copied from phone", "Addon URLs, account credentials, metadata keys and portable playback preferences.")
    Spacer(Modifier.height(14.dp))
    StatusCard("Kept device-local", "Downloads, notifications, app appearance, calendars, camera access and phone-only player routing are not copied.")
    Spacer(Modifier.height(16.dp))
    FocusableAction("Close settings", secondary = true, onClick = onClose)
}

@Composable
private fun PrivacyNote() {
    Text(
        "The QR carries a one-time invitation only. Settings cross your local network as AES-256-GCM ciphertext and are saved with Android Keystore protection.",
        color = TertiaryText,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(top = 18.dp),
    )
}

@Composable
private fun StatusCard(title: String, detail: String, attention: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (attention) TvTokens.Color.Error.copy(alpha = 0.16f) else Elevated2)
            .padding(horizontal = 20.dp, vertical = 17.dp),
    ) {
        Text(title, color = if (attention) TvTokens.Color.Error else PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = SecondaryText, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FocusableAction(
    label: String,
    secondary: Boolean = false,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill = when {
        focused && danger -> TvTokens.Color.Error
        focused -> Accent
        secondary -> Elevated2
        danger -> TvTokens.Color.Error.copy(alpha = 0.16f)
        else -> Accent.copy(alpha = 0.16f)
    }
    val foreground = when {
        focused -> Slate0
        danger -> TvTokens.Color.Error
        else -> PrimaryText
    }
    Box(
        modifier = modifier
            .heightIn(min = TvTokens.Geometry.PrimaryActionHeightDp.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .onFocusChanged { focused = it.hasFocus }
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 22.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun TVSettingsPairingState.receiptCount(name: String): Int {
    val receipt = when (this) {
        is TVSettingsPairingState.Saved -> receipt
        is TVSettingsPairingState.Staged -> receipt
        is TVSettingsPairingState.Saving -> receipt
        else -> null
    }
    return receipt?.categories?.firstOrNull { it.name == name }?.count ?: 0
}
