@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.TvDialog
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.search.RailEdge
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A choice list a page asked for. Drawn at the screen root, over everything (spec §16.8). */
internal data class PanelRequest(
    val header: String,
    val options: List<String>,
    val selected: String?,
    val onPick: (String) -> Unit,
)

/** The TV keyboard, used modally by "Add by URL" (spec §14.5 and §15.3). */
internal data class KeyboardRequest(
    val header: String,
    val hint: String,
    val initialText: String = "https://",
    val space: String = ".",
    val onDone: (String) -> Unit,
)

/** A PIN never goes through the general text keyboard or a persisted state holder. */
internal data class ProfilePinRequest(
    val header: String,
    val onDone: (CharArray) -> Unit,
)

/** A destructive settings action. The safe choice always receives initial focus. */
internal data class ConfirmRequest(
    val title: String,
    val body: String,
    val destructiveLabel: String,
    val onConfirm: () -> Unit,
)

/** Everything a page can ask the shell to do, so a page never owns an overlay itself. */
internal class PaneActions(
    val nav: ClientNav,
    val firstControl: FocusRequester,
    val autoFrameRate: () -> Boolean,
    val onAutoFrameRate: (Boolean) -> Unit,
    val onImportFile: () -> Unit,
    val onStremioImport: () -> Unit,
    val onChoice: (PanelRequest) -> Unit,
    val onKeyboard: (KeyboardRequest) -> Unit,
    val onProfilePin: (ProfilePinRequest) -> Unit,
    val onConfirm: (ConfirmRequest) -> Unit,
    val onLicences: () -> Unit,
    val onConfirmClear: () -> Unit,
)

