@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.search.SystemSearchPolicy
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvChip
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the results side is showing, spec §12.5. */
private sealed interface SearchPhase {
    /** No query yet: the invitation and the recent list. */
    data object Idle : SearchPhase

    /** No settings document, so no add-on can be asked. */
    data object NoAddons : SearchPhase
    data object Searching : SearchPhase
    data class Loaded(val query: String, val sections: List<SearchSection>) : SearchPhase
    data object Failed : SearchPhase
}

/** The Search entry retains its settled results while a title is above it. */
private class SearchMemory : ViewModel() {
    val phase = mutableStateOf<SearchPhase>(SearchPhase.Idle)
    var completedInputs: List<Any?>? = null
    var activeQuery by mutableStateOf<String?>(null)
    private var indexedShelves: List<Shelf>? = null
    private var indexTask: Deferred<List<SearchSections.IndexedTitle>>? = null

    fun indexFor(shelves: List<Shelf>): Deferred<List<SearchSections.IndexedTitle>> {
        if (indexedShelves !== shelves) {
            indexTask?.cancel()
            indexedShelves = shelves
            indexTask = viewModelScope.async(Dispatchers.Default) { SearchSections.indexCatalog(shelves) }
        }
        return checkNotNull(indexTask)
    }
}

/** At most this many catalogs are asked, so one query is never forty HTTP calls. */
private const val MAX_CATALOGS = 12
private const val PER_CATALOG_MILLIS = 6_000L

