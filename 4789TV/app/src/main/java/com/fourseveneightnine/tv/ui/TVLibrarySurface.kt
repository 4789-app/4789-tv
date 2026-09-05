package com.fourseveneightnine.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogState
import com.fourseveneightnine.tv.catalog.TVTMDBCatalogState
import com.fourseveneightnine.tv.catalog.TVAddonPlayableSource
import com.fourseveneightnine.tv.catalog.TVAddonEpisode
import com.fourseveneightnine.tv.catalog.TVAddonSourceState
import com.fourseveneightnine.tv.catalog.sourceKey
import com.fourseveneightnine.tv.catalog.visibleSnapshot
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.ui.settings.TVSettingsDestination
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

private val LibraryBlack = TvTokens.Color.Black
private val LibrarySlate = TvTokens.Color.Slate
private val LibraryElevated = TvTokens.Color.Elevated
private val LibraryElevatedFocused = TvTokens.Color.ElevatedFocused
private val LibraryAccent = TvTokens.Color.BrandOrange
private val LibraryText = TvTokens.Color.Text
private val LibrarySecondary = TvTokens.Color.Secondary
private val LibraryTertiary = TvTokens.Color.Tertiary
private val LibraryDisplayFont = TvTokens.Type.Display
private val LibraryUIFont = TvTokens.Type.UI
private val LibraryDataFont = TvTokens.Type.Data

// Focus changes are keyboard-like TV input. Keep them immediate; the settled card and rail are
// lightweight enough that a debounce only makes the interface feel like it is lagging behind the
// remote.
private val FOCUS_PREVIEW_SETTLE_MILLIS = TvTokens.Motion.HeroSettleMillis

/** A pending shelf preview must survive a recents refresh without retaining a stale item object. */
private data class RecentFocusTarget(
    val dedupeKey: String,
    val canonicalKey: String,
)

/** Keep D-pad relocation deterministic; animated bring-into-view spends frames chasing the remote. */
private object ImmediateBringIntoViewSpec : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()
}

/**
 * Focus transitions arrive as two callbacks (old card leaves, new card enters). Keeping one
 * mutable focus state per card lets the enter callback clear the old card and paint the new one in
 * the same snapshot, so a D-pad press schedules one frame instead of two.
 */
private class FocusVisualRegistry {
    private val states = mutableMapOf<Any, androidx.compose.runtime.MutableState<Boolean>>()

    fun stateFor(key: Any): State<Boolean> = states.getOrPut(key) { mutableStateOf(false) }

    fun moveTo(key: Any) {
        states.forEach { (registeredKey, state) ->
            state.value = registeredKey == key
        }
    }
}

/** Pure layer policy so rapid Settings/playback transitions can be sequence-tested off device. */
internal object TVLibrarySurfacePolicy {
    fun shouldShow(
        startupReady: Boolean,
        homePhase: Boolean,
        settingsVisible: Boolean,
        settingsConfigured: Boolean,
    ): Boolean = startupReady && homePhase && !settingsVisible && settingsConfigured

    fun shouldShowPairing(
        startupReady: Boolean,
        homePhase: Boolean,
        settingsVisible: Boolean,
        settingsConfigured: Boolean,
    ): Boolean = startupReady && homePhase && !settingsVisible && !settingsConfigured

    fun blocksReceiverHome(libraryVisible: Boolean, settingsVisible: Boolean): Boolean =
        libraryVisible || settingsVisible

    /** Keep the settled hero on the same title when a phone refresh enriches its metadata. */
    fun refreshedFocusIndex(
        previousDedupeKey: String?,
        previousCanonicalKey: String?,
        dedupeKeys: List<String>,
        canonicalKeys: List<String>,
    ): Int {
        previousDedupeKey?.let { key ->
            dedupeKeys.indexOf(key).takeIf { it >= 0 }?.let { return it }
        }
        previousCanonicalKey?.let { key ->
            canonicalKeys.indexOf(key).takeIf { it >= 0 }?.let { return it }
        }
        return if (dedupeKeys.isEmpty()) -1 else 0
    }

    fun settingsDestination(
        destination: TVLibraryDestination,
        settingsConfigured: Boolean,
    ): TVSettingsDestination = if (!settingsConfigured) {
        TVSettingsDestination.Pair
    } else {
        when (destination) {
            TVLibraryDestination.TMDBCatalogs -> TVSettingsDestination.Metadata
            TVLibraryDestination.TamilMV,
            TVLibraryDestination.LetterboxdLists,
            TVLibraryDestination.NewFromFriends -> TVSettingsDestination.Addons
            else -> TVSettingsDestination.Pair
        }
    }
}

internal enum class TVLibraryDestination(
    val icon: RailIcon?,
    val title: String,
    val subtitle: String,
) {
    Continue(RailIcon.Play, "Continue watching", "Resume on this TV"),
    TamilMV(null, "Tamil MV", "Popular and recent releases"),
    LetterboxdLists(RailIcon.List, "Letterboxd lists", "Lists from your iPhone"),
    NewFromFriends(RailIcon.People, "New From Friends", "Letterboxd activity"),
    TMDBCatalogs(RailIcon.Stack, "TMDB catalogs", "Movies and series"),
    Jobs(RailIcon.Jobs, "Jobs", "Refresh and provider status"),
}

internal enum class RailIcon { Play, List, People, Stack, Jobs, Settings }

internal enum class TVJobPhase { Waiting, Running, Ready, Failed, Blocked }

internal data class TVJobStatus(
    val phase: TVJobPhase,
    val detail: String,
) {
    val label: String
        get() = when (phase) {
            TVJobPhase.Waiting -> "WAITING"
            TVJobPhase.Running -> "RUNNING"
            TVJobPhase.Ready -> "READY"
            TVJobPhase.Failed -> "FAILED"
            TVJobPhase.Blocked -> "BLOCKED"
        }
}

/** A deliberately small TV model for collection canvases. Data is populated by a later catalog
 * snapshot; an empty shelf is still rendered as an actionable, honest state rather than a fake
 * poster grid. */
private data class TVCollectionShelf(
    val id: String,
    val title: String,
    val subtitle: String,
    val items: List<TVTamilMVCatalogItem> = emptyList(),
)

