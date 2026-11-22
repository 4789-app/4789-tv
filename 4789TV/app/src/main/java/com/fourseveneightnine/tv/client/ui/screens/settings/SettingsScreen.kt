@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.settings

import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ContentTransition
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.live.LiveTvLaunch
import com.fourseveneightnine.tv.client.ui.screens.search.RailEdge
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** A choice list a page asked for. Drawn at the screen root, over everything (spec §16.8). */
internal data class PanelRequest(
    val header: String,
    val options: List<String>,
    val selected: String?,
    val description: String? = null,
    val onPick: (String) -> Unit,
)

/** The TV keyboard for short, non-secret settings such as profile names. */
internal data class KeyboardRequest(
    val header: String,
    val hint: String,
    val initialText: String = "",
    val usernameInput: Boolean = false,
    val secret: Boolean = false,
    val onDone: (String) -> Unit,
)

/** A PIN never goes through the general text keyboard or a persisted state holder. */
internal data class ProfilePinRequest(
    val header: String,
    val message: String = "Choose a 4-digit PIN.",
    val onDone: (CharArray) -> Unit,
)

/** A destructive settings action. The safe choice always receives initial focus. */
internal data class ConfirmRequest(
    val title: String,
    val body: String,
    val destructiveLabel: String,
    val destructive: Boolean = true,
    val onConfirm: () -> Unit,
)