/** The poster rails stay clear of the keyboard. Editing uses the same live search pipeline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
    nav: ClientNav,
    initialQuery: String = "",
    initialQueryKey: Long = 0L,
    editOnEntry: Boolean = false,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val prefs = remember(context) { context.appGraph.presentationPreferences }
    val services by client.services.collectAsState()
    val shelves by client.snapshots.shelves().collectAsState()
    val iptv by client.iptv.state.collectAsState()
    val shell = LocalShellState.current

    var query by rememberSaveable { mutableStateOf(SystemSearchPolicy.boundedQuery(initialQuery).orEmpty()) }
    var appliedQueryKey by rememberSaveable { mutableStateOf(initialQueryKey) }
    var openedEntryEditor by rememberSaveable { mutableStateOf(false) }
    var submittedTerm by rememberSaveable { mutableStateOf(SystemSearchPolicy.boundedQuery(initialQuery)) }
    val memory: SearchMemory = viewModel { SearchMemory() }
    var phase by memory.phase
    LaunchedEffect(shelves) { memory.indexFor(shelves) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf<List<String>?>(null) }
    fun readRecents() = SearchSections.cleanRecents(
        SettingsKeys.readStringList(prefs, SettingsKeys.RECENT_SEARCHES),
    )
    LaunchedEffect(prefs) {
        val stored = withContext(Dispatchers.IO) { readRecents() }
        if (recents == null) recents = stored
    }
    fun keepRecent(term: String) {
        val existing = recents ?: readRecents()
        val next = SearchSections.recordRecent(existing, term)
        if (next == existing) return
        recents = next
        SettingsKeys.writeStringList(prefs, SettingsKeys.RECENT_SEARCHES, next)
    }

    var editing by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableIntStateOf(0) }
    var returnToField by remember { mutableStateOf(true) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query, TextRange(query.length)))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var keyboardWasShown by remember(editing, editorRequest) { mutableStateOf(false) }
    LaunchedEffect(editing, imeVisible) {
        if (editing && imeVisible) keyboardWasShown = true
    }
    // IME visibility controls input ownership only. It can change while dictation is committing.
    val keyboardActive = editing && (imeVisible || !keyboardWasShown)
    var focusFirstResult by remember { mutableStateOf(false) }
    var detailReturnKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openedResultKey by remember { mutableStateOf<String?>(null) }
    val lastResultKey = remember { arrayOfNulls<String>(1).also { it[0] = detailReturnKey } }
    fun changeQuery(value: String) {
        query = value.take(SystemSearchPolicy.MAX_QUERY_CHARACTERS)
        if (draft.text != query) draft = TextFieldValue(query, TextRange(query.length))
        submittedTerm = null
        detailReturnKey = null
        openedResultKey = null
        lastResultKey[0] = null
    }

    LaunchedEffect(initialQueryKey) {
        val incoming = SystemSearchPolicy.boundedQuery(initialQuery)
        if (appliedQueryKey != initialQueryKey) {
            changeQuery(incoming.orEmpty())
            submittedTerm = incoming
            editing = false
            keyboard?.hide()
            focusFirstResult = incoming != null
            appliedQueryKey = initialQueryKey
        }
    }

    val queryFocus = remember { FocusRequester() }
    val headerFocus = remember { FocusRequester() }
    val firstSuggestion = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val ownsInput = lifecycleState == Lifecycle.State.RESUMED
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                detailReturnKey?.let { openedResultKey = it }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(keyboardActive) {
        val restore = {
            if (keyboardActive) {
                queryFocus.requestFocus()
            } else {
                val lastKey = detailReturnKey ?: lastResultKey[0]
                if (lastKey != null) openedResultKey = lastKey
                else if (!headerFocus.requestFocus()) queryFocus.requestFocus()
            }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    if (keyboardActive) {
        DisposableEffect(shell) {
            shell.overlayVisible = true
            onDispose { if (!shell.playerVisible) shell.overlayVisible = false }
        }
    }
    BackHandler(enabled = keyboardActive) {
        focusFirstResult = false
        editing = false
        keyboard?.hide()
    }
    LaunchedEffect(ownsInput, editing, editorRequest) {
        if (!ownsInput) return@LaunchedEffect
        withFrameNanos { }
        if (editing) {
            queryFocus.requestFocus()
            keyboard?.show()
        } else {
            if (returnToField && openedResultKey == null && detailReturnKey == null) queryFocus.requestFocus()
            returnToField = true
        }
    }

    fun openEditor(value: String) {
        focusFirstResult = false
        if (query != value) changeQuery(value)
        draft = TextFieldValue(value, TextRange(value.length))
        editing = true
        editorRequest++
    }

    LaunchedEffect(editOnEntry, ownsInput) {
        if (editOnEntry && ownsInput && !openedEntryEditor) {
            openedEntryEditor = true
            openEditor(query)
        }
    }

    fun submit(value: String) {
        if (!SearchSections.shouldSearch(value)) return
        if (value.trim() != query.trim()) changeQuery(value)
        submittedTerm = value.trim()
        keepRecent(value)
        editing = false
        keyboard?.hide()
        focusFirstResult = true
    }

    val term = query.trim()
    val loaded = phase as? SearchPhase.Loaded
    val retained = remember(loaded, term) {
        if (loaded != null && loaded.query != term && SearchSections.shouldSearch(term)) {
            SearchSections.refineTitles(loaded.sections, term)
        } else emptyList()
    }
    LaunchedEffect(term, services, shelves, iptv.catalog, iptv.sources, iptv.accounts.hiddenGroups,
        iptv.accounts.lockedGroups, retryToken) {
        val inputs = listOf(term, services, shelves, iptv.catalog, iptv.sources,
            iptv.accounts.hiddenGroups, iptv.accounts.lockedGroups, retryToken)
        if (loaded?.query == term && memory.completedInputs == inputs) {
            return@LaunchedEffect
        }
        if (!SearchSections.shouldSearch(term)) {
            memory.activeQuery = null
            phase = SearchPhase.Idle
            return@LaunchedEffect
        }
        if (loaded == null) phase = SearchPhase.Searching
        memory.activeQuery = term
        val answered = SearchSections.progressiveResults(
            query = term,
            local = {
                if (shelves.none { it.items.isNotEmpty() }) null
                else {
                    val index = memory.indexFor(shelves).await()
                    withContext(Dispatchers.Default) { SearchSections.searchCatalog(index, term) }
                }
            },
            iptv = {
                val hasIptv = iptv.sources.any { it.enabled } &&
                    (iptv.catalog.channels.isNotEmpty() || iptv.catalog.vod.isNotEmpty() ||
                        iptv.catalog.vodCategories.isNotEmpty())
                if (hasIptv) withContext(Dispatchers.Default) { SearchSections.iptv(iptv, term) } else null
            },
            remote = flow {
                services?.let {
                    withTimeoutOrNull(SearchSections.DEBOUNCE_MILLIS) {
                        snapshotFlow { submittedTerm }.first { it == term }
                    }
                    emitAll(searchEveryCatalog(it, term).flowOn(Dispatchers.IO))
                }
            },
            retained = retained.flatMap { it.items },
            publish = { phase = SearchPhase.Loaded(term, it) },
        )
        if (!answered) phase = if (services == null) SearchPhase.NoAddons else SearchPhase.Failed
        if (answered) memory.completedInputs = inputs
    }

    val visiblePhase = when {
        !SearchSections.shouldSearch(term) -> SearchPhase.Idle
        loaded?.query == term -> loaded
        retained.isNotEmpty() -> SearchPhase.Loaded(term, retained)
        term != memory.activeQuery -> SearchPhase.Searching
        loaded != null -> SearchPhase.Searching
        else -> phase
    }
    val trending = remember(shelves) { SearchSections.trending(shelves) }
    val rows = remember(visiblePhase, term, trending) {
        when (visiblePhase) {
            SearchPhase.Idle -> trending
            is SearchPhase.Loaded -> SearchSections.resultRails(visiblePhase.sections, term)
            else -> emptyList()
        }
    }

    val showSuggestions = SearchSections.shouldSearch(term) && submittedTerm != term
    val suggestions = remember(showSuggestions, rows, term) {
        if (showSuggestions) SearchSections.titleSuggestions(rows, term)
        else emptyList()
    }
    fun pickSuggestion(result: SearchResult) {
        if (term != query.trim() || submittedTerm == term) return
        keepRecent(result.title)
        submittedTerm = term
        editing = false
        keyboard?.hide()
        focusFirstResult = false
        val row = rows.firstOrNull { result in it.items }
        detailReturnKey = row?.location(result)
        openSearchResult(nav, result)
    }

    LaunchedEffect(ownsInput, imeVisible, focusFirstResult, rows, visiblePhase, loaded?.query, detailReturnKey) {
        if (!ownsInput || imeVisible || !focusFirstResult || detailReturnKey != null) return@LaunchedEffect
        if (loaded != null && loaded.query != term) return@LaunchedEffect
        if (rows.isNotEmpty()) {
            withFrameNanos { }
            if (runCatching { firstResult.requestFocus() }.getOrDefault(false)) focusFirstResult = false
        } else if (visiblePhase !is SearchPhase.Searching) {
            focusFirstResult = false
        }
    }
    LaunchedEffect(ownsInput, imeVisible, openedResultKey, rows, visiblePhase) {
        val target = openedResultKey ?: return@LaunchedEffect
        if (ownsInput && !imeVisible && visiblePhase !is SearchPhase.Searching &&
            rows.none { row -> row.items.any { row.location(it) == target } }) {
            detailReturnKey = null
            openedResultKey = null
            lastResultKey[0] = null
            queryFocus.requestFocus()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onKeyEvent { event ->
        if (!keyboardActive && event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
            nav.openRail()
            true
        } else false
    }) {
        Row(Modifier.fillMaxSize().padding(start = TvGeom.ContentLeft, end = 96.dp, top = TvGeom.SafeTop)) {
            RailEdge(onOpenRail = {
                if (editing && !keyboardActive) {
                    returnToField = false
                    editing = false
                }
                nav.openRail()
            })
            Column(Modifier.fillMaxSize()) {
                Text("Search", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth().focusRequester(headerFocus)
                        .onFocusChanged { if (it.hasFocus) lastResultKey[0] = null }
                        .focusRestorer(queryFocus).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineSearchField(
                        value = draft,
                        modifier = Modifier.weight(1f).focusRequester(queryFocus),
                        editing = editing,
                        suggestions = suggestions,
                        suggestionFocus = firstSuggestion.takeIf { suggestions.isNotEmpty() },
                        onSuggestion = { if (editing) pickSuggestion(it) },
                        onChange = {
                            draft = if (it.text.length <= SystemSearchPolicy.MAX_QUERY_CHARACTERS) it
                            else it.copy(text = it.text.take(SystemSearchPolicy.MAX_QUERY_CHARACTERS))
                            if (query != draft.text) changeQuery(draft.text)
                        },
                        onSubmit = { submit(draft.text) },
                        onReopen = { openEditor(query) },
                        onNavigateAway = {
                            returnToField = false
                            editing = false
                            keyboard?.hide()
                        },
                        imeVisible = imeVisible,
                    )
                    TvButton(label = "Voice search", height = 52.dp, onClick = { openEditor("") })
                    if (query.isNotBlank()) {
                        TvButton(label = "Clear", height = 52.dp, onClick = {
                            focusFirstResult = false
                            if (editing) {
                                editing = false
                                keyboard?.hide()
                            } else queryFocus.requestFocus()
                            changeQuery("")
                        })
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (suggestions.isNotEmpty()) {
                    SearchSuggestions(
                        items = suggestions,
                        queryFocus = queryFocus,
                        firstSuggestion = firstSuggestion,
                        onFocused = {
                            if (editing) {
                                returnToField = false
                                editing = false
                                keyboard?.hide()
                            }
                        },
                        onPick = ::pickSuggestion,
                    )
                    Spacer(Modifier.height(18.dp))
                }
                ResultsBand(
                    phase = visiblePhase,
                    query = term,
                    sections = rows,
                    recents = recents.orEmpty(),
                    firstResult = firstResult,
                    returnKey = openedResultKey.takeIf { ownsInput },
                    keepTop = { detailReturnKey == null && lastResultKey[0] == null },
                    onFocused = { lastResultKey[0] = it },
                    onReturnFinished = {
                        detailReturnKey = null
                        openedResultKey = null
                    },
                    onOpen = { section, result ->
                        focusFirstResult = false
                        detailReturnKey = section.location(result)
                        keepRecent(term)
                        submittedTerm = term
                        editing = false
                        keyboard?.hide()
                        openSearchResult(nav, result)
                    },
                    onRecent = ::submit,
                    onRetry = { retryToken++ },
                    onPair = { nav.openSettings("pair") },
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun InlineSearchField(
    value: TextFieldValue,
    modifier: Modifier,
    editing: Boolean,
    suggestions: List<SearchResult>,
    suggestionFocus: FocusRequester?,
    onSuggestion: (SearchResult) -> Unit,
    onChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onReopen: () -> Unit,
    onNavigateAway: () -> Unit,
    imeVisible: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val input = remember(view) { view.context.getSystemService(InputMethodManager::class.java) }
    val currentSuggestions by rememberUpdatedState(suggestions)
    val currentOnSuggestion by rememberUpdatedState(onSuggestion)
    val acceptsCompletion by rememberUpdatedState(editing && focused)
    var connectionVersion by remember { mutableIntStateOf(0) }
    val completionDetails = suggestions.map { Triple(SearchSections.catalogDisplayKey(it), it.title, it.year) }
    val completions = remember(completionDetails) {
        suggestions.mapIndexed { index, result ->
            val kind = if (result.type == "movie" || result.type == "iptv-movie") "Movie" else "Series"
            val label = listOfNotNull(result.title, result.year?.toString(), kind).joinToString(" · ")
            CompletionInfo(completionDetails[index].first.hashCode().toLong(), index, result.title, label)
        }.toTypedArray()
    }
    val currentCompletions by rememberUpdatedState(completions)
    // Gboard clears its completions on text edits, including edits that keep every title in the list.
    LaunchedEffect(input, view, editing, focused, imeVisible, value.text, completions, connectionVersion) {
        if (editing && focused) {
            withFrameNanos { }
            input.displayCompletions(view, completions)
        } else input.displayCompletions(view, null)
    }
    DisposableEffect(input, view) {
        onDispose { input.displayCompletions(view, null) }
    }
    // Keep Compose editing/dictation; only add the platform's app-supplied completion protocol.
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                val connection = request.createInputConnection(info)
                info.inputType = (info.inputType and
                    (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).inv()) or
                    InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
                connectionVersion++
                object : InputConnectionWrapper(connection, false) {
                    override fun commitCompletion(completion: CompletionInfo?): Boolean {
                        if (!acceptsCompletion || completion == null) return false
                        val result = currentSuggestions.firstOrNull {
                            SearchSections.catalogDisplayKey(it).hashCode().toLong() == completion.id &&
                                it.title == completion.text?.toString()
                        } ?: return false
                        currentOnSuggestion(result)
                        return true
                    }
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        // TV Gboard commits the displayed label instead of commitCompletion.
                        val completion = currentCompletions.firstOrNull { it.label.toString() == text?.toString() }
                        if (completion != null) return commitCompletion(completion)
                        return super.commitText(text, newCursorPosition)
                    }
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            readOnly = !editing,
            modifier = modifier
                .onPreviewKeyEvent { event ->
                    if (imeVisible) return@onPreviewKeyEvent false
                    if (event.key == Key.DirectionCenter ||
                        (!editing && (event.key == Key.Enter || event.key == Key.NumPadEnter))
                    ) {
                        if (event.type == KeyEventType.KeyUp) onReopen()
                        return@onPreviewKeyEvent true
                    }
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val moved = (direction == FocusDirection.Down && suggestionFocus?.requestFocus() == true) ||
                        focusManager.moveFocus(direction)
                    if (moved && editing) onNavigateAway()
                    moved
                }
                .semantics {
                    contentDescription = when {
                        editing -> "Search query"
                        value.text.isBlank() -> "Search titles and channels"
                        else -> "Edit search, ${value.text}"
                    }
                    if (!editing) semanticOnClick(label = "Open keyboard") { onReopen(); true }
                }
                .onFocusChanged { focused = it.isFocused }
                .height(72.dp).clip(TvShape.Control).background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                .border(if (focused) 2.dp else 1.dp, if (focused) TvColor.Focus else Color.White.copy(alpha = 0.13f),
                    TvShape.Control)
                .padding(horizontal = 24.dp),
            singleLine = true,
            textStyle = TvType.data(30).copy(color = TvColor.TextPrimary),
            cursorBrush = SolidColor(TvColor.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            if (editing) "Hold the remote mic button or type a title" else "Search titles, channels, and programs",
                            style = TvType.data(30),
                            color = TvColor.TextSecondary,
                        )
                    }
                    field()
                }
            },
        )
    }
}

private fun openSearchResult(nav: ClientNav, result: SearchResult) {
    when (result.type) {
        "channel" -> nav.openLiveItem("channel", result.id)
        "iptv-program" -> nav.openLiveItem("channel", result.id.substringBeforeLast(':'))
        "iptv-channel-category" -> nav.openLiveItem("channel-group", result.id)
        "iptv-movie-category" -> nav.openLiveItem("movie-group", result.id)
        "iptv-series-category" -> nav.openLiveItem("series-group", result.id)
        "iptv-movie", "iptv-series" -> nav.openLiveItem("vod", result.id)
        else -> {
            DetailPreview.stage(HomeCard(result.id, result.type, result.title, posterUrl = result.posterURL, year = result.year))
            nav.openDetail(result.type, result.id)
        }
    }
}

private fun SearchSection.cardKey(result: SearchResult): String =
    if (type == "movie" || type == "series") SearchSections.catalogDisplayKey(result) else result.key

private fun SearchSection.location(result: SearchResult): String = "$type|${cardKey(result)}"

@Composable
private fun SearchSuggestions(
    items: List<SearchResult>,
    queryFocus: FocusRequester,
    firstSuggestion: FocusRequester,
    onFocused: () -> Unit,
    onPick: (SearchResult) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Suggestions", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count = items.size, key = { SearchSections.catalogDisplayKey(items[it]) }) { index ->
                val result = items[index]
                val label = listOfNotNull(result.title, result.year?.toString()).joinToString(" · ")
                SidePanelRow(
                    label = label,
                    selected = false,
                    width = 344.dp,
                    height = 58.dp,
                    modifier = (if (index == 0) Modifier.focusRequester(firstSuggestion) else Modifier)
                        .focusProperties { up = queryFocus }
                        .onFocusChanged { if (it.isFocused) onFocused() },
                    onClick = { onPick(result) },
                )
            }
        }
    }
}

/** The TV speech activity owns microphone input and sends the final text back here. */
@Composable
internal fun VoiceSearchButton(
    prompt: String,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
) {
    val context = LocalContext.current
    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstNotNullOfOrNull(SystemSearchPolicy::boundedQuery)
            if (query != null) onQuery(query)
            else Toast.makeText(context, "No speech recognized. Try again or type.", Toast.LENGTH_SHORT).show()
        }
    }
    TvButton(label = "Voice search", modifier = modifier, height = 52.dp, onClick = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // Google TV can expose two handlers. Use its TV-specific activity to avoid a chooser.
        val launched = listOf("com.google.android.katniss", null).any { provider ->
            try {
                recognizer.launch(Intent(intent).setPackage(provider))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (!launched) {
            Toast.makeText(context, "Voice search is unavailable. Use the keyboard.", Toast.LENGTH_SHORT).show()
        }
    })
}