/**
 * Settings, spec §14: the page list on the left, that page as a readout on the right.
 *
 * Walking the list teaches every page without a press (spec §14.13.1). RIGHT or OK enters the
 * pane; LEFT from the pane comes back to the list; LEFT from the list opens the rail.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun SettingsScreen(
    page: String,
    nav: ClientNav,
    autoFrameRate: () -> Boolean,
    onAutoFrameRate: (Boolean) -> Unit,
    onImportFile: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val client = remember(context) { context.clientGraph }
    val shell = LocalShellState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val noAddons = remember { MutableStateFlow(emptyList<Addon>()) }
    val services by client.services.collectAsState()
    val pairing by graph.pairing.state.collectAsState()
    val addons by (services?.registry?.addons ?: noAddons).collectAsState()
    val jobs by client.globalLibrary.jobs().collectAsState(initial = emptyList())

    var current by remember(page) { mutableStateOf(SettingsPage.fromSlug(page)) }
    var panel by remember { mutableStateOf<PanelRequest?>(null) }
    var keyboard by remember { mutableStateOf<KeyboardRequest?>(null) }
    var profilePin by remember { mutableStateOf<ProfilePinRequest?>(null) }
    var confirmation by remember { mutableStateOf<ConfirmRequest?>(null) }
    var licences by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var stremioImport by remember { mutableStateOf(false) }

    val listAnchor = remember { FocusRequester() }
    val paneAnchor = remember { FocusRequester() }
    DisposableEffect(Unit) {
        val restore = { runCatching { listAnchor.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    // Initial focus on the first left row — a node that is always composed (plan §7.4 rule 2).
    LaunchedEffect(Unit) { runCatching { listAnchor.requestFocus() } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) profilePin = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayUp = panel != null || keyboard != null || profilePin != null || confirmation != null || licences || confirmClear || stremioImport
    DisposableEffect(overlayUp) {
        shell.overlayVisible = overlayUp
        onDispose { shell.overlayVisible = false }
    }
    BackHandler(enabled = overlayUp) {
        panel = null
        keyboard = null
        profilePin = null
        confirmation = null
        licences = false
        confirmClear = false
        stremioImport = false
        runCatching { listAnchor.requestFocus() }
    }

    val dots = SettingsPageList.dots(
        paired = pairing is TVSettingsPairingState.Saved,
        addonsFailing = addons.count { !it.health.healthy },
        addonsSlow = addons.count { it.health.healthy && it.health.failStreak > 0 },
        jobsFailed = jobs.count { it.state == JobState.FAILED },
        keysMissing = services?.document?.let {
            it.torboxAPIKey == null && it.realDebridAPIKey == null
        } ?: true,
    )

    val actions = PaneActions(
        nav = nav,
        firstControl = paneAnchor,
        autoFrameRate = autoFrameRate,
        onAutoFrameRate = onAutoFrameRate,
        onImportFile = onImportFile,
        onStremioImport = { stremioImport = true },
        onChoice = { panel = it },
        onKeyboard = { keyboard = it },
        onProfilePin = { profilePin = it },
        onConfirm = { confirmation = it },
        onLicences = { licences = true },
        onConfirmClear = { confirmClear = true },
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    nav.openRail()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = TvGeom.ContentLeft,
                    top = TvGeom.SafeTop,
                    end = 96.dp,
                    bottom = TvGeom.SafeTop,
                ),
        ) {
            Text("Settings", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(
                "Browse with up and down. Press right to change a setting.",
                style = TvType.Meta,
                color = TvColor.TextSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxSize()) {
                RailEdge(onOpenRail = nav::openRail)
                Column(
                    // The scroll clips at its edge; 8 px inside it keeps the top row's exterior
                    // focus ring whole instead of cutting its top stroke off.
                    modifier = Modifier
                        .width(SettingsListWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsPage.entries.forEach { entry ->
                        PageRow(
                            page = entry,
                            dot = dots[entry] ?: PageDot.None,
                            selected = entry == current,
                            modifier = if (entry == SettingsPage.DEFAULT) {
                                Modifier.focusRequester(listAnchor)
                            } else {
                                Modifier
                            },
                            onFocused = { current = entry },
                            onEnter = { runCatching { paneAnchor.requestFocus() } },
                        )
                    }
                }
                Spacer(Modifier.width(32.dp))
                Box(
                    Modifier
                        .width(SettingsPaneWidth)
                        .fillMaxHeight()
                        .clip(TvShape.Panel)
                        .background(TvColor.Elevated)
                        .padding(SettingsPanePadding),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SettingsPane(page = current, actions = actions)
                    }
                }
            }
        }

        panel?.let { request ->
            ChoicePanel(
                header = request.header,
                options = request.options,
                selected = request.selected,
                onPick = {
                    request.onPick(it)
                    panel = null
                    runCatching { paneAnchor.requestFocus() }
                },
                onClose = {
                    panel = null
                    runCatching { paneAnchor.requestFocus() }
                },
            )
        }

        keyboard?.let { request ->
            ModalKeyboard(
                header = request.header,
                hint = request.hint,
                initialText = request.initialText,
                space = request.space,
                onDone = {
                    request.onDone(it)
                    keyboard = null
                    runCatching { paneAnchor.requestFocus() }
                },
                onCancel = {
                    keyboard = null
                    runCatching { paneAnchor.requestFocus() }
                },
            )
        }

        profilePin?.let { request ->
            key(request) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(TvColor.Canvas.copy(alpha = 0.82f))
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        .focusGroup(),
                    contentAlignment = Alignment.Center,
                ) {
                    com.fourseveneightnine.tv.client.ui.profiles.ProfilePinPanel(
                        title = request.header,
                        message = "Choose a 4-digit PIN.",
                        onSubmit = {
                            request.onDone(it)
                            profilePin = null
                            runCatching { paneAnchor.requestFocus() }
                        },
                        onCancel = {
                            profilePin = null
                            runCatching { paneAnchor.requestFocus() }
                        },
                    )
                }
            }
        }

        confirmation?.let { request ->
            TvDialog(
                title = request.title,
                body = request.body,
                safeLabel = "Cancel",
                destructiveLabel = request.destructiveLabel,
                onSafe = {
                    confirmation = null
                    runCatching { paneAnchor.requestFocus() }
                },
                onDestructive = {
                    confirmation = null
                    request.onConfirm()
                    runCatching { paneAnchor.requestFocus() }
                },
            )
        }

        if (licences) {
            LicencesPanel(onClose = {
                licences = false
                runCatching { paneAnchor.requestFocus() }
            })
        }

        if (confirmClear) {
            TvDialog(
                title = "Clear this TV's setup?",
                body = "This removes your add-ons and keys from this TV. Your iPhone keeps everything.",
                safeLabel = "Cancel",
                destructiveLabel = "Clear",
                onSafe = {
                    confirmClear = false
                    runCatching { paneAnchor.requestFocus() }
                },
                onDestructive = {
                    confirmClear = false
                    scope.launch { graph.pairing.clearReceiverSetup() }
                },
            )
        }
        if (stremioImport) {
            StremioImportPanel(onClose = {
                stremioImport = false
                runCatching { paneAnchor.requestFocus() }
            })
        }
    }
}

/**
 * One row of the page list, spec §14.2.
 *
 * RIGHT enters the pane, and so does OK. A pane with no control cannot be entered — the request
 * finds no node and focus stays where it is, which is the nudge the spec asks for.
 */
@Composable
private fun PageRow(
    page: SettingsPage,
    dot: PageDot,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onEnter: () -> Unit,
) {
    TvFocusable(
        onClick = onEnter,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        cornerRadius = 10.dp,
        focusScale = 1f,
    ) { focused ->
        Row(
            modifier = Modifier
                .width(SettingsListWidth)
                .height(84.dp)
                .clip(TvShape.Card)
                .background(if (focused || selected) TvColor.Elevated2 else TvColor.Elevated)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    page.title,
                    style = TvType.ControlLabel,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    page.blurb,
                    style = TvType.Meta,
                    color = TvColor.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dot != PageDot.None) {
                Spacer(Modifier.width(TvSpace.S))
                StatusDot(
                    color = if (dot == PageDot.Error) TvColor.Error else TvColor.Warning,
                    size = 12.dp,
                )
            }
        }
    }
}
