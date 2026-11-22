package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.catalog.LetterboxdListInformation
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdList
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.settings.PairingQRCode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

@Composable
internal fun CollectionsViewPanel(
    current: CollectionsView, active: Boolean, onClose: () -> Unit, onSelect: (CollectionsView) -> Unit,
) {
    val focus = remember { FocusRequester() }
    CollectionOverlay(active, onClose)
    SidePanel("Collections filters", onClose) {
        LaunchedEffect(active) { if (active) focus.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            CollectionsView.entries.forEach { option ->
                SidePanelRow(option.label, selected = option == current,
                    modifier = if (option == current) Modifier.focusRequester(focus) else Modifier,
                    onClick = { onSelect(option) })
                Text(option.note, style = TvType.Meta, color = TvColor.TextSecondary,
                    modifier = Modifier.padding(horizontal = TvSpace.S))
            }
        }
    }
}

@Composable
internal fun CollectionResearchPanel(
    shelf: Shelf, count: Int?, cached: LetterboxdListInformation?, active: Boolean, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.clientGraph
    val key = shelf.researchKey() ?: return
    val link = remember(key) { TVLetterboxdClient.importLink("https://letterboxd.com/${key.substringBefore('/')}/list/${key.substringAfter('/')}/") }
    val focus = remember { FocusRequester() }
    var information by remember(key, cached) { mutableStateOf(cached) }
    var artwork by remember(key) { mutableStateOf<String?>(null) }
    var showSource by remember(key) { mutableStateOf(false) }
    var fullNote by remember(key) { mutableStateOf(false) }
    val covers = remember(shelf.items) { shelf.items.mapNotNull { it.posterUrl }.distinct().take(4) }

    CollectionOverlay(active, onClose)
    LaunchedEffect(key, cached, link) {
        val existing = graph.offlineLetterboxd.listInformation(key) ?: cached
        information = existing
        if (existing != null) artwork = graph.offlineLetterboxd.backdrop(existing)
        if (link != null && existing?.sourceAvailable != true) {
            try {
                val list = TVLetterboxdClient.listLink(link) ?: return@LaunchedEffect
                val fresh = graph.letterboxd.listInformation(TVLetterboxdList(list.owner, list.slug, shelf.title))
                    ?.copy(likedRank = existing?.likedRank, syncedAtMillis = existing?.syncedAtMillis)
                if (fresh != null) {
                    graph.offlineLetterboxd.saveListInformation(key, fresh)
                    information = fresh
                    artwork = graph.offlineLetterboxd.backdrop(fresh)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The stored title, dates and covers remain available offline.
            }
        }
    }
    SidePanel("List information", onClose, width = 860.dp) {
        LaunchedEffect(active, link) { if (active && link != null) focus.requestFocus() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            if (showSource && link != null) {
                val qr = remember(link) { PairingQRCode.render(link).asImageBitmap() }
                Row(Modifier.fillMaxWidth().height(224.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
                    Image(qr, "Scan to open this list on Letterboxd", Modifier.size(212.dp)
                        .clip(TvShape.Control).background(TvColor.TextPrimary).padding(10.dp))
                    Text(link, style = TvType.Meta, color = TvColor.TextSecondary)
                }
            } else {
                CollectionResearchArtwork(artwork, covers)
            }
            Text(shelf.title, style = TvType.PlateTitle, color = TvColor.TextPrimary,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            val entries = count ?: shelf.items.size
            Text("By ${key.substringBefore('/')} · ${NumberFormat.getIntegerInstance().format(entries)} " +
                if (entries == 1) "title" else "titles", style = TvType.Meta, color = TvColor.TextSecondary)
            val details = information
            val dates = listOfNotNull(
                details?.publishedAt?.let(::publicDate)?.let { "Published $it" },
                details?.updatedAt?.let(::publicDate)?.let { "Updated $it" },
                details?.syncedAtMillis?.let { date(Instant.ofEpochMilli(it)) }?.let { "Synced $it" },
            )
            if (dates.isNotEmpty()) Text(dates.joinToString(" · "), style = TvType.Meta, color = TvColor.TextSecondary)
            if (details?.likedRank != null) Text("Liked by Cineorgasm", style = TvType.Meta, color = TvColor.TextSecondary)
            if (link != null) SidePanelRow(if (showSource) "Show list artwork" else "Show source QR",
                selected = showSource, modifier = Modifier.focusRequester(focus),
                onClick = { showSource = !showSource })
            details?.description?.let { note ->
                Text(note, style = TvType.Body, color = TvColor.TextPrimary,
                    maxLines = if (fullNote) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                if (note.length > 320) SidePanelRow(if (fullNote) "Show less" else "Read description",
                    selected = false, onClick = { fullNote = !fullNote })
            }
        }
    }
}

@Composable
private fun CollectionResearchArtwork(backdrop: String?, covers: List<String>) {
    val graph = LocalContext.current.clientGraph
    var failed by remember(backdrop) { mutableStateOf(false) }
    if (backdrop == null && covers.isEmpty()) return
    Box(Modifier.fillMaxWidth().height(214.dp).clip(TvShape.Control).background(TvColor.Elevated2)) {
        if (backdrop != null && !failed) {
            AsyncImage(backdrop, contentDescription = null, imageLoader = graph.imageLoader,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                covers.forEach { poster ->
                    AsyncImage(poster, contentDescription = null, imageLoader = graph.imageLoader,
                        modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun CollectionOverlay(active: Boolean, onClose: () -> Unit) {
    val shell = LocalShellState.current
    BackHandler(enabled = active, onBack = onClose)
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val previous = shell.overlayVisible
        shell.overlayVisible = true
        onDispose { shell.overlayVisible = previous }
    }
}

private fun publicDate(value: String): String? = runCatching { date(Instant.parse(value)) }.getOrNull()

private fun date(value: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(value.atZone(ZoneId.systemDefault()))
