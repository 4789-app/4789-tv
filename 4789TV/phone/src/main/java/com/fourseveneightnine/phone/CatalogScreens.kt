package com.fourseveneightnine.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.fourseveneightnine.contract.CatalogPresentationPolicy
import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.contract.PhoneDoor

@Composable
internal fun DoorContent(
    door: PhoneDoor,
    state: CatalogLoadState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    mediaFilter: CatalogMediaFilter,
    onMediaFilterChange: (CatalogMediaFilter) -> Unit,
    onSelect: (DiscoverItem) -> Unit,
    onSettings: () -> Unit,
) {
    when (state) {
        CatalogLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is CatalogLoadState.Failed -> Box(
            Modifier.fillMaxSize().padding(28.dp),
            contentAlignment = Alignment.Center,
        ) { Text(state.message) }
        is CatalogLoadState.Ready -> when (door) {
            PhoneDoor.Discover -> DiscoverScreen(
                CatalogPresentationPolicy.discover(
                    CatalogSearchPolicy.filter(state.items, searchQuery, mediaFilter),
                ),
                state.isLive,
                searchQuery,
                onSearchQueryChange,
                mediaFilter,
                onMediaFilterChange,
                onSelect,
                onSettings,
            )
            PhoneDoor.Wall -> WallScreen(
                CatalogPresentationPolicy.wall(
                    CatalogSearchPolicy.filter(state.items, searchQuery, mediaFilter),
                ),
                state.isLive,
                searchQuery,
                onSearchQueryChange,
                mediaFilter,
                onMediaFilterChange,
                onSelect,
                onSettings,
            )
        }
    }
}

@Composable
private fun DiscoverScreen(
    items: List<DiscoverItem>,
    isLive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    mediaFilter: CatalogMediaFilter,
    onMediaFilterChange: (CatalogMediaFilter) -> Unit,
    onSelect: (DiscoverItem) -> Unit,
    onSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Discover",
                modifier = Modifier.testTag("door-discover"),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (isLive) "Verified public catalog" else "Bundled cross-platform catalog",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Settings") }
            CatalogSearchField(searchQuery, onSearchQueryChange)
            CatalogMediaFilters(mediaFilter, onMediaFilterChange)
            Spacer(Modifier.height(12.dp))
        }
        if (items.isEmpty()) {
            item { Text("No matching titles.", modifier = Modifier.testTag("catalog-empty")) }
        }

        // Shelves are for browsing. Someone who has typed a query is looking for one title, and
        // scattering the matches across a dozen headings would hide it, so a search falls back to
        // the flat list.
        val shelves = if (searchQuery.isBlank()) DiscoverShelfPolicy.shelves(items) else emptyList()
        if (shelves.isEmpty()) {
            items(items, key = { it.id }) { item ->
                CatalogRow(item, onSelect)
            }
        } else {
            items(shelves, key = DiscoverShelf::title) { shelf ->
                DiscoverShelfRow(shelf, onSelect)
            }
        }
    }
}

@Composable
private fun DiscoverShelfRow(shelf: DiscoverShelf, onSelect: (DiscoverItem) -> Unit) {
    Column(
        modifier = Modifier.padding(bottom = Spacing.sm).testTag("discover-shelf"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            shelf.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items(shelf.items, key = { it.id }) { item ->
                ShelfPoster(item, onSelect)
            }
        }
    }
}

/**
 * A poster on a shelf.
 *
 * Taller than it is wide, at the 2:3 a film poster is printed at, so real artwork fills the frame
 * instead of being cropped to a square.
 */
@Composable
private fun ShelfPoster(item: DiscoverItem, onSelect: (DiscoverItem) -> Unit) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .testTag("poster-tile")
            .clickable { onSelect(item) }
            .semantics { contentDescription = "Open ${item.title}" },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        CatalogMark(
            item,
            Modifier
                .fillMaxWidth()
                .height(186.dp)
                .clip(RoundedCornerShape(Radius.tile)),
            contentDescription = null,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WallScreen(
    items: List<DiscoverItem>,
    isLive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    mediaFilter: CatalogMediaFilter,
    onMediaFilterChange: (CatalogMediaFilter) -> Unit,
    onSelect: (DiscoverItem) -> Unit,
    onSettings: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text(
                    "Wall",
                    modifier = Modifier.testTag("door-wall"),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    if (isLive) "Verified public catalog" else "Rating-ranked bundled catalog",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Settings") }
                CatalogSearchField(searchQuery, onSearchQueryChange)
                CatalogMediaFilters(mediaFilter, onMediaFilterChange)
            }
        }
        if (items.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "No matching titles.",
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("catalog-empty"),
                )
            }
        }
        items(items, key = { it.id }) { item ->
            PosterTile(item, onSelect)
        }
    }
}

@Composable
private fun CatalogSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(CatalogSearchPolicy.boundedQuery(it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("catalog-search"),
        label = { Text("Search catalog") },
        singleLine = true,
    )
}