/** Spec §12.2. A readout; it never takes focus, and the caret does not blink. */
@Composable
private fun ResultsBand(
    phase: SearchPhase,
    query: String,
    sections: List<SearchSection>,
    recents: List<String>,
    firstResult: FocusRequester,
    returnKey: String?,
    keepTop: () -> Boolean,
    onFocused: (String) -> Unit,
    onReturnFinished: () -> Unit,
    onOpen: (SearchSection, SearchResult) -> Unit,
    onRecent: (String) -> Unit,
    onRetry: () -> Unit,
    onPair: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val posterWidth = with(LocalDensity.current) {
            val available = maxWidth.roundToPx() - SEARCH_POSTER_GAP.roundToPx() * (SEARCH_POSTER_COUNT - 1)
            (available / SEARCH_POSTER_COUNT).toDp()
        }
        when (phase) {
            SearchPhase.Idle -> Column(Modifier.fillMaxSize()) {
                if (recents.isNotEmpty()) {
                    Text("Recent searches", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recents, key = { it }) { term ->
                            TvChip(label = term.take(32), onClick = { onRecent(term) })
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                }
                Sections(sections, posterWidth, firstResult, returnKey, keepTop, onFocused,
                    onReturnFinished, onOpen, modifier = Modifier.weight(1f))
            }
            SearchPhase.Searching -> Column {
                Text("Searching…", style = TvType.ShelfHeader, color = TvColor.TextSecondary)
                Spacer(Modifier.height(TvSpace.S))
                Row(horizontalArrangement = Arrangement.spacedBy(SEARCH_POSTER_GAP)) {
                    repeat(SEARCH_POSTER_COUNT) { index ->
                        Skeleton(Modifier.size(posterWidth, posterWidth * 1.5f), sweep = index == 0)
                    }
                }
            }
            SearchPhase.NoAddons -> EmptyState(
                headline = "Nothing to search yet",
                line = "Pair your iPhone to sync lists, or add a search-capable add-on.",
                actionLabel = "Open Pair and Sync",
                onAction = onPair,
            )
            SearchPhase.Failed -> StateBlock(
                headline = "Couldn't search right now",
                line = "Check the TV's network, then try again.",
                actionLabel = "Retry",
                onAction = onRetry,
                announceAsError = true,
            )
            is SearchPhase.Loaded -> if (sections.isEmpty()) {
                EmptyState(headline = "No results for $query", line = "Check the spelling, or try fewer words.")
            } else {
                Sections(sections, posterWidth, firstResult, returnKey, keepTop, onFocused,
                    onReturnFinished, onOpen, count = SearchSections.countLabel(sections))
            }
        }
    }
}