private fun collectionShelvesFor(
    destination: TVLibraryDestination,
    tamilMVState: TVTamilMVCatalogState,
    tmdbState: TVTMDBCatalogState,
    letterboxdUsernames: List<String>,
): List<TVCollectionShelf> = when (destination) {
    TVLibraryDestination.LetterboxdLists -> {
        val snapshot = tamilMVState.visibleSnapshot()
        val syncedShelves = snapshot?.letterboxdShelves.orEmpty()
        if (syncedShelves.isNotEmpty()) {
            syncedShelves.map { shelf ->
                TVCollectionShelf(
                    id = shelf.id,
                    title = shelf.title,
                    subtitle = "Letterboxd list · synced from your iPhone",
                    items = shelf.items,
                )
            }
        } else {
            // Older snapshots predate per-list shelf metadata. Keep them browsable until the
            // next server refresh instead of hiding the already-cached titles.
            val items = snapshot?.letterboxd.orEmpty()
            if (letterboxdUsernames.isEmpty()) {
                listOf(
                    TVCollectionShelf(
                        id = "letterboxd:cached",
                        title = "Letterboxd",
                        subtitle = "Saved Letterboxd items",
                        items = items,
                    ),
                )
            } else {
                letterboxdUsernames.mapIndexed { index, username ->
                    TVCollectionShelf(
                        "letterboxd:$username",
                        "@$username",
                        "Lists from @$username",
                        items = if (index == 0) items else emptyList(),
                    )
                }
            }
        }
    }
    TVLibraryDestination.NewFromFriends -> listOf(
        TVCollectionShelf(
            "friends",
            "New From Friends",
            "Letterboxd activity from your iPhone",
            tamilMVState.visibleSnapshot()?.friends.orEmpty(),
        ),
    )
    TVLibraryDestination.TMDBCatalogs -> tmdbState.visibleSnapshot()?.let { snapshot ->
        listOf(
            TVCollectionShelf("tmdb:movies", "Movies", "Popular and recent TMDB movies", snapshot.movies),
            TVCollectionShelf("tmdb:series", "Series", "Popular and recent TMDB series", snapshot.series),
        )
    } ?: listOf(
        TVCollectionShelf("tmdb:movies", "Movies", "Popular and recent TMDB movies"),
        TVCollectionShelf("tmdb:series", "Series", "Popular and recent TMDB series"),
    )
    else -> emptyList()
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TVLibrarySurface(
    items: List<RecentItem>,
    receiverAddress: String,
    settingsConfigured: Boolean,
    artworkLoader: ArtworkLoader,
    tamilMVState: TVTamilMVCatalogState,
    tmdbState: TVTMDBCatalogState,
    tamilJobStatus: TVJobStatus,
    letterboxdJobStatus: TVJobStatus,
    tmdbJobStatus: TVJobStatus,
    letterboxdUsernames: List<String>,
    selectedDestination: TVLibraryDestination,
    destinationResetKey: Int = 0,
    onDestinationChanged: (TVLibraryDestination) -> Unit,
    onDestinationSelected: (TVLibraryDestination) -> Unit,
    onOpenSettings: (TVSettingsDestination) -> Unit,
    onOpenItem: (RecentItem) -> Unit,
    onRefreshTamilMV: () -> Unit,
    onRefreshLetterboxd: () -> Unit,
    onRefreshTMDB: () -> Unit,
    onResync: () -> Unit,
    addonSourceState: TVAddonSourceState,
    onRequestTamilMVSources: (TVTamilMVCatalogItem) -> Unit,
    onEpisodeSelected: (TVTamilMVCatalogItem) -> Unit,
    onCancelTamilMVSources: () -> Unit,
    onPlayTamilMVSource: (TVTamilMVCatalogItem, TVAddonPlayableSource) -> Unit,
    restoredDetailItem: TVTamilMVCatalogItem? = null,
    restoredDetailLabel: String? = null,
    onDetailChanged: (TVTamilMVCatalogItem?, String?) -> Unit = { _, _ -> },
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
    val destination = selectedDestination
    var previewDestination by remember { mutableStateOf(destination) }
    // Keep the focused item in a state holder. The value is read only by the hero restart scope
    // below; changing focus therefore does not invalidate the rail, rail icons, or poster cards.
    // Keep the holder across a metadata replacement. The refresh effect below needs the previous
    // canonical key to enrich the settled hero instead of jumping back to the first row.
    val focusedItemState = remember(destination) {
        mutableStateOf(itemsFor(destination, items).firstOrNull())
    }
    // Poster focus can change several times per second. Keep those events in a conflated channel so
    // the card itself updates immediately, while the expensive hero/backdrop recomposition only
    // happens for the item the remote settles on.
    val focusedItemUpdates = remember(destination) { Channel<RecentFocusTarget>(Channel.CONFLATED) }
    val latestRecentItems by rememberUpdatedState(items)
    var tamilShelf by remember { mutableStateOf(TamilMVShelf.Popular) }
    val tamilSnapshot = tamilMVState.visibleSnapshot()
    var focusedTamilItem by remember(tamilSnapshot, tamilShelf) {
        mutableStateOf(tamilShelf.items(tamilSnapshot).firstOrNull())
    }
    val focusedTamilUpdates = remember(tamilShelf) { Channel<TVTamilMVCatalogItem>(Channel.CONFLATED) }
    var tamilDetailItem by remember(restoredDetailItem?.sourceKey) { mutableStateOf(restoredDetailItem) }
    var tamilDetailLabel by remember(restoredDetailItem?.sourceKey, restoredDetailLabel) {
        mutableStateOf(restoredDetailLabel ?: "TAMIL MV")
    }
    var detailReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val collectionShelves = collectionShelvesFor(
        destination = destination,
        tamilMVState = tamilMVState,
        tmdbState = tmdbState,
        letterboxdUsernames = letterboxdUsernames,
    )
    val hasFocusableDestinationContent = when (destination) {
        TVLibraryDestination.Continue -> items.isNotEmpty()
        TVLibraryDestination.TamilMV -> tamilSnapshot?.let { it.popular.isNotEmpty() || it.recent.isNotEmpty() } == true
        TVLibraryDestination.LetterboxdLists,
        TVLibraryDestination.NewFromFriends,
        TVLibraryDestination.TMDBCatalogs,
        -> collectionShelves.any { it.items.isNotEmpty() }
        TVLibraryDestination.Jobs -> true
    }
    // The policy is the source of the reset graph: selecting any destination starts at top-nav
    // column zero, and an empty Lists/Friends canvas never grows an invisible Hero target.
    val destinationNavigation = remember(destination, destinationResetKey, hasFocusableDestinationContent) {
        TVReceiverPresentationPolicy.selectDestination(destination)
    }
    val hasHero = TVReceiverPresentationPolicy.moveDown(
        destinationNavigation,
        hasFocusableDestinationContent,
    ).focusZone == TVReceiverPresentationPolicy.HomeFocusZone.Hero &&
        TVReceiverPresentationPolicy.canFocusHero(hasFocusableDestinationContent)
    var selectedShelfID by remember(destination, destinationResetKey, collectionShelves) {
        mutableStateOf(collectionShelves.getOrNull(destinationNavigation.column)?.id)
    }
    val selectedRailFocus = remember { FocusRequester() }
    // Every selected rail item has an explicit RIGHT destination. Fire OS's spatial search does
    // not reliably cross the large hero gap from the compact dock to the poster viewport.
    val contentEntryFocus = remember(destination, destinationResetKey) { FocusRequester() }
    val heroFocus = remember(destination, destinationResetKey) { FocusRequester() }
    val railFocusVisuals = remember { FocusVisualRegistry() }
    var navigationChrome by remember(destination, destinationResetKey) {
        mutableStateOf(TVReceiverPresentationPolicy.NavigationChrome.Visible)
    }
    val onShelfFocused = {
        if (destination != TVLibraryDestination.Jobs) {
            navigationChrome = TVReceiverPresentationPolicy.navigationChromeFor(
                TVReceiverPresentationPolicy.HomeFocusZone.Shelf,
            )
        }
    }
    val onNavigationOrHeroFocused = {
        navigationChrome = TVReceiverPresentationPolicy.navigationChromeFor(
            TVReceiverPresentationPolicy.HomeFocusZone.Hero,
        )
    }
    val shelfPresentation by animateFloatAsState(
        targetValue = if (
            navigationChrome == TVReceiverPresentationPolicy.NavigationChrome.HiddenForShelf &&
            destination != TVLibraryDestination.Jobs
        ) 1f else 0f,
        animationSpec = tween(TvTokens.Motion.NavHideMillis, easing = TvTokens.Motion.Emph),
        label = "libraryPageGlide",
    )

    LaunchedEffect(Unit) { selectedRailFocus.requestFocus() }
    LaunchedEffect(destination) {
        previewDestination = destination
    }
    LaunchedEffect(previewDestination, destination) {
        if (previewDestination == destination) return@LaunchedEffect
        onDestinationChanged(previewDestination)
    }
    LaunchedEffect(destination, items) {
        val refreshedItems = itemsFor(destination, items)
        val current = focusedItemState.value
        val refreshedIndex = TVLibrarySurfacePolicy.refreshedFocusIndex(
            previousDedupeKey = current?.dedupeKey,
            previousCanonicalKey = current?.canonicalKey,
            dedupeKeys = refreshedItems.map(RecentItem::dedupeKey),
            canonicalKeys = refreshedItems.map(RecentItem::canonicalKey),
        )
        focusedItemState.value = refreshedItems.getOrNull(refreshedIndex)
    }
    LaunchedEffect(focusedItemUpdates) {
        while (true) {
            var latest = focusedItemUpdates.receive()
            while (true) {
                val next = withTimeoutOrNull(FOCUS_PREVIEW_SETTLE_MILLIS) { focusedItemUpdates.receive() } ?: break
                latest = next
            }
            // Resolve against the latest model after the settle window. A phone push may enrich
            // this card or remove it while the remote is still repeating, so retaining the old
            // object would make the hero stale or resurrect a removed row.
            val refreshedItems = itemsFor(destination, latestRecentItems)
            val refreshedIndex = TVLibrarySurfacePolicy.refreshedFocusIndex(
                previousDedupeKey = latest.dedupeKey,
                previousCanonicalKey = latest.canonicalKey,
                dedupeKeys = refreshedItems.map(RecentItem::dedupeKey),
                canonicalKeys = refreshedItems.map(RecentItem::canonicalKey),
            )
            focusedItemState.value = refreshedItems.getOrNull(refreshedIndex)
        }
    }
    LaunchedEffect(tamilSnapshot, tamilShelf) {
        focusedTamilItem = tamilShelf.items(tamilSnapshot).firstOrNull()
    }
    LaunchedEffect(focusedTamilUpdates) {
        while (true) {
            var latest = focusedTamilUpdates.receive()
            while (true) {
                val next = withTimeoutOrNull(FOCUS_PREVIEW_SETTLE_MILLIS) { focusedTamilUpdates.receive() } ?: break
                latest = next
            }
            focusedTamilItem = latest
        }
    }
    val closeTamilDetail = {
        tamilDetailItem = null
        onDetailChanged(null, null)
        onCancelTamilMVSources()
    }
    BackHandler(enabled = tamilDetailItem != null) { closeTamilDetail() }
    LaunchedEffect(tamilDetailItem, detailReturnFocus, hasHero) {
        val target = detailReturnFocus
        if (tamilDetailItem == null && target != null) {
            when (TVReceiverPresentationPolicy.detailReturnZone(target.requestFocus(), hasHero)) {
                TVReceiverPresentationPolicy.DetailReturnZone.Card -> Unit
                TVReceiverPresentationPolicy.DetailReturnZone.Hero -> heroFocus.requestFocus()
                TVReceiverPresentationPolicy.DetailReturnZone.Navigation -> selectedRailFocus.requestFocus()
            }
            detailReturnFocus = null
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LibraryAccent,
            onPrimary = LibraryBlack,
            surface = LibraryElevated,
            onSurface = LibraryText,
            background = LibraryBlack,
            onBackground = LibraryText,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(LibraryBlack)) {
            CompositionLocalProvider(
                LocalDensity provides designDensity,
                LocalBringIntoViewSpec provides ImmediateBringIntoViewSpec,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopNavigation(
                        selected = destination,
                        hasFocusableContent = hasHero,
                        navigationVisible = navigationChrome == TVReceiverPresentationPolicy.NavigationChrome.Visible,
                        selectedFocus = selectedRailFocus,
                        contentEntryFocus = if (destination != TVLibraryDestination.Jobs && hasHero) heroFocus else contentEntryFocus,
                        focusVisuals = railFocusVisuals,
                        onFocused = onNavigationOrHeroFocused,
                        onPreview = { previewDestination = it },
                        onSelect = {
                            previewDestination = it
                            onDestinationSelected(it)
                        },
                        onOpenSettings = { onOpenSettings(TVSettingsDestination.Pair) },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (destination == TVLibraryDestination.TamilMV) {
                            TamilMVContent(
                                state = tamilMVState,
                                settingsConfigured = settingsConfigured,
                                shelf = tamilShelf,
                                focusedItem = focusedTamilItem,
                                receiverAddress = receiverAddress,
                                artworkLoader = artworkLoader,
                                returnRailFocus = selectedRailFocus,
                                contentEntryFocus = contentEntryFocus,
                                heroFocus = heroFocus,
                                onContentFocused = onShelfFocused,
                                onHeroFocused = onNavigationOrHeroFocused,
                                shelfPresentation = shelfPresentation,
                                onShelfChanged = { tamilShelf = it },
                                onItemFocused = { focusedTamilUpdates.trySend(it) },
                                onOpenItem = { item, focusRequester ->
                                    detailReturnFocus = focusRequester
                                    tamilDetailItem = item
                                    tamilDetailLabel = "TAMIL MV"
                                    onDetailChanged(item, tamilDetailLabel)
                                    onRequestTamilMVSources(item)
                                },
                                onRefresh = {},
                                onOpenSettings = {
                                    onOpenSettings(
                                        if (settingsConfigured) TVSettingsDestination.Keys else TVSettingsDestination.Pair,
                                    )
                                },
                            )
                        } else if (destination in setOf(
                            TVLibraryDestination.LetterboxdLists,
                            TVLibraryDestination.NewFromFriends,
                            TVLibraryDestination.TMDBCatalogs,
                        )) {
                            CollectionContent(
                                destination = destination,
                                settingsConfigured = settingsConfigured,
                                shelves = collectionShelves,
                                selectedShelfID = selectedShelfID,
                                onShelfSelected = { selectedShelfID = it },
                                letterboxdUsernames = letterboxdUsernames,
                                artworkLoader = artworkLoader,
                                returnRailFocus = selectedRailFocus,
                                contentEntryFocus = contentEntryFocus,
                                heroFocus = heroFocus,
                        onContentFocused = onShelfFocused,
                        onHeroFocused = onNavigationOrHeroFocused,
                        shelfPresentation = shelfPresentation,
                                onOpenItem = { item, focusRequester ->
                                    detailReturnFocus = focusRequester
                                    tamilDetailItem = item
                                    tamilDetailLabel = destination.title.uppercase()
                                    onDetailChanged(item, tamilDetailLabel)
                                    onRequestTamilMVSources(item)
                                },
                                onOpenSettings = onOpenSettings,
                            )
                        } else {
                            LibraryContent(
                                destination = destination,
                                items = itemsFor(destination, items),
                                focusedItemState = focusedItemState,
                                receiverAddress = receiverAddress,
                                settingsConfigured = settingsConfigured,
                                tamilJobStatus = tamilJobStatus,
                                letterboxdJobStatus = letterboxdJobStatus,
                                tmdbJobStatus = tmdbJobStatus,
                                artworkLoader = artworkLoader,
                                returnRailFocus = selectedRailFocus,
                                contentEntryFocus = contentEntryFocus,
                                heroFocus = heroFocus,
                        onContentFocused = onShelfFocused,
                        onHeroFocused = onNavigationOrHeroFocused,
                        shelfPresentation = shelfPresentation,
                                onItemFocused = { item ->
                                    focusedItemUpdates.trySend(
                                        RecentFocusTarget(item.dedupeKey, item.canonicalKey),
                                    )
                                },
                                onOpenSettings = onOpenSettings,
                                onOpenItem = onOpenItem,
                                onRefreshTamilMV = onRefreshTamilMV,
                                onRefreshLetterboxd = onRefreshLetterboxd,
                                onRefreshTMDB = onRefreshTMDB,
                                onResync = onResync,
                            )
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalDensity provides designDensity) {
                tamilDetailItem?.let { item ->
                    TamilMVDetailOverlay(
                        item = item,
                        collectionLabel = tamilDetailLabel,
                        artworkLoader = artworkLoader,
                        sourceState = addonSourceState,
                        onRetry = { onRequestTamilMVSources(item) },
                        onEpisodeSelected = { episodeItem ->
                            tamilDetailItem = episodeItem
                            onDetailChanged(episodeItem, tamilDetailLabel)
                            onEpisodeSelected(episodeItem)
                        },
                        onPlay = { onPlayTamilMVSource(item, it) },
                        onClose = closeTamilDetail,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopNavigation(
    selected: TVLibraryDestination,
    hasFocusableContent: Boolean,
    navigationVisible: Boolean,
    selectedFocus: FocusRequester,
    contentEntryFocus: FocusRequester,
    focusVisuals: FocusVisualRegistry,
    onFocused: () -> Unit,
    onPreview: (TVLibraryDestination) -> Unit,
    onSelect: (TVLibraryDestination) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val navVisibility by animateFloatAsState(
        targetValue = if (navigationVisible) 1f else 0f,
        animationSpec = tween(TvTokens.Motion.NavHideMillis, easing = TvTokens.Motion.Emph),
        label = "libraryNavVisibility",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTokens.Geometry.NavHeightDp.dp)
            .background(LibraryBlack.copy(alpha = 0.94f))
            .graphicsLayer {
                alpha = navVisibility
                translationY = (-24).dp.toPx() * (1f - navVisibility)
            }
            .padding(
                start = TvTokens.Geometry.ScreenPaddingDp.dp,
                top = TvTokens.Geometry.NavigationTopPaddingDp.dp,
                end = TvTokens.Geometry.ScreenPaddingDp.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.width(220.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = "4789",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(72.dp),
            )
            Text("RECEIVER", color = LibraryTertiary, fontSize = 14.sp, fontFamily = LibraryDataFont, letterSpacing = 2.8.sp)
        }
        LazyRow(
            modifier = Modifier.weight(1f).height(72.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(TVLibraryDestination.entries, key = { it.name }) { destination ->
                TopNavTab(
                    label = when (destination) {
                        TVLibraryDestination.Continue -> "Continue"
                        TVLibraryDestination.TamilMV -> "Tamil MV"
                        TVLibraryDestination.LetterboxdLists -> "Lists"
                        TVLibraryDestination.NewFromFriends -> "Friends"
                        TVLibraryDestination.TMDBCatalogs -> "TMDB"
                        TVLibraryDestination.Jobs -> "Jobs"
                    },
                    icon = destination.icon,
                    accent = TvTokens.accentFor(destination),
                    selected = destination == selected,
                    focusEnabled = navigationVisible,
                    modifier = Modifier
                        .then(if (destination == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                        .then(
                            if (destination == selected && hasFocusableContent) {
                                Modifier.focusProperties { down = contentEntryFocus }
                            } else {
                                Modifier
                            },
                        ),
                    focusKey = destination,
                    focusVisuals = focusVisuals,
                    onFocused = {
                        onFocused()
                        onPreview(destination)
                    },
                    onClick = { onSelect(destination) },
                )
            }
            item(key = "settings") {
                TopNavTab(
                    label = "Pair & Sync",
                    icon = RailIcon.Settings,
                    accent = LibraryText,
                    selected = false,
                    focusEnabled = navigationVisible,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    focusKey = "settings",
                    focusVisuals = focusVisuals,
                    onFocused = onFocused,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun TopNavTab(
    label: String,
    icon: RailIcon?,
    accent: Color,
    selected: Boolean,
    focusEnabled: Boolean,
    modifier: Modifier,
    focusKey: Any,
    focusVisuals: FocusVisualRegistry,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val focused by remember(focusKey, focusVisuals) { focusVisuals.stateFor(focusKey) }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(TvTokens.Motion.FocusScaleMillis, easing = TvTokens.Motion.Pop),
        label = "topNavFocusScale",
    )
    Row(
        modifier = modifier
            .height(68.dp)
            .graphicsLayer { scaleX = focusScale; scaleY = focusScale }
            .then(if (!focusEnabled) Modifier.focusProperties { canFocus = false } else Modifier)
            .clip(RoundedCornerShape(99.dp))
            .background(
                when {
                    focused -> accent
                    selected -> accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            )
            .onFocusChanged {
                if (it.hasFocus) {
                    focusVisuals.moveTo(focusKey)
                    onFocused()
                }
            }
            .then(
                if (focusEnabled) Modifier.clickable(role = Role.Button, onClick = onClick).focusable()
                else Modifier,
            )
            .padding(horizontal = 21.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        RailGlyph(
            icon = icon,
            fallback = label.take(4),
            color = if (focused) TvTokens.Color.OnAccent else accent,
            modifier = Modifier.width(24.dp),
        )
        Text(
            label,
            color = if (focused) TvTokens.Color.OnAccent else if (selected) LibraryText else LibrarySecondary,
            fontFamily = LibraryUIFont,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RailGlyph(
    icon: RailIcon?,
    fallback: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (icon == null) {
        Text(
            fallback.take(4).uppercase(),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            modifier = modifier,
        )
        return
    }
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val stroke = Stroke(
            width = 2.1.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
            val w = size.width
            val h = size.height
            when (icon) {
            RailIcon.Play -> {
                val path = Path().apply {
                    moveTo(w * 0.30f, h * 0.19f)
                    lineTo(w * 0.79f, h * 0.50f)
                    lineTo(w * 0.30f, h * 0.81f)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
            RailIcon.List -> {
                val path = Path().apply {
                    moveTo(w * 0.25f, h * 0.25f)
                    lineTo(w * 0.78f, h * 0.25f)
                    moveTo(w * 0.25f, h * 0.50f)
                    lineTo(w * 0.78f, h * 0.50f)
                    moveTo(w * 0.25f, h * 0.75f)
                    lineTo(w * 0.78f, h * 0.75f)
                }
                drawPath(path, color, style = stroke)
            }
            RailIcon.People -> {
                val path = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(w * 0.34f, h * 0.13f, w * 0.62f, h * 0.41f))
                    moveTo(w * 0.19f, h * 0.83f)
                    cubicTo(w * 0.22f, h * 0.55f, w * 0.74f, h * 0.55f, w * 0.81f, h * 0.83f)
                }
                drawPath(path, color, style = stroke)
            }
            RailIcon.Stack -> {
                val path = Path().apply {
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.84f, h * 0.30f)
                    lineTo(w * 0.50f, h * 0.48f)
                    lineTo(w * 0.16f, h * 0.30f)
                    close()
                    moveTo(w * 0.16f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.68f)
                    lineTo(w * 0.84f, h * 0.50f)
                    moveTo(w * 0.16f, h * 0.70f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.84f, h * 0.70f)
                }
                drawPath(path, color, style = stroke)
            }
            RailIcon.Jobs -> {
                drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.28f), end = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.28f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.50f), end = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.72f), end = androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.28f))
                drawCircle(color, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.50f))
                drawCircle(color, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.43f, h * 0.72f))
            }
            RailIcon.Settings -> {
                drawCircle(color, radius = w * 0.19f, center = center, style = stroke)
                repeat(8) { index ->
                    val angle = Math.toRadians(index * 45.0)
                    val start = androidx.compose.ui.geometry.Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * w * 0.29f,
                        center.y + kotlin.math.sin(angle).toFloat() * h * 0.29f,
                    )
                    val end = androidx.compose.ui.geometry.Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * w * 0.40f,
                        center.y + kotlin.math.sin(angle).toFloat() * h * 0.40f,
                    )
                    drawLine(color, start, end, strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            }
        }
    }
}

private enum class TamilMVShelf(val label: String) {
    Popular("Popular"),
    Recent("Recent"),
    ;

    fun items(snapshot: com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot?): List<TVTamilMVCatalogItem> =
        when (this) {
            Popular -> snapshot?.popular.orEmpty()
            Recent -> snapshot?.recent.orEmpty()
        }
}

@Composable
private fun TamilMVContent(
    state: TVTamilMVCatalogState,
    settingsConfigured: Boolean,
    shelf: TamilMVShelf,
    focusedItem: TVTamilMVCatalogItem?,
    receiverAddress: String,
    artworkLoader: ArtworkLoader,
    returnRailFocus: FocusRequester,
    contentEntryFocus: FocusRequester,
    heroFocus: FocusRequester,
    onContentFocused: () -> Unit,
    onHeroFocused: () -> Unit,
    shelfPresentation: Float,
    onShelfChanged: (TamilMVShelf) -> Unit,
    onItemFocused: (TVTamilMVCatalogItem) -> Unit,
    onOpenItem: (TVTamilMVCatalogItem, FocusRequester) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snapshot = state.visibleSnapshot()
    val items = shelf.items(snapshot)
    val receiverSuffix = receiverAddress.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()
    val popularFocus = remember { FocusRequester() }
    val recentFocus = remember { FocusRequester() }
    val shelfFocus = if (shelf == TamilMVShelf.Popular) popularFocus else recentFocus
    val posterFocus = remember(shelf, snapshot?.generation) { FocusRequester() }
    val cardFocusVisuals = remember { FocusVisualRegistry() }
    val posterRailState = rememberLazyListState()
    var settledPosterIndex by remember(shelf, snapshot?.generation) { mutableStateOf(0) }
    val posterExtentPx = with(LocalDensity.current) {
        (TvTokens.Geometry.PosterCardWidthDp + 18).dp.roundToPx()
    }
    LaunchedEffect(items.size, settledPosterIndex, posterExtentPx) {
        if (items.isNotEmpty()) {
                posterRailState.scrollToItem(
                index = 0,
                scrollOffset = TVReceiverPresentationPolicy.railScrollOffset(settledPosterIndex, posterExtentPx),
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = -TvTokens.Geometry.PageGlideOffsetDp.dp.toPx() * shelfPresentation }
            .background(Brush.linearGradient(listOf(LibraryBlack, LibrarySlate, LibraryElevated))),
    ) {
        // Backdrops are preferred; poster-only records still render their subject whole in the
        // shared 16:9 artwork stage instead of being stretched across the hero copy area.
        val heroArtwork = focusedItem?.backdropURL ?: focusedItem?.posterURL
        LibraryHeroBackdrop(
            artworkUrl = heroArtwork,
            artworkLoader = artworkLoader,
            portraitFallback = focusedItem?.backdropURL == null,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvTokens.Geometry.ScreenPaddingDp.dp,
                    top = 26.dp,
                    end = TvTokens.Geometry.ScreenPaddingDp.dp,
                    bottom = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
                ),
        ) {
            Text(
                "TAMIL MV  ·  4789 TV$receiverSuffix",
                color = TvTokens.Color.BrandGreen,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            var heroFocused by remember(shelf, focusedItem?.id) { mutableStateOf(false) }
            Text(
                focusedItem?.title ?: "Tamil MV",
                color = LibraryText,
                fontSize = 104.sp,
                lineHeight = 94.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = LibraryDisplayFont,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth(0.64f)
                    .graphicsLayer {
                        alpha = 1f - shelfPresentation
                        scaleX = 1f - 0.04f * shelfPresentation
                        scaleY = 1f - 0.04f * shelfPresentation
                    }
                    .then(if (focusedItem != null && items.isNotEmpty()) Modifier
                        .focusRequester(heroFocus)
                        .focusProperties { up = returnRailFocus; down = contentEntryFocus }
                        .onFocusChanged {
                            heroFocused = it.hasFocus
                            if (it.hasFocus) onHeroFocused()
                        }
                        .clickable(role = Role.Button) { onOpenItem(focusedItem, heroFocus) }
                        .focusable()
                        .then(if (heroFocused) Modifier.border(TvTokens.Geometry.FocusRingDp.dp, TvTokens.Color.BrandGreen, RoundedCornerShape(18.dp)) else Modifier)
                        .padding(16.dp) else Modifier),
            )
            if (heroFocused && focusedItem != null) {
                Text("OPEN", color = TvTokens.Color.BrandGreen, fontFamily = LibraryDataFont, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (focusedItem != null) {
                val metadata = buildList {
                    focusedItem.year?.let { add(it.toString()) }
                    add(if (focusedItem.mediaType == "series") "SERIES" else "MOVIE")
                    addAll(focusedItem.genres.take(2))
                }.joinToString("  ·  ")
                Text(metadata, color = LibrarySecondary, fontSize = 15.sp, maxLines = 1)
                focusedItem.overview?.trim()?.takeIf(String::isNotEmpty)?.let { overview ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        overview,
                        color = LibraryText.copy(alpha = 0.90f),
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.62f),
                    )
                }
            } else {
                Text(tamilMVStateMessage(state), color = LibrarySecondary, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.fillMaxWidth(0.72f))
            }
            Spacer(Modifier.weight(1f))
            Text(tamilMVStateMessage(state), color = tamilMVStateColor(state), fontSize = 14.sp, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Refresh actions are available in Jobs", color = LibrarySecondary, fontSize = 16.sp)
                TamilMVControl(
                    label = "Popular ${snapshot?.popular?.size?.takeIf { it > 0 }?.let { "· $it" }.orEmpty()}",
                    selected = shelf == TamilMVShelf.Popular,
                    accent = TvTokens.Color.BrandGreen,
                    focusRequester = contentEntryFocus,
                    entryFocusRequester = null,
                    upFocus = heroFocus,
                    downFocus = posterFocus,
                    onFocused = onContentFocused,
                    onClick = { onShelfChanged(TamilMVShelf.Popular) },
                )
                TamilMVControl(
                    label = "Recent ${snapshot?.recent?.size?.takeIf { it > 0 }?.let { "· $it" }.orEmpty()}",
                    selected = shelf == TamilMVShelf.Recent,
                    accent = TvTokens.Color.BrandGreen,
                    focusRequester = recentFocus,
                    entryFocusRequester = null,
                    upFocus = heroFocus,
                    downFocus = posterFocus,
                    onFocused = onContentFocused,
                    onClick = { onShelfChanged(TamilMVShelf.Recent) },
                )
                if (state is TVTamilMVCatalogState.MissingCredential) {
                    TamilMVControl(
                        label = if (settingsConfigured) "Open API keys" else "Pair settings",
                        selected = false,
                        accent = TvTokens.Color.BrandGreen,
                        upFocus = heroFocus,
                        downFocus = posterFocus,
                        onFocused = onContentFocused,
                        onClick = onOpenSettings,
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            if (items.isEmpty()) {
                Text(
                    if (state is TVTamilMVCatalogState.Empty) {
                        "On iPhone: open Tamil MV → Refresh. When it finishes, return here and press Refresh."
                    } else {
                        "No ${shelf.label.lowercase()} posters are available yet."
                    },
                    color = LibrarySecondary,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(64.dp))
            } else {
                Text(shelf.label.uppercase(), color = LibraryText, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    // Lazy layouts have no intrinsic height. Without an explicit viewport the
                    // weighted hero spacer can consume the remaining column space and collapse
                    // the poster rail to zero, leaving the D-pad with no right-pane target.
                    state = posterRailState,
                    modifier = Modifier.height(360.dp).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        val cardFocusRequester = if (index == 0) {
                            posterFocus
                        } else {
                            remember(item.id) { FocusRequester() }
                        }
                        TamilMVPosterCard(
                            item = item,
                            artworkLoader = artworkLoader,
                            returnRailFocus = returnRailFocus.takeIf { index == 0 },
                            focusRequester = cardFocusRequester,
                            focusVisuals = cardFocusVisuals,
                            accent = TvTokens.Color.BrandGreen,
                            upFocus = heroFocus.takeIf { index == 0 } ?: shelfFocus,
                            downFocus = shelfFocus,
                            onFocused = {
                                onContentFocused()
                                settledPosterIndex = index
                                onItemFocused(item)
                            },
                            onClick = { onOpenItem(item, cardFocusRequester) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionContent(
    destination: TVLibraryDestination,
    settingsConfigured: Boolean,
    shelves: List<TVCollectionShelf>,
    selectedShelfID: String?,
    onShelfSelected: (String) -> Unit,
    letterboxdUsernames: List<String>,
    artworkLoader: ArtworkLoader,
    returnRailFocus: FocusRequester,
    contentEntryFocus: FocusRequester,
    heroFocus: FocusRequester,
    onContentFocused: () -> Unit,
    onHeroFocused: () -> Unit,
    shelfPresentation: Float,
    onOpenItem: (TVTamilMVCatalogItem, FocusRequester) -> Unit,
    onOpenSettings: (TVSettingsDestination) -> Unit,
) {
    val selectedShelf = shelves.firstOrNull { it.id == selectedShelfID } ?: shelves.firstOrNull()
    val items = selectedShelf?.items.orEmpty()
    var focusedItem by remember(selectedShelf?.id) { mutableStateOf(items.firstOrNull()) }
    val hasLiveShelfContent = shelves.any { it.items.isNotEmpty() }
    val shelfFocusRequesters = remember(shelves.map(TVCollectionShelf::id)) {
        shelves.associate { it.id to FocusRequester() }
    }
    val posterFocus = remember(selectedShelf?.id) { FocusRequester() }
    val cardFocusVisuals = remember { FocusVisualRegistry() }
    val posterRailState = rememberLazyListState()
    var settledPosterIndex by remember(selectedShelf?.id) { mutableStateOf(0) }
    val posterExtentPx = with(LocalDensity.current) {
        (TvTokens.Geometry.PosterCardWidthDp + 18).dp.roundToPx()
    }
    LaunchedEffect(items.size, selectedShelf?.id, settledPosterIndex, posterExtentPx) {
        if (items.isNotEmpty()) {
                posterRailState.scrollToItem(
                index = 0,
                scrollOffset = TVReceiverPresentationPolicy.railScrollOffset(settledPosterIndex, posterExtentPx),
            )
        }
    }
    if (!hasLiveShelfContent && destination in setOf(
            TVLibraryDestination.LetterboxdLists,
            TVLibraryDestination.NewFromFriends,
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(LibraryBlack, LibrarySlate))),
            contentAlignment = Alignment.Center,
        ) {
            CollectionEmptyPanel(destination, letterboxdUsernames)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = -TvTokens.Geometry.PageGlideOffsetDp.dp.toPx() * shelfPresentation }
            .background(Brush.linearGradient(listOf(LibraryBlack, LibrarySlate, LibraryElevated))),
    ) {
        val heroArtwork = focusedItem?.backdropURL ?: focusedItem?.posterURL
        LibraryHeroBackdrop(
            artworkUrl = heroArtwork,
            artworkLoader = artworkLoader,
            portraitFallback = focusedItem?.backdropURL == null,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvTokens.Geometry.ScreenPaddingDp.dp,
                    top = 26.dp,
                    end = TvTokens.Geometry.ScreenPaddingDp.dp,
                    bottom = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
                ),
        ) {
            Text(
                "${destination.title.uppercase()}  ·  4789 TV",
                color = TvTokens.accentFor(destination),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            var heroFocused by remember(destination, selectedShelf?.id) { mutableStateOf(false) }
            Text(
                focusedItem?.title ?: destination.title,
                color = LibraryText,
                fontSize = 88.sp,
                lineHeight = 84.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = LibraryDisplayFont,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - shelfPresentation
                    scaleX = 1f - 0.04f * shelfPresentation
                    scaleY = 1f - 0.04f * shelfPresentation
                }
                    .then(if (items.isNotEmpty()) Modifier
                    .focusRequester(heroFocus)
                    .focusProperties { up = returnRailFocus; down = contentEntryFocus }
                    .onFocusChanged {
                        heroFocused = it.hasFocus
                        if (it.hasFocus) onHeroFocused()
                    }
                    .clickable(role = Role.Button) {
                        items.firstOrNull()?.let { onOpenItem(it, heroFocus) }
                    }
                    .focusable()
                    .then(if (heroFocused) Modifier.border(TvTokens.Geometry.FocusRingDp.dp, TvTokens.accentFor(destination), RoundedCornerShape(18.dp)) else Modifier)
                    .padding(16.dp) else Modifier),
            )
            if (heroFocused && items.isNotEmpty()) {
                Text("OPEN", color = TvTokens.accentFor(destination), fontFamily = LibraryDataFont, fontSize = 14.sp)
            }
            focusedItem?.let { item ->
                val metadata = buildList {
                    item.year?.let { add(it.toString()) }
                    add(if (item.mediaType == "series") "SERIES" else "MOVIE")
                    addAll(item.genres.take(2))
                }.joinToString("  ·  ")
                Text(metadata, color = LibrarySecondary, fontSize = 17.sp, maxLines = 1)
                item.overview?.trim()?.takeIf(String::isNotEmpty)?.let { overview ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        overview,
                        color = LibraryText.copy(alpha = 0.90f),
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.62f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (destination) {
                    TVLibraryDestination.LetterboxdLists -> if (letterboxdUsernames.isEmpty()) {
                        "Add a Letterboxd username on your iPhone, then Re-Sync from Jobs."
                    } else {
                        "Lists from ${letterboxdUsernames.joinToString { "@$it" }}"
                    }
                    TVLibraryDestination.NewFromFriends -> "Letterboxd activity, exactly as shared from your iPhone."
                    TVLibraryDestination.TMDBCatalogs -> "Movies and series from the signed TMDB catalogs."
                    else -> ""
                },
                color = LibrarySecondary,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                modifier = Modifier.fillMaxWidth(0.70f),
            )
            Spacer(Modifier.height(24.dp))
            if (shelves.size > 1 && hasLiveShelfContent) {
                LazyRow(
                    modifier = Modifier.height(76.dp).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shelves, key = { it.id }) { shelf ->
                        TamilMVControl(
                            label = shelf.title,
                            selected = shelf.id == selectedShelf?.id,
                            accent = TvTokens.accentFor(destination),
                            focusRequester = if (shelf.id == shelves.firstOrNull()?.id) contentEntryFocus else shelfFocusRequesters[shelf.id],
                            entryFocusRequester = null,
                            upFocus = heroFocus,
                            downFocus = posterFocus,
                            onFocused = onContentFocused,
                            onClick = { onShelfSelected(shelf.id) },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (items.isEmpty()) {
                Text(
                    when (destination) {
                        TVLibraryDestination.LetterboxdLists -> "No Letterboxd lists are cached on this TV yet. Run Letterboxd refresh from Jobs after pairing."
                        TVLibraryDestination.NewFromFriends -> "No friend activity is cached on this TV yet. Run Letterboxd refresh from Jobs after pairing."
                        TVLibraryDestination.TMDBCatalogs -> "No TMDB posters are cached on this TV yet. Run TMDB refresh from Jobs."
                        else -> "No items available yet."
                    },
                    color = LibrarySecondary,
                    fontSize = 18.sp,
                    lineHeight = 27.sp,
                    modifier = Modifier.fillMaxWidth(0.70f),
                )
                if (destination !in setOf(TVLibraryDestination.LetterboxdLists, TVLibraryDestination.NewFromFriends)) {
                    Spacer(Modifier.height(18.dp))
                    LibraryAction(
                        label = if (!settingsConfigured) "Open Pair & Sync" else "Open Settings",
                        returnRailFocus = returnRailFocus,
                        focusRequester = contentEntryFocus,
                        onFocused = onContentFocused,
                        onClick = {
                            onOpenSettings(TVLibrarySurfacePolicy.settingsDestination(destination, settingsConfigured))
                        },
                    )
                }
            } else {
                Text(selectedShelf?.title?.uppercase().orEmpty(), color = LibraryText, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    state = posterRailState,
                    modifier = Modifier.height(360.dp).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        val cardFocusRequester = if (index == 0) {
                            posterFocus
                        } else {
                            remember(item.id) { FocusRequester() }
                        }
                        TamilMVPosterCard(
                            item = item,
                            artworkLoader = artworkLoader,
                            returnRailFocus = returnRailFocus.takeIf { index == 0 },
                            focusRequester = cardFocusRequester,
                            focusVisuals = cardFocusVisuals,
                            accent = TvTokens.accentFor(destination),
                            upFocus = heroFocus.takeIf { index == 0 } ?: shelfFocusRequesters[selectedShelf?.id],
                            downFocus = shelfFocusRequesters[selectedShelf?.id],
                            onFocused = {
                                focusedItem = item
                                settledPosterIndex = index
                                onContentFocused()
                            },
                            onClick = { onOpenItem(item, cardFocusRequester) },
                        )
                    }
                }
            }
        }
    }
}

/** An honest empty state: no hidden hero, no synthetic card, and no competing focus target. */
@Composable
private fun CollectionEmptyPanel(
    destination: TVLibraryDestination,
    letterboxdUsernames: List<String>,
) {
    val headline = when (destination) {
        TVLibraryDestination.LetterboxdLists -> "No lists on this TV yet"
        TVLibraryDestination.NewFromFriends -> "No friend activity on this TV yet"
        else -> "Nothing here yet"
    }
    val body = when (destination) {
        TVLibraryDestination.LetterboxdLists -> if (letterboxdUsernames.isEmpty()) {
            "No Letterboxd username has been received by this receiver."
        } else {
            "No saved lists were received for ${letterboxdUsernames.joinToString { "@$it" }}."
        }
        TVLibraryDestination.NewFromFriends -> "No saved Letterboxd friend activity has been received by this receiver."
        else -> "No saved collection items were received by this receiver."
    }
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(LibraryElevated)
            .padding(56.dp),
    ) {
        Text(destination.title.uppercase(), color = TvTokens.accentFor(destination), fontFamily = LibraryDataFont, fontSize = 13.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(18.dp))
        Text(headline, color = LibraryText, fontFamily = LibraryDisplayFont, fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        Text(body, color = LibrarySecondary, fontSize = 20.sp, lineHeight = 31.sp)
        Spacer(Modifier.height(28.dp))
        Text("Use LEFT or RIGHT to choose another destination", color = LibraryTertiary, fontFamily = LibraryDataFont, fontSize = 14.sp)
    }
}

private fun tamilMVStateMessage(state: TVTamilMVCatalogState): String = when (state) {
    is TVTamilMVCatalogState.Loading -> if (state.cached == null) "Loading the signed Tamil MV catalog…" else "Refreshing · saved posters remain available"
    is TVTamilMVCatalogState.Ready -> when {
        state.notice != null -> state.notice
        state.isStale -> "Saved catalog · refresh when the network is available"
        else -> "Updated on this TV · ${state.snapshot.tamilMVItemCount} Tamil MV entries"
    }
    TVTamilMVCatalogState.MissingCredential -> "Catalog Server Token is missing. Open Settings → Debrid & API Keys, then Re-Sync from the iPhone."
    TVTamilMVCatalogState.Empty -> "No server snapshot yet. Refresh Tamil MV once on the iPhone, then refresh this TV."
    is TVTamilMVCatalogState.Error -> state.message
}

private fun tamilMVStateColor(state: TVTamilMVCatalogState): Color = when (state) {
    is TVTamilMVCatalogState.Ready -> if (state.isStale || state.notice != null) TvTokens.Color.Warning else TvTokens.Color.BrandGreen
    is TVTamilMVCatalogState.Loading -> LibraryAccent
    TVTamilMVCatalogState.Empty, TVTamilMVCatalogState.MissingCredential, is TVTamilMVCatalogState.Error -> TvTokens.Color.Warning
}

@Composable
private fun TamilMVControl(
    label: String,
    selected: Boolean,
    accent: Color = LibraryAccent,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    returnRailFocus: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    entryFocusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (returnRailFocus != null || upFocus != null || downFocus != null) {
                Modifier.focusProperties {
                    returnRailFocus?.let { up = it }
                    upFocus?.let { up = it }
                    downFocus?.let { down = it }
                }
            } else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .heightIn(
                min = if (compact) {
                    TvTokens.Geometry.CompactActionHeightDp.dp
                } else {
                    TvTokens.Geometry.PrimaryActionHeightDp.dp
                },
            )
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) accent else if (selected) LibraryElevatedFocused else LibraryElevated)
            .border(
                if (selected || focused) 2.dp else 1.dp,
                if (selected || focused) accent else LibraryText.copy(alpha = 0.14f),
                RoundedCornerShape(999.dp),
            )
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 8.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) LibraryBlack else LibraryText, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TamilMVPosterCard(
    item: TVTamilMVCatalogItem,
    artworkLoader: ArtworkLoader,
    returnRailFocus: FocusRequester?,
    focusVisuals: FocusVisualRegistry,
    accent: Color = TvTokens.Color.BrandGreen,
    focusRequester: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val focused by remember(item.id, focusVisuals) { focusVisuals.stateFor(item.id) }
    // One clock drives all focused-cell properties. Fire TV can repeat D-pad moves every ~120ms;
    // snapping the leaving cell avoids overlapping scale/art/metadata animations in a sweep.
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (focused) {
            tween(TvTokens.Motion.FocusScaleMillis, easing = TvTokens.Motion.Pop)
        } else {
            snap()
        },
        label = "tamilPosterFocusProgress",
    )
    val focusScale = 1f + 0.09f * focusProgress
    val imageScale = 1f + 0.07f * focusProgress
    val metadataAlpha = focusProgress
    Box(
        modifier = Modifier
            .width(TvTokens.Geometry.PosterCardWidthDp.dp)
            .height(TvTokens.Geometry.PosterCardHeightDp.dp)
            .then(if (returnRailFocus != null || upFocus != null || downFocus != null) {
                Modifier.focusProperties {
                    returnRailFocus?.let { up = it }
                    upFocus?.let { up = it }
                    downFocus?.let { down = it }
                }
            } else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                translationY = -12.dp.toPx() * focusProgress
            }
            .clip(RoundedCornerShape(18.dp))
            .background(LibraryElevated)
            .onFocusChanged {
                if (it.hasFocus) {
                    focusVisuals.moveTo(item.id)
                    onFocused()
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .then(if (focused) Modifier.border(TvTokens.Geometry.FocusRingDp.dp, accent, RoundedCornerShape(18.dp)) else Modifier),
    ) {
        // Decode at the actual 164dp drawn width; larger textures upload during rail relocation.
        LibraryArtwork(
            item.posterURL,
            TvTokens.Geometry.PosterCardWidthDp,
            artworkLoader,
            Modifier.fillMaxSize().graphicsLayer { scaleX = imageScale; scaleY = imageScale },
            1f,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(118.dp)
                .graphicsLayer { alpha = metadataAlpha }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.45f to LibraryBlack.copy(alpha = 0.54f),
                            1f to LibraryBlack.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )
        Text(
            item.title,
            color = LibraryText,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = metadataAlpha }
                .padding(13.dp),
        )
    }
}

@Composable
private fun TamilMVDetailOverlay(
    item: TVTamilMVCatalogItem,
    collectionLabel: String,
    artworkLoader: ArtworkLoader,
    sourceState: TVAddonSourceState,
    onRetry: () -> Unit,
    onEpisodeSelected: (TVTamilMVCatalogItem) -> Unit,
    onPlay: (TVAddonPlayableSource) -> Unit,
    onClose: () -> Unit,
) {
    val itemKey = item.sourceKey
    val primaryFocus = remember(itemKey) { FocusRequester() }
    val loadingFocus = remember(itemKey) { FocusRequester() }
    val episodeFocus = remember(itemKey) { FocusRequester() }
    val stateForItem = when (sourceState) {
        is TVAddonSourceState.Loading -> sourceState.takeIf { it.itemID == itemKey }
        is TVAddonSourceState.Ready -> sourceState.takeIf { it.itemID == itemKey }
        is TVAddonSourceState.Error -> sourceState.takeIf { it.itemID == itemKey }
        TVAddonSourceState.Idle -> TVAddonSourceState.Idle
    } ?: TVAddonSourceState.Idle
    val result = (stateForItem as? TVAddonSourceState.Ready)?.result
    val episodes = result?.episodes.orEmpty()
    val seasons = episodes.map(TVAddonEpisode::season).distinct().sorted()
    var selectedSeason by remember(itemKey, seasons) {
        mutableStateOf(item.season?.let { candidate -> candidate.takeIf { it in seasons } } ?: seasons.firstOrNull())
    }
    LaunchedEffect(itemKey, seasons) {
        if (selectedSeason?.let { it !in seasons } == true) selectedSeason = seasons.firstOrNull()
    }
    val visibleEpisodes = episodes.filter { it.season == selectedSeason }
    val episodeUnselected = item.mediaType == "series" && (item.season == null || item.episode == null)
    val isTerminalSourceState = stateForItem is TVAddonSourceState.Ready || stateForItem is TVAddonSourceState.Error
    val initialFocusTarget = TVReceiverPresentationPolicy.initialDetailFocusTarget(
        isSeries = item.mediaType == "series",
        isTerminalSourceState = isTerminalSourceState,
        hasVisibleEpisodes = visibleEpisodes.isNotEmpty(),
    )
    val primaryTargetKey = when (stateForItem) {
        is TVAddonSourceState.Ready -> stateForItem.result.sources.firstOrNull()?.id ?: "ready-retry"
        is TVAddonSourceState.Error -> "error-retry"
        else -> null
    }
    var primaryTargetReady by remember(itemKey, primaryTargetKey) { mutableStateOf(false) }
    var episodeTargetReady by remember(itemKey, selectedSeason, visibleEpisodes.firstOrNull()) {
        mutableStateOf(false)
    }
    var initialFocusPlaced by remember(itemKey) { mutableStateOf(false) }
    val nonterminalFocusOwner = TVReceiverPresentationPolicy.nonterminalDetailFocusOwner(
        isIdle = stateForItem == TVAddonSourceState.Idle,
        isLoading = stateForItem is TVAddonSourceState.Loading,
    )
    // Loading keeps a temporary Close target, but does not consume the canonical placement. A
    // series therefore lands on Episodes once metadata arrives rather than remaining on Close.
    LaunchedEffect(itemKey, nonterminalFocusOwner) {
        if (nonterminalFocusOwner != TVReceiverPresentationPolicy.NonterminalDetailFocusOwner.None) {
            initialFocusPlaced = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
                previouslyPlaced = initialFocusPlaced,
                isTerminalSourceState = false,
                placementSucceeded = false,
            )
            withFrameNanos { }
            loadingFocus.requestFocus()
        }
    }
    val initialTargetReady = when (initialFocusTarget) {
        TVReceiverPresentationPolicy.InitialDetailFocusTarget.Episodes -> episodeTargetReady
        TVReceiverPresentationPolicy.InitialDetailFocusTarget.Primary -> primaryTargetReady
        TVReceiverPresentationPolicy.InitialDetailFocusTarget.Defer -> false
    }
    LaunchedEffect(itemKey, initialFocusTarget, initialTargetReady, visibleEpisodes.firstOrNull()) {
        val target = when (initialFocusTarget) {
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Episodes -> episodeFocus
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Primary -> primaryFocus
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Defer -> null
        }
        if (TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(initialFocusPlaced, target != null && initialTargetReady)) {
            // LazyVerticalGrid subcomposes its first source after the terminal result enters the
            // tree. A same-frame request can therefore target the removed Loading action and leave
            // the detail with no focused node. Retry for a bounded three frames; the effect is
            // composition-owned, so Back/item replacement cancels the sequence immediately.
            repeat(TVReceiverPresentationPolicy.InitialDetailFocusFrameBudget) {
                withFrameNanos { }
                if (target?.requestFocus() == true) {
                    initialFocusPlaced = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
                        previouslyPlaced = initialFocusPlaced,
                        isTerminalSourceState = isTerminalSourceState,
                        placementSucceeded = true,
                    )
                    return@LaunchedEffect
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LibraryBlack.copy(alpha = 0.96f))
            .focusGroup()
            .padding(
                start = TvTokens.Geometry.ScreenPaddingDp.dp,
                top = 76.dp,
                end = TvTokens.Geometry.ScreenPaddingDp.dp,
                bottom = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
            ),
    ) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            LibraryArtwork(
                url = item.posterURL,
                targetWidth = TvTokens.Geometry.DetailPosterWidthDp,
                loader = artworkLoader,
                modifier = Modifier
                    .width(TvTokens.Geometry.DetailPosterWidthDp.dp)
                    .height(TvTokens.Geometry.DetailPosterHeightDp.dp)
                    .clip(RoundedCornerShape(22.dp)),
                alpha = 1f,
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(collectionLabel, color = LibraryAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(7.dp))
                Text(
                    item.title,
                    color = LibraryText,
                    fontSize = 80.sp,
                    lineHeight = 76.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = LibraryDisplayFont,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        item.year?.toString(),
                        if (item.mediaType == "series") "Series" else "Movie",
                        item.season?.let { season -> item.episode?.let { episode -> "S$season · E$episode" } },
                        item.episodeTitle,
                    )
                        .plus(item.genres.take(3)).joinToString("  ·  "),
                    color = LibrarySecondary,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    item.overview ?: "No overview metadata was returned for this title.",
                    color = LibraryText.copy(alpha = 0.84f),
                    fontSize = 20.sp,
                    lineHeight = 31.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.mediaType == "series" && result != null) {
                    Spacer(Modifier.height(9.dp))
                    Text("SEASONS", color = LibraryAccent, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(5.dp))
                    if (seasons.isEmpty()) {
                        Text("No episode metadata was returned by the configured addons yet.", color = LibrarySecondary, fontSize = 14.sp)
                    } else {
                        LazyRow(
                            modifier = Modifier.height(TvTokens.Geometry.CompactActionHeightDp.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons, key = { it }) { season ->
                                TamilMVControl(
                                    label = "S${season.toString().padStart(2, '0')}",
                                    selected = selectedSeason == season,
                                    compact = true,
                                    modifier = Modifier,
                                    onFocused = {},
                                    onClick = { selectedSeason = season },
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("EPISODES", color = LibraryAccent, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Spacer(Modifier.height(5.dp))
                        LazyRow(
                            modifier = Modifier.height(58.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            items(visibleEpisodes, key = { "${it.season}:${it.episode}" }) { episode ->
                                TVEpisodeSelector(
                                    episode = episode,
                                    selected = item.season == episode.season && item.episode == episode.episode,
                                    modifier = if (episode == visibleEpisodes.firstOrNull()) {
                                        Modifier
                                            .focusRequester(episodeFocus)
                                            .onGloballyPositioned { episodeTargetReady = true }
                                    } else {
                                        Modifier
                                    },
                                    onClick = {
                                        onEpisodeSelected(
                                            item.copy(
                                                season = episode.season,
                                                episode = episode.episode,
                                                episodeTitle = episode.title,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("SOURCES", color = LibraryAccent, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                when (stateForItem) {
                    is TVAddonSourceState.Loading -> {
                        Text(
                            "Checking configured addons… Each addon is isolated, so one slow provider cannot hide the others.",
                            color = LibrarySecondary,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TamilMVControl(
                            label = "Close",
                            selected = false,
                            modifier = Modifier.focusRequester(loadingFocus),
                            onFocused = {},
                            onClick = onClose,
                        )
                    }
                    is TVAddonSourceState.Ready -> {
                        val readyResult = stateForItem.result
                        if (readyResult.sources.isNotEmpty()) {
                            Text(
                                buildString {
                                    append("${readyResult.sources.size} direct source")
                                    if (readyResult.sources.size != 1) append('s')
                                    append(" · ${readyResult.attemptedAddons} addon")
                                    if (readyResult.attemptedAddons != 1) append('s')
                                    if (readyResult.failedAddons > 0) append(" · ${readyResult.failedAddons} unavailable")
                                },
                                color = if (readyResult.failedAddons > 0) TvTokens.Color.Warning else LibrarySecondary,
                                fontSize = 15.sp,
                            )
                            Spacer(Modifier.height(7.dp))
                            LazyVerticalGrid(
                                // Two columns keep four to six source blocks visible beside the
                                // episode chooser, even on Fire TV's 2x density canvas.
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.height(TvTokens.Geometry.DetailSourceGridHeightDp.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                gridItems(readyResult.sources, key = { it.id }) { source ->
                                    TVAddonSourceRow(
                                        source = source,
                                    modifier = if (source.id == readyResult.sources.firstOrNull()?.id) {
                                        Modifier
                                            .focusRequester(primaryFocus)
                                            .onGloballyPositioned { primaryTargetReady = true }
                                    } else {
                                        Modifier
                                    },
                                        onClick = { onPlay(source) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TamilMVControl("Refresh sources", false, onFocused = {}, onClick = onRetry)
                                TamilMVControl("Close", false, onFocused = {}, onClick = onClose)
                            }
                        } else {
                            val message = when {
                                item.mediaType == "series" && (item.season == null || item.episode == null) -> "Choose a season and episode to find exact sources."
                                item.mediaType == "series" && episodes.isEmpty() -> "No episode metadata was returned by the configured addons yet."
                                readyResult.attemptedAddons == 0 -> "No stream addons were found in the transferred TV settings. Pair the latest iPhone setup, then retry."
                                readyResult.torrentOnlyCount > 0 -> "Addons returned ${readyResult.torrentOnlyCount} torrent-only row${if (readyResult.torrentOnlyCount == 1) "" else "s"}. The TV needs a direct debrid URL before it can play."
                                readyResult.failedAddons == readyResult.attemptedAddons -> "Configured addons did not respond. Check the TV network, then retry."
                                else -> "No direct playable source was returned for this title."
                            }
                            Text(message, color = if (episodeUnselected) LibrarySecondary else TvTokens.Color.Warning, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TamilMVControl(
                                    "Retry",
                                    false,
                                    modifier = Modifier
                                        .focusRequester(primaryFocus)
                                        .onGloballyPositioned { primaryTargetReady = true },
                                    onFocused = {},
                                    onClick = onRetry,
                                )
                                TamilMVControl("Close", false, onFocused = {}, onClick = onClose)
                            }
                        }
                    }
                    is TVAddonSourceState.Error -> {
                        Text(stateForItem.message, color = TvTokens.Color.Warning, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TamilMVControl(
                                "Retry",
                                false,
                                modifier = Modifier
                                    .focusRequester(primaryFocus)
                                    .onGloballyPositioned { primaryTargetReady = true },
                                onFocused = {},
                                onClick = onRetry,
                            )
                            TamilMVControl("Close", false, onFocused = {}, onClick = onClose)
                        }
                    }
                    TVAddonSourceState.Idle -> {
                        Text("Choose Find sources to query the addons copied from your phone.", color = LibrarySecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TamilMVControl("Find sources", false, modifier = Modifier.focusRequester(loadingFocus), onFocused = {}, onClick = onRetry)
                            TamilMVControl("Close", false, onFocused = {}, onClick = onClose)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TVEpisodeSelector(
    episode: TVAddonEpisode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember(episode.season, episode.episode) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(190.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) LibraryAccent else if (selected) LibraryElevatedFocused else LibraryElevated)
            .border(
                if (focused) TvTokens.Geometry.FocusRingDp.dp else if (selected) 2.dp else 1.dp,
                if (focused) LibraryText else if (selected) LibraryAccent else LibraryText.copy(alpha = 0.14f),
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "E${episode.episode.toString().padStart(2, '0')}",
            color = if (focused) LibraryBlack else LibraryAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = LibraryDataFont,
        )
        Text(
            episode.title,
            color = if (focused) LibraryBlack else LibraryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TVAddonSourceRow(
    source: TVAddonPlayableSource,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember(source.id) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) LibraryAccent else LibraryElevated)
            .border(
                TvTokens.Geometry.FocusRingDp.dp,
                if (focused) LibraryText else LibraryText.copy(alpha = 0.12f),
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                source.title,
                color = if (focused) LibraryBlack else LibraryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    source.addonName,
                    source.quality,
                    TVReceiverPresentationPolicy.sourceSizeBytes(source.sizeBytes)?.let(::formatSourceSize),
                    source.detail.takeIf(String::isNotBlank),
                ).joinToString("  ·  "),
                color = if (focused) LibraryBlack.copy(alpha = 0.72f) else LibrarySecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (focused) LibraryBlack else LibraryAccent),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.26f, size.height * 0.12f)
                    lineTo(size.width * 0.82f, size.height * 0.50f)
                    lineTo(size.width * 0.26f, size.height * 0.88f)
                    close()
                }
                drawPath(path, if (focused) LibraryAccent else LibraryBlack)
            }
        }
    }
}

private fun formatSourceSize(bytes: Long): String {
    val gib = bytes.toDouble() / 1_073_741_824.0
    return if (gib >= 1.0) String.format(Locale.US, "%.1f GB", gib)
    else String.format(Locale.US, "%.0f MB", bytes.toDouble() / 1_048_576.0)
}

/**
 * The settled Continue artwork is a dedicated landscape stage. Focused title/details still update,
 * but this restart scope changes only after the existing focus settle, never on every D-pad tick.
 */
@Composable
private fun BoxScope.LibraryHeroBackdrop(
    artworkUrl: String?,
    artworkLoader: ArtworkLoader,
    portraitFallback: Boolean = false,
) {
    Crossfade(
        targetState = artworkUrl,
        animationSpec = tween(180, easing = TvTokens.Motion.Std),
        label = "settledHeroArtwork",
    ) { settledPosterUrl ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(LibraryBlack, LibrarySlate, LibraryElevated),
                    ),
                ),
        )
        if (settledPosterUrl != null) {
            // Fill the television canvas with a quiet crop first, then preserve the complete
            // subject in the FIT layer above. This avoids a poster-shaped island surrounded by
            // unused black real estate without sacrificing faces or title art.
            LibraryArtwork(
                url = settledPosterUrl,
                targetWidth = 1920,
                loader = artworkLoader,
                modifier = Modifier.fillMaxSize(),
                alpha = if (portraitFallback) 0.20f else 0.34f,
                alignment = Alignment.CenterEnd,
                contentScale = ContentScale.Crop,
            )
            // A full-width 1920x440 band is much wider than a normal 16:9 backdrop and crops away
            // most of its useful picture. Give the art its native landscape shape instead. This
            // also makes a portrait-only fallback honest: FIT shows the complete poster in the
            // same stage instead of zooming its centre across the hero.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.CenterEnd)
                    .background(LibraryBlack.copy(alpha = if (portraitFallback) 0.42f else 0.08f)),
            ) {
                LibraryArtwork(
                    url = settledPosterUrl,
                    // Request a high-resolution source for the 16:9 stage; the loader still
                    // subsamples the decoded bitmap to a bounded size for the receiver.
                    targetWidth = 1920,
                    loader = artworkLoader,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 1f,
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
    // Keep the copy readable without putting a near-black blanket over the actual title artwork.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTokens.Geometry.HeroHeightDp.dp)
            .align(Alignment.TopCenter)
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to LibraryBlack.copy(alpha = 0.72f),
                        0.42f to LibraryBlack.copy(alpha = 0.26f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            .background(Brush.verticalGradient(listOf(Color.Transparent, LibraryBlack.copy(alpha = 0.44f)))),
    )
}

/** Text and metadata change with the hero, but the poster rail below does not. */
@Composable
private fun LibraryHeroText(
    destination: TVLibraryDestination,
    settingsConfigured: Boolean,
    focusedItemState: State<RecentItem?>,
) {
    val focusedItem = focusedItemState.value
    when {
        destination == TVLibraryDestination.Jobs -> JobsHero(settingsConfigured)
        focusedItem != null -> ItemHero(focusedItem)
        else -> EmptyHero(destination, settingsConfigured)
    }
}

@Composable
private fun LibraryContent(
    destination: TVLibraryDestination,
    items: List<RecentItem>,
    focusedItemState: State<RecentItem?>,
    receiverAddress: String,
    settingsConfigured: Boolean,
    tamilJobStatus: TVJobStatus,
    letterboxdJobStatus: TVJobStatus,
    tmdbJobStatus: TVJobStatus,
    artworkLoader: ArtworkLoader,
    returnRailFocus: FocusRequester,
    contentEntryFocus: FocusRequester,
    heroFocus: FocusRequester,
    onContentFocused: () -> Unit,
    onHeroFocused: () -> Unit,
    shelfPresentation: Float,
    onItemFocused: (RecentItem) -> Unit,
    onOpenSettings: (TVSettingsDestination) -> Unit,
    onOpenItem: (RecentItem) -> Unit,
    onRefreshTamilMV: () -> Unit,
    onRefreshLetterboxd: () -> Unit,
    onRefreshTMDB: () -> Unit,
    onResync: () -> Unit,
) {
    val posterRailState = rememberLazyListState()
    // Continue cards never observe these values in composition. The single rail overlay reads
    // them from a layer block, avoiding old/new poster draw invalidation for every D-pad move.
    val focusedPosterIndex = remember(destination, items.map(RecentItem::dedupeKey)) {
        mutableIntStateOf(0)
    }
    val shelfHasFocus = remember(destination, items.map(RecentItem::dedupeKey)) {
        mutableStateOf(false)
    }
    // Do not write a composition-observed index for every focus hop. Repeated D-pad events would
    // otherwise recompose this whole hero/rail parent even though only the final rail position is
    // useful. The conflated request is consumed after the same settled-focus window as the hero.
    val posterRailRequests = remember(destination, items.map(RecentItem::dedupeKey)) {
        Channel<Int>(Channel.CONFLATED)
    }
    val posterExtentPx = with(LocalDensity.current) {
        (TvTokens.Geometry.PosterCardWidthDp + TvTokens.Geometry.RailGapDp).dp.roundToPx()
    }
    LaunchedEffect(destination, items.size, posterExtentPx, posterRailRequests) {
        if (destination != TVLibraryDestination.Jobs && items.isNotEmpty()) {
            while (true) {
                var settledIndex = posterRailRequests.receive()
                while (true) {
                    val next = withTimeoutOrNull(FOCUS_PREVIEW_SETTLE_MILLIS) {
                        posterRailRequests.receive()
                    } ?: break
                    settledIndex = next
                }
                posterRailState.scrollToItem(
                    index = 0,
                    scrollOffset = TVReceiverPresentationPolicy.railScrollOffset(settledIndex, posterExtentPx),
                )
            }
        }
    }
    val receiverSuffix = receiverAddress.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(LibraryBlack, LibrarySlate, LibraryElevated),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvTokens.Geometry.ScreenPaddingDp.dp,
                    top = 26.dp,
                    end = TvTokens.Geometry.ScreenPaddingDp.dp,
                    bottom = TvTokens.Geometry.ScreenVerticalPaddingDp.dp,
                ),
        ) {
            Text(
                "${destination.title.uppercase()}  ·  4789 TV$receiverSuffix",
                color = LibraryAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (destination == TVLibraryDestination.Jobs) {
                JobsHero(settingsConfigured)
                Spacer(Modifier.height(20.dp))
            }
            val focusedItem = focusedItemState.value
            var heroFocused by remember(destination) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (destination == TVLibraryDestination.Jobs) 0.dp else TvTokens.Geometry.HeroHeightDp.dp)
                    .graphicsLayer {
                        scaleX = 1f - 0.04f * shelfPresentation
                        scaleY = 1f - 0.04f * shelfPresentation
                    }
                    .then(
                        if (items.isNotEmpty()) {
                            Modifier
                                .focusRequester(heroFocus)
                                .focusProperties {
                                    up = returnRailFocus
                                    down = contentEntryFocus
                                }
                                .onFocusChanged {
                                    heroFocused = it.hasFocus
                                    if (it.hasFocus) onHeroFocused()
                                }
                                .graphicsLayer {
                                    scaleX = if (heroFocused) 1.012f else 1f
                                    scaleY = if (heroFocused) 1.012f else 1f
                                }
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (heroFocused) LibraryElevated.copy(alpha = 0.58f) else Color.Transparent)
                                .clickable(role = Role.Button) { focusedItem?.let(onOpenItem) }
                                .focusable()
                                .then(
                                    if (heroFocused) Modifier.border(
                                        TvTokens.Geometry.FocusRingDp.dp,
                                        TvTokens.accentFor(destination),
                                        RoundedCornerShape(22.dp),
                                    ) else Modifier,
                                )
                        } else Modifier
                    ),
                contentAlignment = Alignment.BottomStart,
            ) {
                LibraryHeroBackdrop(
                    focusedItemState.value?.backdropUrl ?: focusedItemState.value?.posterUrl,
                    artworkLoader,
                    portraitFallback = focusedItemState.value?.backdropUrl == null,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    LibraryHeroText(destination, settingsConfigured, focusedItemState)
                    if (heroFocused && focusedItem != null) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            if (focusedItem.positionMillis > 0L) "RESUME" else "PLAY",
                            color = TvTokens.Color.OnAccent,
                            fontFamily = LibraryDataFont,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(TvTokens.accentFor(destination))
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            if (destination == TVLibraryDestination.Jobs) {
                JobsRow(
                    settingsConfigured,
                    tamilJobStatus = tamilJobStatus,
                    letterboxdJobStatus = letterboxdJobStatus,
                    tmdbJobStatus = tmdbJobStatus,
                    onContentFocused,
                    { onOpenSettings(TVSettingsDestination.Pair) },
                    returnRailFocus,
                    contentEntryFocus,
                    onRefreshTamilMV,
                    onRefreshLetterboxd,
                    onRefreshTMDB,
                    onResync,
                )
            } else if (items.isNotEmpty()) {
                Text(
                    if (destination == TVLibraryDestination.Continue) "CONTINUE WATCHING" else "AVAILABLE ON THIS TV",
                    color = LibraryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(14.dp))
                // Compose only the visible window. A fixed Row starts an artwork coroutine for
                // every Continue item (including cards far off-screen); on a sixteen-item rail
                // that creates a burst of bitmap allocations and a long Fire OS GC. The local
                // bring-into-view policy above keeps LazyRow relocation a snap instead of an
                // animated transaction.
                Box(modifier = Modifier.height(360.dp).fillMaxWidth()) {
                    LazyRow(
                        state = posterRailState,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup()
                            .onFocusChanged { shelfHasFocus.value = it.hasFocus },
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(items, key = { _, item -> item.dedupeKey }) { index, item ->
                            PosterCard(
                                item = item,
                                artworkLoader = artworkLoader,
                                returnRailFocus = heroFocus.takeIf { index == 0 } ?: returnRailFocus.takeIf { index == 0 },
                                focusRequester = contentEntryFocus.takeIf { index == 0 },
                                onFocused = {
                                    focusedPosterIndex.intValue = index
                                    onContentFocused()
                                    posterRailRequests.trySend(index)
                                    onItemFocused(item)
                                },
                                onClick = { onOpenItem(item) },
                            )
                        }
                    }
                    // A fixed, non-interactive layer follows the focused card through snapped
                    // scrolling. It is the only Continue focus visual and cannot steal clicks.
                    Box(
                        modifier = Modifier
                            .width(TvTokens.Geometry.PosterCardWidthDp.dp)
                            .height(TvTokens.Geometry.PosterCardHeightDp.dp)
                            .graphicsLayer {
                                val focusedIndex = focusedPosterIndex.intValue
                                val firstVisibleIndex = posterRailState.firstVisibleItemIndex
                                val firstOffset = posterRailState.firstVisibleItemScrollOffset
                                alpha = if (shelfHasFocus.value) 1f else 0f
                                translationX =
                                    ((focusedIndex - firstVisibleIndex) * posterExtentPx - firstOffset).toFloat()
                            }
                            .border(
                                TvTokens.Geometry.FocusRingDp.dp,
                                TvTokens.accentFor(destination),
                                RoundedCornerShape(18.dp),
                            ),
                    )
                }
            } else {
                LibraryAction(
                    label = when {
                        !settingsConfigured -> "Pair or import settings"
                        destination == TVLibraryDestination.TamilMV -> "Review Addons & Catalogs"
                        else -> "Review source settings"
                    },
                    returnRailFocus = returnRailFocus,
                    focusRequester = contentEntryFocus,
                    onFocused = onContentFocused,
                    onClick = {
                        onOpenSettings(TVLibrarySurfacePolicy.settingsDestination(destination, settingsConfigured))
                    },
                )
            }
        }
    }
}

@Composable
private fun ItemHero(item: RecentItem) {
    Text(
        item.title,
        color = LibraryText,
        fontSize = 104.sp,
        lineHeight = 94.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = LibraryDisplayFont,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(0.58f),
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (item.fromPhone) "FROM IPHONE" else "ON THIS TV", color = LibraryAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        item.subtitle?.let { Text(it, color = LibrarySecondary, fontSize = 21.sp, maxLines = 1) }
        if (item.durationMillis > 0L) {
            Text(formatDuration(item.durationMillis), color = LibrarySecondary, fontSize = 21.sp)
        }
    }
    if (item.positionMillis > 0L && item.durationMillis > item.positionMillis) {
        Spacer(Modifier.height(12.dp))
        Text(
            "${formatDuration(item.durationMillis - item.positionMillis)} remaining",
            color = LibraryText.copy(alpha = 0.84f),
            fontSize = 21.sp,
        )
    }
    item.overview?.trim()?.takeIf(String::isNotEmpty)?.let { overview ->
        Spacer(Modifier.height(14.dp))
        Text(
            overview,
            color = LibraryText.copy(alpha = 0.90f),
            fontSize = 21.sp,
            lineHeight = 31.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.58f),
        )
    }
}

@Composable
private fun EmptyHero(destination: TVLibraryDestination, settingsConfigured: Boolean) {
    Text(destination.title, color = LibraryText, fontSize = 104.sp, lineHeight = 94.sp, fontWeight = FontWeight.ExtraBold, fontFamily = LibraryDisplayFont)
    Spacer(Modifier.height(14.dp))
    Text(
        when {
            !settingsConfigured -> "The collection shell is ready. Pair your iPhone or import a settings file to connect this source."
            destination == TVLibraryDestination.TamilMV -> "Your setup is present, but this TV has not received a Tamil MV catalog snapshot yet. Pair the latest iPhone build to copy posters and metadata."
            else -> "This source is configured for the standalone collection phase but has not produced TV-local items yet."
        },
        color = LibrarySecondary,
        fontSize = 21.sp,
        lineHeight = 31.sp,
        modifier = Modifier.fillMaxWidth(0.64f),
    )
}

@Composable
private fun JobsHero(settingsConfigured: Boolean) {
    Text("Collection jobs", color = LibraryText, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, fontFamily = LibraryDisplayFont)
    Spacer(Modifier.height(14.dp))
    Text(
        if (settingsConfigured) "Receiver services are running. Provider refreshes appear here as each standalone source is enabled."
        else "The receiver is online. Pair or import settings to unlock provider refresh jobs.",
        color = LibrarySecondary,
        fontSize = 18.sp,
        lineHeight = 27.sp,
        modifier = Modifier.fillMaxWidth(0.64f),
    )
}

@Composable
private fun JobsRow(
    settingsConfigured: Boolean,
    tamilJobStatus: TVJobStatus,
    letterboxdJobStatus: TVJobStatus,
    tmdbJobStatus: TVJobStatus,
    onFocused: () -> Unit,
    onOpenSettings: () -> Unit,
    returnRailFocus: FocusRequester,
    contentEntryFocus: FocusRequester,
    onRefreshTamilMV: () -> Unit,
    onRefreshLetterboxd: () -> Unit,
    onRefreshTMDB: () -> Unit,
    onResync: () -> Unit,
) {
    data class JobSpec(
        val title: String,
        val status: String,
        val detail: String,
        val action: (() -> Unit)?,
    )
    val jobs = listOf(
        JobSpec("Receiver", "RUNNING", "Local playback and phone control", null),
        JobSpec("Settings sync", if (settingsConfigured) "READY" else "WAITING", if (settingsConfigured) "Encrypted receiver copy available" else "Pair or import to continue", onOpenSettings),
        JobSpec("Tamil MV", tamilJobStatus.label, tamilJobStatus.detail, onRefreshTamilMV),
        JobSpec("Letterboxd", letterboxdJobStatus.label, letterboxdJobStatus.detail, onRefreshLetterboxd),
        JobSpec("TMDB", tmdbJobStatus.label, tmdbJobStatus.detail, onRefreshTMDB),
        JobSpec("Re-Sync iPhone", if (settingsConfigured) "CONNECTED" else "WAITING", if (settingsConfigured) "Open Pair & Sync to pull current settings" else "Pair an iPhone to enable sync", onResync),
    )
    val jobFocus = remember { List(jobs.size) { FocusRequester() } }
    Text("STATUS", color = LibraryText, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(14.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.height(420.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItemsIndexed(jobs, key = { _, job -> job.title }) { index, job ->
            JobCard(
                title = job.title,
                status = job.status,
                detail = job.detail,
                onFocused = onFocused,
                upFocus = TVReceiverPresentationPolicy.jobGridUp(index)?.let(jobFocus::get)
                    ?: returnRailFocus.takeIf { index in 1..3 },
                downFocus = TVReceiverPresentationPolicy.jobGridDown(index)?.let(jobFocus::get),
                focusRequester = when (index) {
                    1 -> contentEntryFocus
                    in 2..5 -> jobFocus[index]
                    else -> null
                },
                onClick = job.action,
            )
        }
    }
}

@Composable
private fun JobCard(
    title: String,
    status: String,
    detail: String,
    onFocused: () -> Unit,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(TvTokens.Motion.FocusScaleMillis, easing = TvTokens.Motion.Pop),
        label = "jobCardFocusScale",
    )
    val phaseColor = when (status) {
        "READY", "CONNECTED" -> TvTokens.Color.BrandGreen
        "RUNNING" -> TvTokens.Color.BrandOrange
        "WAITING" -> TvTokens.Color.Warning
        "FAILED", "BLOCKED" -> TvTokens.Color.Error
        else -> LibrarySecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .then(
                if (upFocus != null || downFocus != null) Modifier.focusProperties {
                    upFocus?.let { up = it }
                    downFocus?.let { down = it }
                }
                else Modifier,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
                translationY = if (focused) -6.dp.toPx() else 0f
            }
            .clip(RoundedCornerShape(22.dp))
            .background(if (focused) phaseColor else LibraryElevated)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .then(onClick?.let { Modifier.clickable(role = Role.Button, onClick = it).focusable() } ?: Modifier)
            .padding(22.dp),
    ) {
        Text("●  $status", color = if (focused) LibraryBlack else phaseColor, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        if (status == "RUNNING") {
            RunningJobStripe()
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = if (focused) LibraryBlack else LibraryText, fontSize = 27.sp, fontWeight = FontWeight.Bold, fontFamily = LibraryDisplayFont)
        Text(detail, color = if (focused) LibraryBlack.copy(alpha = 0.68f) else LibrarySecondary, fontSize = 13.sp, maxLines = 2)
        if (onClick != null) {
            Spacer(Modifier.height(8.dp))
            Text("SELECT TO RUN", color = if (focused) LibraryBlack.copy(alpha = 0.7f) else LibraryTertiary, fontFamily = LibraryDataFont, fontSize = 11.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun RunningJobStripe() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val animationsDisabled = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val offset = if (animationsDisabled) {
        0f
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "runningJobStripe")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(900, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
            ),
            label = "runningJobStripeOffset",
        )
        value
    }
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(LibraryBlack.copy(alpha = 0.18f))) {
        Box(
            Modifier
                .fillMaxWidth(0.38f)
                .height(3.dp)
                .graphicsLayer { translationX = offset * 120.dp.toPx() }
                .background(TvTokens.Color.BrandOrange),
        )
    }
}

@Composable
private fun PosterCard(
    item: RecentItem,
    artworkLoader: ArtworkLoader,
    returnRailFocus: FocusRequester?,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val runtimeMinutes = (item.durationMillis / 60_000L).toInt().takeIf { it > 0 }
    val progressPercent = runtimeMinutes?.let {
        if (item.durationMillis > 0L) ((item.positionMillis.coerceIn(0L, item.durationMillis) * 100L) / item.durationMillis).toInt() else null
    }
    // RecentItem has no authoritative media classification. A guessed MOVIE badge is worse than
    // no badge, so only the measured resume fact is eligible for this rail.
    val badge = TVReceiverPresentationPolicy.cardBadge(runtimeMinutes, progressPercent, isSeries = null)
    // Continue can receive 120ms remote repeats. The settled hero owns the rich motion; cards
    // deliberately keep static art/text/progress so only one lightweight ring layer changes.
    Box(
        modifier = Modifier
            .width(TvTokens.Geometry.PosterCardWidthDp.dp)
            .height(TvTokens.Geometry.PosterCardHeightDp.dp)
            .then(
                if (returnRailFocus != null) Modifier.focusProperties { up = returnRailFocus }
                else Modifier,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(RoundedCornerShape(18.dp))
            .background(LibraryElevated)
            .onFocusChanged {
                if (it.hasFocus) {
                    onFocused()
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable(),
    ) {
        LibraryArtwork(
            url = item.posterUrl,
            targetWidth = TvTokens.Geometry.PosterCardWidthDp,
            loader = artworkLoader,
            modifier = Modifier.fillMaxSize(),
            alpha = 1f,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            0.78f to LibraryBlack.copy(alpha = 0.58f),
                            1f to LibraryBlack.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        )
        if (badge != null) {
            Text(
                badge,
                color = LibraryText,
                fontFamily = LibraryDataFont,
                fontSize = 13.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(LibraryBlack.copy(alpha = 0.78f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                item.title,
                color = LibraryText,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.durationMillis > 0L) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LibraryText.copy(alpha = 0.2f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((item.positionMillis.toFloat() / item.durationMillis.toFloat()).coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(LibraryAccent),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryArtwork(
    url: String?,
    targetWidth: Int,
    loader: ArtworkLoader,
    modifier: Modifier,
    alpha: Float,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url, key2 = targetWidth) {
        // `produceState` starts on the main dispatcher.  Cache hits therefore used to run
        // Bitmap -> ImageBitmap conversion on the UI thread even though network/decode work was
        // already off-main.  Cards entering the rail during D-pad navigation could consequently
        // block a frame while the renderer uploaded a new bitmap. Keep both the cache lookup and
        // wrapper conversion on a worker; only the immutable ImageBitmap result is published here.
        value = url?.takeIf { it.isNotBlank() }?.let { imageURL ->
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                loader.load(imageURL, targetWidth)?.asImageBitmap()
            }
        }
    }
    val artwork = bitmap
    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = null,
            contentScale = contentScale,
            alignment = alignment,
            alpha = alpha,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    listOf(LibraryElevated, LibraryElevatedFocused, TvTokens.Color.TvCyan.copy(alpha = 0.25f)),
                ),
            ),
        )
    }
}

@Composable
private fun LibraryAction(
    label: String,
    returnRailFocus: FocusRequester,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .focusProperties { up = returnRailFocus }
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .heightIn(min = TvTokens.Geometry.PrimaryActionHeightDp.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) LibraryAccent else LibraryElevated)
            .border(1.dp, if (focused) LibraryText else LibraryText.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) LibraryBlack else LibraryText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun itemsFor(destination: TVLibraryDestination, items: List<RecentItem>): List<RecentItem> =
    when (destination) {
        TVLibraryDestination.Continue -> items
        else -> emptyList()
    }

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
