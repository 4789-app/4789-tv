package com.fourseveneightnine.tv.client.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

internal sealed interface SystemSearchCommand {
    data class Query(val value: String) : SystemSearchCommand
    data class Detail(val type: String, val id: String) : SystemSearchCommand
}

/** The one trust boundary shared by the Activity and the exported suggestion provider. */
internal object SystemSearchIntentParser {
    private const val ACTION_SEARCH = "android.intent.action.SEARCH"
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val DETAIL_PATH = "detail"
    private const val MAX_ENCODED_SEGMENT_CHARACTERS = 700
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,499}")
    private val DETAIL_TYPES = setOf("movie", "series")

    fun parse(
        action: String?,
        query: String?,
        dataUri: String?,
        expectedAuthority: String,
    ): SystemSearchCommand? = when (action) {
        ACTION_SEARCH -> SystemSearchPolicy.boundedQuery(query)?.let(SystemSearchCommand::Query)
        ACTION_VIEW -> parseDetail(dataUri, expectedAuthority)
        else -> null
    }

    fun detailUri(authority: String, type: String, id: String): String? {
        if (authority.isBlank() || type !in DETAIL_TYPES || !SAFE_ID.matches(id)) return null
        return "content://$authority/$DETAIL_PATH/${encode(type)}/${encode(id)}"
    }

    private fun parseDetail(dataUri: String?, expectedAuthority: String): SystemSearchCommand.Detail? {
        val uri = dataUri?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (uri.scheme != "content" || uri.rawAuthority != expectedAuthority) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null || uri.port != -1) return null
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 3 || segments[0] != DETAIL_PATH) return null
        val type = decode(segments[1]) ?: return null
        val id = decode(segments[2]) ?: return null
        if (type !in DETAIL_TYPES || !SAFE_ID.matches(id)) return null
        return SystemSearchCommand.Detail(type, id)
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? {
        if (value.isEmpty() || value.length > MAX_ENCODED_SEGMENT_CHARACTERS) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

internal object SystemSearchPolicy {
    const val MAX_QUERY_CHARACTERS = 120
    const val MAX_RESULTS = 20
    private const val MAX_SCANNED_ITEMS = 2_500
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    fun boundedQuery(raw: String?): String? = raw
        ?.trim()
        ?.take(MAX_QUERY_CHARACTERS)
        ?.takeIf { it.length >= 2 }

    fun suggestions(
        items: List<CatalogItem>,
        rawQuery: String?,
        limit: Int = MAX_RESULTS,
    ): List<CatalogItem> {
        val query = boundedQuery(rawQuery) ?: return emptyList()
        val normalizedQuery = normalize(query)
        val tokens = normalizedQuery.split(WHITESPACE).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return items.asSequence()
            .take(MAX_SCANNED_ITEMS)
            .filter { it.mediaType == "movie" || it.mediaType == "series" }
            .filter { it.canonicalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,499}")) }
            .filter { seen.add("${it.mediaType}/${it.canonicalId}") }
            .mapNotNull { item ->
                val title = normalize(item.title)
                val searchable = buildString {
                    append(title)
                    append(' ')
                    append(item.year ?: "")
                    append(' ')
                    append(item.genres.joinToString(" ") { normalize(it) })
                }
                if (!tokens.all(searchable::contains)) return@mapNotNull null
                val score = when {
                    title == normalizedQuery -> 0
                    title.startsWith(normalizedQuery) -> 1
                    title.split(WHITESPACE).any { it.startsWith(normalizedQuery) } -> 2
                    title.contains(normalizedQuery) -> 3
                    else -> 4
                }
                score to item
            }
            .sortedWith(compareBy<Pair<Int, CatalogItem>>({ it.first }, { normalize(it.second.title) }))
            .map(Pair<Int, CatalogItem>::second)
            .take(limit.coerceIn(0, MAX_RESULTS))
            .toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()
}

/** Read-only Android TV suggestions over the atomically cached public TMDB snapshot. */
class SystemSearchProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val appContext = context?.applicationContext ?: return MatrixCursor(COLUMNS)
        val authority = authority(appContext.packageName)
        val rawQuery = suggestionQuery(uri, authority) ?: return MatrixCursor(COLUMNS)
        val limit = requestedLimit(uri) ?: return MatrixCursor(COLUMNS)
        val results = SystemSearchPolicy.suggestions(
            items = appContext.clientGraph.snapshots.cachedPublicTmdbItems(),
            rawQuery = rawQuery,
            limit = limit,
        )
        return MatrixCursor(COLUMNS, results.size).apply {
            results.forEachIndexed { index, item ->
                val detailUri = SystemSearchIntentParser.detailUri(authority, item.mediaType, item.canonicalId)
                    ?: return@forEachIndexed
                addRow(
                    arrayOf<Any?>(
                        index.toLong(),
                        item.title,
                        listOfNotNull(item.year?.toString(), item.mediaType.displayType()).joinToString(" · "),
                        item.posterUrl,
                        Intent.ACTION_VIEW,
                        detailUri,
                        item.year,
                    ),
                )
            }
        }
    }

    override fun getType(uri: Uri): String? =
        suggestionQuery(uri, authority(context?.packageName.orEmpty()))?.let { SearchManager.SUGGEST_MIME_TYPE }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun suggestionQuery(uri: Uri, expectedAuthority: String): String? {
        if (uri.scheme != "content" || uri.authority != expectedAuthority) return null
        if (uri.pathSegments.size != 2 || uri.pathSegments[0] != SearchManager.SUGGEST_URI_PATH_QUERY) return null
        if (uri.queryParameterNames.any { it != SearchManager.SUGGEST_PARAMETER_LIMIT }) return null
        return SystemSearchPolicy.boundedQuery(uri.pathSegments[1])
    }

    private fun requestedLimit(uri: Uri): Int? {
        val raw = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT) ?: return SystemSearchPolicy.MAX_RESULTS
        return raw.toIntOrNull()?.coerceIn(1, SystemSearchPolicy.MAX_RESULTS)
    }

    private fun String.displayType(): String = if (this == "movie") "Movie" else "Series"

    private companion object {
        fun authority(packageName: String): String = "$packageName.search"

        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
        )
    }
}
