package com.fourseveneightnine.tv.client.home

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.util.LruCache
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.client.ui.screens.collections.SyncedLists
import com.fourseveneightnine.tv.client.ui.screens.home.HomePlan
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal object LauncherRails {
    internal const val PACKAGE = "dev.dentv.home.preview"
    private const val PIN_ACTIVITY = "dev.dentv.home.RailPinActivity"
    private const val PIN_RESULT = "dev.dentv.home.extra.PIN_RESULT"

    fun catalog(context: Context, key: String, genre: String?, onError: (String) -> Unit) =
        pin(context, catalogUri(context, key, genre), onError)

    fun snapshot(context: Context, key: String, onError: (String) -> Unit) =
        pin(context, snapshotUri(context, key), onError)

    fun collection(context: Context, id: Long, onError: (String) -> Unit) {
        val uri = collectionUri(context, id)
        if (uri == null) onError("Den TV Home can show collections from profiles without a PIN.")
        else pin(context, uri, onError)
    }

    fun catalogUri(context: Context, key: String, genre: String?): Uri =
        railUri(context, listOfNotNull("catalog", launcherKey(key), genre?.let(::launcherKey)))

    fun snapshotUri(context: Context, key: String): Uri = railUri(context, listOf("snapshot", launcherKey(key)))

    fun collectionUri(context: Context, id: Long): Uri? {
        val graph = context.clientGraph
        val profileId = graph.activeProfileId() ?: return null
        if (graph.launcherLibrary(profileId) == null) return null
        return railUri(context, listOf("collection", profileId.toString(), id.toString()))
    }

    /** A catalog may already have a pinned rail for a genre selected in an older build. */
    fun pinnedRows(context: Context, uri: Uri): List<Uri> {
        val catalog = uri.pathSegments.getOrNull(1) == "catalog"
        return context.contentResolver.outgoingPersistedUriPermissions.mapNotNull { permission ->
            val granted = permission.uri
            granted.takeIf {
                permission.isReadPermission && granted.authority == uri.authority &&
                    (granted == uri || (catalog && granted.pathSegments.take(3) == uri.pathSegments.take(3)))
            }
        }
    }

    fun remove(context: Context, rows: List<Uri>) {
        rows.forEach { context.revokeUriPermission(PACKAGE, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun railUri(context: Context, path: List<String>): Uri =
        Uri.Builder().scheme("content").authority(launcherAuthority(context)).appendPath("v1")
            .apply { path.forEach { appendPath(it) } }.build()

    fun pin(context: Context, uri: Uri, onMessage: (String) -> Unit) {
        val callback = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                onMessage(if (resultCode == Activity.RESULT_OK) "Added to Den TV Home."
                    else "Couldn't add this row to Den TV Home.")
            }
        }
        // Send the platform ResultReceiver class across packages, with the local Binder callback.
        val parcel = Parcel.obtain()
        val receiver = try {
            callback.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        val intent = Intent().setClassName(PACKAGE, PIN_ACTIVITY).setData(uri).putExtra(PIN_RESULT, receiver).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onMessage("Install or update Den TV Home to add this row.")
        }
    }
}

private fun launcherAuthority(context: Context) = "${context.packageName}.launcher"
private val opaqueKey = Regex("[a-f0-9]{64}")
private const val MAX_CARDS = 24
private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024L
private const val MAX_HERO_BYTES = 8 * 1024 * 1024L

private fun launcherKey(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private data class Card(
    val title: String,
    val type: String,
    val id: String,
    val art: String?,
    val subtitle: String? = null,
    val progress: Float? = null,
    val backdrop: String? = null,
)

private data class Row(val title: String, val cards: List<Card>, val personal: UUID? = null, val resume: Boolean = false)

private fun CollectionItem.card() = Card(title, mediaType, canonicalId, posterUrl)

private data class Art(val url: String, val limit: Long)
private class ShelfRead(val cards: Map<String, Card>) {
    val art = LruCache<String, Art>(96)
}

class LauncherRailProvider : ContentProvider() {
    private val rendered = LruCache<Uri, ShelfRead>(64)

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (callingPackage != LauncherRails.PACKAGE) return null
        val app = context ?: return null
        val path = path(uri, app) ?: return null
        val graph = app.clientGraph
        graph.start()
        if (path.getOrNull(path.lastIndex - 1) == "hero") {
            return hero(graph, app, path.dropLast(2), path.last())
        }
        val row = runBlocking(Dispatchers.IO) { withTimeoutOrNull(7_000) { load(graph, path) } } ?: return null
        if (row.personal?.let { graph.launcherLibrary(it) == null } == true) return null

        val cursor = MatrixCursor(arrayOf("title", "artwork", "target", "subtitle", "progress", "hero"))
        val cards = row.cards.asSequence().distinctBy { it.type to it.id }.take(MAX_CARDS)
            .associateBy { launcherKey("${it.type}|${it.id}") }
        val shelf = ShelfRead(cards)
        cursor.extras = Bundle().apply {
            putString("title", row.title.take(120))
            putBoolean("resume", row.resume)
        }
        cards.forEach { (key, card) ->
            val authority = "${app.packageName}.search"
            val target = if (row.resume && row.personal != null) {
                SystemSearchIntentParser.resumeUri(authority, row.personal, card.type, card.id)
            } else {
                SystemSearchIntentParser.launcherDetailUri(authority, card.type, card.id)
            }
            val width = if (row.resume) TmdbSize.WIDE_WIDTH else TmdbSize.POSTER_WIDTH
            val artUri = card.art?.let { proxy(uri, shelf, TmdbSize.sized(it, width), MAX_IMAGE_BYTES) }
            val heroUri = uri.buildUpon().appendPath("hero").appendPath(key).build()
            cursor.addRow(arrayOf(card.title.take(240), artUri, target, card.subtitle, card.progress, heroUri.toString()))
        }
        rendered.put(uri, shelf)
        return cursor
    }

    private suspend fun load(graph: ClientGraph, path: List<String>): Row? {
        when (path.firstOrNull()) {
            "collection" -> {
                if (path.size != 3) return null
                val profile = profileId(path[1]) ?: return null
                val id = path[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
                val library = graph.launcherLibrary(profile) ?: return null
                val detail = library.collection(id).first() ?: return null
                return when (detail.sourceRef) {
                    SystemCollections.MY_CLOUD_REF -> null
                    SystemCollections.CONTINUE_WATCHING_REF -> Row(detail.name, library.continueWatching().first().take(MAX_CARDS).map {
                        Card(it.title, it.mediaType, it.canonicalId, it.backdropUrl ?: it.posterUrl,
                            HomePlan.continueLine(it).ifEmpty { null },
                            if (it.durationMs > 0) it.progressFraction else null, backdrop = it.backdropUrl)
                    }, personal = profile, resume = true)
                    else -> Row(detail.name, detail.items.take(MAX_CARDS).map(CollectionItem::card), personal = profile)
                }
            }
            "catalog" -> {
                val key = path.getOrNull(1)?.takeIf(opaqueKey::matches) ?: return null
                val genreKey = path.getOrNull(2)
                if (path.size !in 2..3 || genreKey?.let { !opaqueKey.matches(it) } == true) return null
                val services = graph.services.first { it != null } ?: return null
                fun match() = services.registry.catalogs().firstOrNull { (addon, catalog) ->
                    launcherKey(catalog.uid(addon.manifestURL)) == key
                }
                val (addon, catalog) = match() ?: run {
                    services.registry.addons.first { match() != null }
                    match() ?: return null
                }
                val genre = if (genreKey == null) catalog.requiredBrowseGenre else {
                    catalog.genres.firstOrNull { launcherKey(it) == genreKey } ?: return null
                }
                val page = try {
                    services.client.catalog(addon, catalog.type, catalog.id, CatalogExtra(genre = genre))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return null
                }
                val label = listOfNotNull(catalog.name.ifBlank { catalog.id }, genre).joinToString(" · ")
                return Row(label, page.items.take(MAX_CARDS).map {
                    Card(it.title, it.type, it.id, it.posterURL, backdrop = it.backdropURL)
                })
            }
            "snapshot" -> {
                val key = path.getOrNull(1)?.takeIf(opaqueKey::matches) ?: return null
                if (path.size != 2) return null
                val bundled = graph.offlineLetterboxd.directory(localArtwork = false).shelves
                    .firstOrNull { launcherKey(it.id) == key }
                val shelf = bundled ?: graph.collectionShelves
                    .first { shelves -> SyncedLists.visible(shelves).any { launcherKey(it.id) == key } }
                    .let(SyncedLists::visible).first { launcherKey(it.id) == key }
                return Row(shelf.title, shelf.items.take(MAX_CARDS).map {
                    Card(it.title, it.mediaType, it.canonicalId, it.posterUrl, backdrop = it.backdropUrl)
                })
            }
            else -> return null
        }
    }

    private fun hero(graph: ClientGraph, app: Context, path: List<String>, key: String): Cursor? {
        if (!profileAllowed(graph, path)) return null
        val uri = shelfUri(app, path)
        val shelf = rendered.get(uri) ?: return null
        val card = shelf.cards[key] ?: return null
        val meta = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(4_000) {
                if (card.id.startsWith(com.fourseveneightnine.tv.client.playback.WatchlistSource.PREFIX)) null
                else graph.services.value?.meta?.meta(card.type, card.id)
            }
        }
        if (!profileAllowed(graph, path)) return null
        val availableBackdrops = listOfNotNull(card.backdrop, meta?.backdrop)
        val bestBackdrop = availableBackdrops.firstOrNull { it.toHttpUrlOrNull()?.host == "image.tmdb.org" }
            ?: meta?.backdrop ?: card.backdrop
        val backdrop = bestBackdrop?.let { proxy(uri, shelf, TmdbSize.sized(it, 3840), MAX_HERO_BYTES) }
        val logo = meta?.logo?.let { proxy(uri, shelf, TmdbSize.logo(it, 800), MAX_IMAGE_BYTES) }
        return MatrixCursor(arrayOf("backdrop", "logo")).apply { addRow(arrayOf(backdrop, logo)) }
    }

    private fun proxy(uri: Uri, shelf: ShelfRead, url: String, limit: Long): String? {
        if (url.toHttpUrlOrNull() == null) return null
        val key = launcherKey(url)
        shelf.art.put(key, Art(url, limit))
        return uri.buildUpon().appendPath("art").appendPath(key).build().toString()
    }

    private fun shelfUri(context: Context, path: List<String>) =
        Uri.Builder().scheme("content").authority(launcherAuthority(context)).appendPath("v1")
            .apply { path.forEach { appendPath(it) } }.build()

    private fun profileAllowed(graph: ClientGraph, path: List<String>) =
        path.firstOrNull() != "collection" ||
            path.getOrNull(1)?.let(::profileId)?.let { graph.launcherLibrary(it) } != null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (callingPackage != LauncherRails.PACKAGE) throw FileNotFoundException()
        val app = context ?: throw FileNotFoundException()
        val path = path(uri, app) ?: throw FileNotFoundException()
        if (mode != "r" || path.size < 4 || path[path.lastIndex - 1] != "art") throw FileNotFoundException()
        val graph = app.clientGraph
        if (!profileAllowed(graph, path)) throw FileNotFoundException()
        val shelf = shelfUri(app, path.dropLast(2))
        val image = rendered.get(shelf)?.art?.get(path.last()) ?: throw FileNotFoundException()
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        graph.scope.launch(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                try {
                    graph.okHttp.newCall(Request.Builder().url(image.url).build()).execute().use response@{ response ->
                        val body = response.body ?: return@response
                        if (!response.isSuccessful || body.contentType()?.type != "image" ||
                            body.contentLength() > image.limit) return@response
                        body.byteStream().use { input ->
                            val bytes = ByteArray(16 * 1024)
                            var left = image.limit
                            while (left > 0) {
                                val size = input.read(bytes, 0, minOf(bytes.size.toLong(), left).toInt())
                                if (size < 0) break
                                output.write(bytes, 0, size)
                                left -= size
                            }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // An unavailable image leaves the launcher card's placeholder visible.
                }
            }
        }
        return reader
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.getOrNull(uri.pathSegments.size - 2) == "art") "image/*" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private fun path(uri: Uri, app: Context): List<String>? =
        uri.pathSegments.takeIf {
            uri.scheme == "content" && uri.authority == launcherAuthority(app) &&
                uri.query == null && uri.fragment == null && it.firstOrNull() == "v1"
        }?.drop(1)

    private fun profileId(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()
}