private const val SEARCH_POSTER_COUNT = 7
private val SEARCH_POSTER_GAP = 20.dp

@Composable
private fun Sections(
    sections: List<SearchSection>,
    posterWidth: Dp,
    firstResult: FocusRequester,
    returnKey: String?,
    keepTop: () -> Boolean,
    onFocused: (String) -> Unit,
    onReturnFinished: () -> Unit,
    onOpen: (SearchSection, SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
) {
    val listState = rememberLazyListState()
    val leadingRail = sections.firstOrNull()?.type
    LaunchedEffect(leadingRail) {
        // A fast Series or IPTV answer can gain a Movies rail above it on the next update.
        if (leadingRail != null && returnKey == null && keepTop()) listState.scrollToItem(0)
    }
    LaunchedEffect(returnKey, sections) {
        if (returnKey == null) return@LaunchedEffect
        val index = sections.indexOfFirst { row -> row.items.any { row.location(it) == returnKey } }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == sections[index].type }) {
            listState.scrollToItem(index)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 64.dp),
    ) {
        items(count = sections.size, key = { sections[it].type }, contentType = { "result_rail" }) { index ->
            val section = sections[index]
            val rowState = rememberLazyListState()
            val entryFocus = remember { FocusRequester() }
            LaunchedEffect(returnKey, section.items) {
                val cardIndex = section.items.indexOfFirst { section.location(it) == returnKey }
                if (returnKey == null || cardIndex < 0) return@LaunchedEffect
                val cardKey = section.cardKey(section.items[cardIndex])
                if (rowState.layoutInfo.visibleItemsInfo.none { it.key == cardKey }) rowState.scrollToItem(cardIndex)
                snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == cardKey } }.first { it }
                repeat(12) {
                    withFrameNanos { }
                    if (runCatching { entryFocus.requestFocus() }.getOrDefault(false)) {
                        onReturnFinished()
                        return@LaunchedEffect
                    }
                }
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, style = TvType.ShelfHeader, color = TvColor.TextPrimary, maxLines = 1)
                    if (index == 0 && count != null) {
                        Text(count, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    state = rowState,
                    modifier = Modifier.fillMaxWidth().focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(SEARCH_POSTER_GAP),
                ) {
                    items(
                        count = section.items.size,
                        key = { section.cardKey(section.items[it]) },
                        contentType = { section.items[it].type },
                    ) { cardIndex ->
                        val result = section.items[cardIndex]
                        val location = section.location(result)
                        val focus = when {
                            location == returnKey -> Modifier.focusRequester(entryFocus)
                            index == 0 && cardIndex == 0 -> Modifier.focusRequester(firstResult)
                            else -> Modifier
                        }
                        ResultCard(result, posterWidth, { onOpen(section, result) }, { onFocused(location) }, focus)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: SearchResult,
    posterWidth: Dp,
    onOpen: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = result.type.endsWith("-category")
    val broadcast = result.type == "channel" || result.type == "iptv-program"
    val height = posterWidth * if (category || broadcast) 0.56f else 1.5f
    val action = when { category -> "Browse category"; broadcast -> "Watch channel"; else -> "Open details" }
    Column(Modifier.width(posterWidth)) {
        TvFocusable(
            onClick = onOpen,
            modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
            accessibleLabel = accessibilityLabel(result.title, result.year?.toString(), SearchSections.label(result.type)),
            clickLabel = action,
            cornerRadius = 12.dp,
            focusScale = 1f,
        ) { _ ->
            if (category) {
                Box(Modifier.size(posterWidth, height).clip(TvShape.Card).background(TvColor.Elevated),
                    contentAlignment = Alignment.CenterStart) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Browse", style = TvType.Meta, color = TvColor.Selection)
                        val label = when (result.type) {
                            "iptv-channel-category" -> "Channels"
                            "iptv-movie-category" -> "Movies"
                            else -> "Series"
                        }
                        Text(label, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
                    }
                }
            } else {
                val requestWidth = with(LocalDensity.current) { posterWidth.roundToPx() }
                TvArtwork(
                    request = remember(result.posterURL, requestWidth) { PosterRequest.poster(result.posterURL, requestWidth) },
                    title = result.title,
                    modifier = Modifier.size(posterWidth, height).then(if (broadcast && result.posterURL != null) Modifier.padding(14.dp) else Modifier),
                    contentScale = if (broadcast) ContentScale.Fit else ContentScale.Crop,
                    posterLookupId = result.id.takeUnless { broadcast || result.type.startsWith("iptv-") },
                    posterLookupType = result.type,
                )
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(result.title, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        val typeLabel = when (result.type) {
        "movie", "iptv-movie" -> "Movie"
        "series", "iptv-series" -> "Series"
        else -> SearchSections.label(result.type)
    }
    val metadata = listOfNotNull(result.year?.toString(), typeLabel).joinToString(" · ")
        Text(metadata, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
    }
}

/**
 * The 2 px strip that makes LEFT mean "open the rail" at the left edge of a screen with columns.
 *
 * The edge opens the overlay on a Left transfer. Compose can still move focus between
 * controls in the keyboard, grid and Settings, and default focus has no menu side effect.
 */
@Composable
internal fun RailEdge(onOpenRail: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(2.dp)
            .fillMaxHeight()
            .focusProperties {
                onEnter = {
                    cancelFocusChange()
                    if (requestedFocusDirection == FocusDirection.Left) onOpenRail()
                }
            }
            .focusGroup()
            .focusable(),
    )
}

/** Each searchable catalog contributes as soon as it answers. The index keeps merge ties stable. */
private fun searchEveryCatalog(
    services: ClientGraph.DataServices,
    query: String,
): Flow<IndexedValue<List<DiscoverItem>>> = channelFlow {
    services.registry.catalogs()
        .filter { (_, catalog) ->
            catalog.extraSupported.any { it.equals(SearchSections.SEARCH_EXTRA, ignoreCase = true) }
        }
        .take(MAX_CATALOGS)
        .forEachIndexed { index, (addon, catalog) ->
            launch {
                val items = withTimeoutOrNull(PER_CATALOG_MILLIS) {
                    try {
                        services.client.catalog(
                            addon = addon,
                            type = catalog.type,
                            id = catalog.id,
                            extra = CatalogExtra(search = query),
                        ).items
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                if (items != null) send(IndexedValue(index, items))
            }
        }
}