@Composable
private fun CatalogMediaFilters(
    selected: CatalogMediaFilter,
    onSelect: (CatalogMediaFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        items(CatalogMediaFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.name) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("catalog-filter-${filter.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun CatalogRow(item: DiscoverItem, onSelect: (DiscoverItem) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .clickable { onSelect(item) }
            .semantics { contentDescription = "Open ${item.title}" },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CatalogMark(item, Modifier.size(56.dp), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metadata(item), style = MaterialTheme.typography.bodySmall)
                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PosterTile(item: DiscoverItem, onSelect: (DiscoverItem) -> Unit) {
    Card(
        modifier = Modifier
            .testTag("poster-tile")
            .height(184.dp)
            .clickable { onSelect(item) }
            .semantics { contentDescription = "Open ${item.title}" },
    ) {
        Column(Modifier.fillMaxSize()) {
            CatalogMark(item, Modifier.fillMaxWidth().weight(1f), contentDescription = null)
            Text(
                item.title,
                modifier = Modifier.padding(10.dp),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CatalogMark(item: DiscoverItem, modifier: Modifier, contentDescription: String?) {
    val hue = item.id.hashCode().toUInt().toLong() % 360
    val base = Color.hsv(hue.toFloat(), 0.62f, 0.82f)
    val semanticModifier = if (contentDescription == null) {
        modifier.clearAndSetSemantics { }
    } else {
        modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = semanticModifier.background(
            Brush.linearGradient(listOf(base, base.copy(alpha = 0.45f))),
            RoundedCornerShape(12.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (!item.posterURL.isNullOrBlank()) {
            AsyncImage(
                model = item.posterURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                item.title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
fun DetailScreen(
    item: DiscoverItem?,
    resumePercent: Int?,
    offlineAvailable: Boolean,
    offlineStatus: String?,
    downloadStatus: String?,
    onBack: () -> Unit,
    onOpenLocal: () -> Unit,
    onPlayOffline: () -> Unit,
    onImportOffline: () -> Unit,
    onRemoveOffline: () -> Unit,
    onOpenRemote: () -> Unit,
    onConfigureSource: () -> Unit,
) {
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("detail-list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } }
        item {
            CatalogMark(
                item,
                Modifier.fillMaxWidth().height(220.dp),
                contentDescription = "Poster for ${item.title}",
            )
        }
        item {
            Text(
                item.title,
                modifier = Modifier.testTag("detail-title"),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
            )
        }
        item { Text(metadata(item), style = MaterialTheme.typography.titleMedium) }
        item { Text(item.description ?: "No description in the frozen contract snapshot.") }
        if (item.genres.isNotEmpty()) {
            item { Text(item.genres.joinToString(" • "), style = MaterialTheme.typography.labelLarge) }
        }
        if (resumePercent != null) {
            item {
                Text(
                    "Playback saved at $resumePercent%. Reselect the local file or choose a " +
                        "fresh remote source to resume.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        offlineStatus?.let { message -> item { Text(message) } }
        downloadStatus?.let { message ->
            item {
                Text(
                    message,
                    modifier = Modifier.testTag("detail-download-status"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (offlineAvailable) {
            item {
                Button(onClick = onPlayOffline, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Play downloaded copy")
                }
            }
            item {
                Button(onClick = onRemoveOffline, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Remove downloaded copy")
                }
            }
        } else {
            item {
                Button(onClick = onImportOffline, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Keep a local video offline")
                }
            }
        }
        item {
            Button(onClick = onOpenLocal, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Play a local video")
            }
        }
        item {
            Button(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Find remote sources")
            }
        }
        item {
            Button(onClick = onConfigureSource, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Source configuration")
            }
        }
    }
}

@Composable
fun DoorSwitcher(
    selected: PhoneDoor,
    onSelect: (PhoneDoor) -> Unit,
) {
    val reduced = rememberReducedMotion()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            // Android has no backdrop blur in Compose, so Liquid Glass cannot be copied directly.
            // A translucent lift over the canvas plus a hairline edge is the honest equivalent: it
            // reads as a floating layer without pretending to refract what is behind it.
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            shadowElevation = 12.dp,
        ) {
            Row(Modifier.padding(Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PhoneDoor.entries.forEach { door ->
                    DoorSegment(door, isSelected = selected == door, reduced = reduced) { onSelect(door) }
                }
            }
        }
    }
}

/**
 * One half of the door switcher.
 *
 * The old bar filled the door you were NOT on and greyed out the one you were, which reads as the
 * opposite of what it means. Here the current door carries the brand fill.
 */
@Composable
private fun DoorSegment(
    door: PhoneDoor,
    isSelected: Boolean,
    reduced: Boolean,
    onSelect: () -> Unit,
) {
    val fill by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = tween(Motion.element(reduced)),
        label = "door-fill",
    )
    val ink by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(Motion.element(reduced)),
        label = "door-ink",
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onSelect)
            .widthIn(min = 132.dp)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Open ${door.name}"
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(door.name, color = ink, style = MaterialTheme.typography.titleSmall)
    }
}

private fun metadata(item: DiscoverItem): String = buildList {
    item.year?.let { add(it.toString()) }
    item.rating?.let { add("★ %.1f".format(it)) }
    item.runtime?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString("  •  ")