/** Everything a page can ask the shell to do, so a page never owns an overlay itself. */
internal class PaneActions(
    val nav: ClientNav,
    val firstControl: FocusRequester,
    val autoFrameRate: () -> Boolean,
    val onAutoFrameRate: (Boolean) -> Unit,
    val onPhoneSync: () -> Unit,
    val onStremioImport: () -> Unit,
    val onAddonLink: () -> Unit,
    val onLetterboxdLink: () -> Unit,
    val onAddonDetails: (String, FocusRequester) -> Unit,
    val onChoice: (PanelRequest) -> Unit,
    val onKeyboard: (KeyboardRequest) -> Unit,
    val onProfilePin: (ProfilePinRequest) -> Unit,
    val onConfirm: (ConfirmRequest) -> Unit,
    val onLicences: () -> Unit,
    val onReadout: (String, List<Pair<String, String>>) -> Unit,
    val onCatalogReadout: (String, List<Pair<String, String>>) -> Unit,
    val onSetUpLiveTv: () -> Unit,
    val welcome: Boolean,
    val onRemoteButtons: () -> Unit,
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
    welcome: Boolean = false,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val shell = LocalShellState.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val noAddons = remember { MutableStateFlow(emptyList<Addon>()) }
    val services by client.services.collectAsState()
    val addons by (services?.registry?.addons ?: noAddons).collectAsState()
    val jobs by client.globalLibrary.jobs().collectAsState(initial = emptyList())

    var current by rememberSaveable(page) { mutableStateOf(SettingsPage.fromSlug(page)) }
    var displayedPage by remember(page) { mutableStateOf(current) }
    val reduceMotion = LocalReduceMotion.current
    // Coalesce category previews; entering a pane bypasses this rest below.
    LaunchedEffect(current, reduceMotion) {
        if (!reduceMotion) delay(TvMotion.HeroSettleMillis)
        displayedPage = current
    }
    // Retention is bounded by SettingsPage.entries and ends on leaving this screen.
    // Only the active page is placed; each retained page owns its controls and focus history.
    val visitedPages = remember { mutableSetOf<SettingsPage>() }
    SideEffect { visitedPages.add(displayedPage) }
    var panel by remember { mutableStateOf<PanelRequest?>(null) }
    var keyboard by remember { mutableStateOf<KeyboardRequest?>(null) }
    var profilePin by remember { mutableStateOf<ProfilePinRequest?>(null) }
    var confirmation by remember { mutableStateOf<ConfirmRequest?>(null) }
    var licences by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf<Triple<String, List<Pair<String, String>>, Boolean>?>(null) }
    var addonLink by remember { mutableStateOf(false) }
    var letterboxdLink by remember { mutableStateOf(false) }
    var addonDetails by remember { mutableStateOf<Pair<String, FocusRequester>?>(null) }
    var stremioImport by remember { mutableStateOf(false) }
    var remoteButtons by remember { mutableStateOf(false) }
    var phoneSyncFrom by remember { mutableStateOf<SettingsPage?>(null) }

    val listAnchor = remember { FocusRequester() }
    var paneFocused by remember { mutableStateOf(false) }
    val paneAnchors = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    val paneGroups = remember { SettingsPage.entries.associateWith { FocusRequester() } }
    var restorePending by remember { mutableStateOf(false) }
    var restoreControl by remember { mutableStateOf<FocusRequester?>(null) }
    fun rememberPaneFocus() { paneGroups.getValue(current).saveFocusedChild() }
    fun restorePaneFocus(control: FocusRequester? = null) {
        displayedPage = current
        restoreControl = control
        restorePending = true
    }
    DisposableEffect(Unit) {
        val restore = { runCatching { listAnchor.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    LaunchedEffect(Unit) {
        if (welcome) restorePaneFocus() else listAnchor.requestFocus()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) profilePin = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayUp = panel != null || keyboard != null || profilePin != null || confirmation != null ||
        licences || readout != null || stremioImport || addonLink || addonDetails != null ||
        remoteButtons || letterboxdLink || phoneSyncFrom != null
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(restorePending, overlayUp, windowFocused, current, displayedPage) {
        if (!restorePending || overlayUp || !windowFocused || current != displayedPage) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        restorePending = false
        val control = restoreControl
        restoreControl = null
        if (control != null) {
            if (!runCatching { control.requestFocus() }.getOrDefault(false)) {
                paneAnchors.getValue(current).requestFocus()
            }
            return@LaunchedEffect
        }
        val group = paneGroups.getValue(current)
        if (!group.restoreFocusedChild()) group.requestFocus()
    }
    DisposableEffect(overlayUp) {
        shell.overlayVisible = overlayUp
        onDispose { shell.overlayVisible = false }
    }
    BackHandler(enabled = !overlayUp && paneFocused) { listAnchor.requestFocus() }
    BackHandler(enabled = overlayUp && phoneSyncFrom == null) {
        val opener = addonDetails?.second
        panel = null
        keyboard = null
        profilePin = null
        confirmation = null
        licences = false
        readout = null
        stremioImport = false
        addonLink = false
        letterboxdLink = false
        addonDetails = null
        remoteButtons = false
        restorePaneFocus(opener)
    }

    val dots = SettingsPageList.dots(
        addonsFailing = addons.count { it.enabled && !it.health.healthy },
        addonsSlow = addons.count { it.enabled && it.health.healthy && it.health.failStreak > 0 },
        jobsFailed = jobs.count { it.state == JobState.FAILED },
    )

    val pageActions = remember(nav, autoFrameRate, onAutoFrameRate, welcome) {
        SettingsPage.entries.associateWith { entry ->
            PaneActions(
                nav = nav,
                firstControl = paneAnchors.getValue(entry),
                autoFrameRate = autoFrameRate,
                onAutoFrameRate = onAutoFrameRate,
                onPhoneSync = { rememberPaneFocus(); phoneSyncFrom = entry },
                onAddonLink = { rememberPaneFocus(); addonLink = true },
                onLetterboxdLink = { rememberPaneFocus(); letterboxdLink = true },
                onAddonDetails = { url, opener -> rememberPaneFocus(); addonDetails = url to opener },
                onStremioImport = { rememberPaneFocus(); stremioImport = true },
                onChoice = { rememberPaneFocus(); panel = it },
                onKeyboard = { rememberPaneFocus(); keyboard = it },
                onProfilePin = { rememberPaneFocus(); profilePin = it },
                onConfirm = { rememberPaneFocus(); confirmation = it },
                onLicences = { rememberPaneFocus(); licences = true },
                onReadout = { title, values -> rememberPaneFocus(); readout = Triple(title, values, true) },
                onCatalogReadout = { title, values -> rememberPaneFocus(); readout = Triple(title, values, false) },
                onSetUpLiveTv = { nav.openLiveItem(LiveTvLaunch.SOURCE_SETUP, "") },
                onRemoteButtons = { rememberPaneFocus(); remoteButtons = true },
                welcome = welcome,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(TvColor.Background, Color(0xFF101722))))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    nav.openRail()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            Modifier.fillMaxSize()
                .padding(start = TvGeom.ContentLeft, top = 60.dp, end = 96.dp, bottom = TvGeom.SafeTop),
        ) {
            RailEdge(onOpenRail = nav::openRail)
            Column(Modifier.width(SettingsListWidth).fillMaxHeight()) {
                Text("Settings", style = TvType.ScreenTitle, color = TvColor.TextPrimary,
                    modifier = Modifier.padding(start = 12.dp).semantics { heading() })
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsPage.entries.forEach { entry ->
                        when (entry) {
                            SettingsPage.Pair -> CategoryHeading("Connections")
                            SettingsPage.Playback -> CategoryHeading("Preferences")
                            else -> Unit
                        }
                        PageRow(
                            page = entry,
                            dot = dots[entry] ?: PageDot.None,
                            selected = entry == current,
                            modifier = if (entry == current) Modifier.focusRequester(listAnchor) else Modifier,
                            onFocused = { current = entry },
                            onEnter = { restorePaneFocus() },
                        )
                    }
                }
                Text(
                    if (paneFocused) "←  Categories" else "→  Open category",
                    style = TvType.Meta,
                    color = TvColor.TextSecondary,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.width(34.dp))
            ContentTransition(
                targetKey = displayedPage,
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clip(TvShape.Panel)
                    .background(SettingsSurface)
                    .border(1.dp, TvColor.Border, TvShape.Panel)
                    .padding(horizontal = SettingsPanePadding, vertical = 22.dp),
            ) {
                SettingsPage.entries.forEach { entry ->
                    if (entry == displayedPage || entry in visitedPages) key(entry) {
                        val active = entry == displayedPage
                        val placement = remember(entry) {
                            Modifier.layout { measurable, constraints ->
                                val content = measurable.measure(constraints)
                                layout(content.width, content.height) {
                                    if (entry == displayedPage) content.placeRelative(0, 0)
                                }
                            }
                        }
                        Box(
                            placement
                                .then(if (active) Modifier else Modifier.clearAndSetSemantics { }),
                        ) {
                            Column(
                                Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    // Restore actual controls, inside the scroll container's focus target.
                                    .focusRequester(paneGroups.getValue(entry))
                                    .onFocusChanged { if (active) paneFocused = it.hasFocus }
                                    .focusProperties {
                                        onExit = {
                                            paneGroups.getValue(entry).saveFocusedChild()
                                            if (requestedFocusDirection == FocusDirection.Left) listAnchor.requestFocus()
                                        }
                                    }
                                    .focusRestorer(paneAnchors.getValue(entry))
                                    .focusGroup()
                                    .padding(horizontal = 6.dp, vertical = 12.dp)
                                    .graphicsLayer(),
                            ) {
                                SettingsHeading(entry, if (welcome && entry == SettingsPage.Pair) "Set up 4789" else entry.title)
                                SettingsPane(page = entry, actions = pageActions.getValue(entry))
                            }
                        }
                    }
                }
            }
        }

        phoneSyncFrom?.let { entry ->
            PhoneSyncPanel(forKeys = entry == SettingsPage.Accounts, onClose = {
                phoneSyncFrom = null
                restorePaneFocus()
            })
        }

        panel?.let { request ->
            ChoicePanel(
                header = request.header,
                options = request.options,
                selected = request.selected,
                description = request.description,
                onPick = {
                    panel = null
                    request.onPick(it)
                    restorePaneFocus()
                },
                onClose = {
                    panel = null
                    restorePaneFocus()
                },
            )
        }

        if (remoteButtons) RemoteButtonsPanel(onClose = {
            remoteButtons = false
            restorePaneFocus()
        })

        keyboard?.let { request ->
            ModalKeyboard(
                header = request.header,
                hint = request.hint,
                initialText = request.initialText,
                secret = request.secret,
                usernameInput = request.usernameInput,
                onDone = {
                    keyboard = null
                    request.onDone(it)
                    restorePaneFocus()
                },
                onCancel = {
                    keyboard = null
                    restorePaneFocus()
                },
            )
        }

        profilePin?.let { request ->
            key(request) {
                val cancel = {
                    profilePin = null
                    restorePaneFocus()
                }
                SettingsModal(request.header, cancel, width = 648.dp) {
                    com.fourseveneightnine.tv.client.ui.profiles.ProfilePinPanel(
                        title = null,
                        message = request.message,
                        onSubmit = {
                            profilePin = null
                            request.onDone(it)
                            restorePaneFocus()
                        },
                        onCancel = cancel,
                    )
                }
            }
        }

        confirmation?.let { request ->
            SettingsConfirmation(
                request = request,
                onCancel = {
                    confirmation = null
                    restorePaneFocus()
                },
                onConfirm = {
                    confirmation = null
                    request.onConfirm()
                    restorePaneFocus()
                },
            )
        }

        readout?.let { (title, values, playback) ->
            val close = {
                readout = null
                restorePaneFocus()
            }
            val done = remember { FocusRequester() }
            SettingsModal(title, close) {
                var eventPage by remember(title, values) { mutableIntStateOf(0) }
                if (playback) {
                    Text("Change these rules in 4789 on your iPhone, then sync them in Setup.",
                        style = TvType.Body, color = TvColor.TextSecondary)
                    Spacer(Modifier.height(TvSpace.S))
                }
                val pageSize = if (playback) values.size.coerceAtLeast(1) else 5
                val start = eventPage * pageSize
                val end = minOf(start + pageSize, values.size)
                if (values.size > pageSize) {
                    Text("${start + 1}–$end of ${values.size}", style = TvType.Meta, color = TvColor.TextSecondary)
                }
                values.subList(start, end).forEach { (label, value) -> ReadoutRow(label, value, height = 60.dp) }
                Spacer(Modifier.height(TvSpace.M))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                    if (values.size > pageSize) {
                        SettingsDialogButton("Previous", { if (eventPage > 0) eventPage-- }, enabled = eventPage > 0)
                        SettingsDialogButton("Next", { if (end < values.size) eventPage++ }, enabled = end < values.size)
                    }
                    SettingsDialogButton("Done", close, Modifier.focusRequester(done))
                }
                LaunchedEffect(Unit) { done.requestFocus() }
            }
        }

        PanelTransition(visible = licences) {
            LicencesPanel(onClose = {
                licences = false
                restorePaneFocus()
            })
        }

        if (letterboxdLink) {
            LetterboxdImportPanel(onClose = { letterboxdLink = false; restorePaneFocus() })
        }
        if (addonLink) {
            AddonLinkPanel(
                onClose = { addonLink = false; restorePaneFocus() },
                onStremioImport = { addonLink = false; stremioImport = true },
            )
        }
        addonDetails?.let { (url, opener) ->
            AddonDetailsPanel(url, onClose = { addonDetails = null; restorePaneFocus(opener) })
        }
        if (stremioImport) {
            StremioImportPanel(onClose = {
                stremioImport = false
                restorePaneFocus()
            })
        }
    }
}

@Composable
private fun CategoryHeading(label: String) {
    Text(label, style = TvType.Meta, color = TvColor.TextSecondary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp).semantics { heading() })
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
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onEnter()
                    true
                } else false
            },
        accessibleRole = Role.Tab,
        selected = selected,
        stateDescription = when (dot) {
            PageDot.Error -> "Action needed"
            PageDot.Warning -> "Check status"
            PageDot.None -> null
        },
        cornerRadius = 16.dp,
        focusScale = 1f,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .clip(TvShape.CardProminent)
                .background(when {
                    focused -> SettingsFocusFill
                    selected -> page.tint.copy(alpha = 0.12f)
                    else -> Color.Transparent
                })
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsGlyph(page.icon, page.tint, 42.dp)
            Column(Modifier.weight(1f)) {
                Text(page.title, style = TvType.ControlLabel, color = TvColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (dot != PageDot.None) {
                Box(Modifier.size(10.dp).clip(TvShape.Chip)
                    .background(if (dot == PageDot.Error) TvColor.Error else TvColor.Warning))
            } else if (selected) {
                Text("›", style = TvType.ControlLabel, color = page.tint,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
