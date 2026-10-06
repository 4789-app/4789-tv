package com.fourseveneightnine.tv.client.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.client.ui.MainActivity
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

internal data class TvHomeProgram(
    val providerId: String,
    val detailUri: String,
    val item: CatalogItem,
)

/** Pure selection boundary: only validated movie/series metadata can reach TvProvider. */
internal object TvHomeChannelPolicy {
    const val CHANNEL_PROVIDER_ID = "4789:public-tmdb:v1"
    const val MAX_PROGRAMS = 40

    fun ownsProgram(rowPackage: String?, appPackage: String, providerId: String?): Boolean =
        rowPackage == appPackage && providerId?.startsWith("4789:") == true

    fun programs(
        items: List<CatalogItem>,
        authority: String,
        suppressedIds: Set<String> = emptySet(),
    ): List<TvHomeProgram> {
        val seen = HashSet<String>()
        return items.asSequence().mapNotNull { item ->
            val detailUri = SystemSearchIntentParser.detailUri(authority, item.mediaType, item.canonicalId)
                ?: return@mapNotNull null
            val providerId = "4789:${item.mediaType}:${item.canonicalId}"
            if (item.title.isBlank() || providerId in suppressedIds || !seen.add(providerId)) {
                return@mapNotNull null
            }
            TvHomeProgram(providerId, detailUri, item)
        }.take(MAX_PROGRAMS).toList()
    }
}

/** Cache-only reconciliation for Android TV's one default 4789 channel. */
// tvprovider 1.1.0's documented public PreviewProgram API inherits RestrictTo annotations from
// its base builders/getters. Newer lint reports those calls even though this is the supported API.
@SuppressLint("RestrictedApi")
internal object TvHomeChannelPublisher {
    private const val CHANNEL_NAME = "Popular on 4789"
    private const val PREFERENCES = "tv-home-channel"
    private const val CHANNEL_ID = "channel-id"
    private const val CHANNEL_REMOVED = "channel-removed"
    private const val SUPPRESSED_PROGRAMS = "suppressed-programs"
    private const val PROGRAM_MAP_PREFIX = "program-"
    private val lock = Any()

    fun reconcile(context: Context) {
        val app = context.applicationContext
        if (!supportsTvProvider(app)) return
        val items = runCatching { app.clientGraph.snapshots.cachedPublicTmdbItems() }
            .getOrDefault(emptyList())
        if (items.isEmpty()) return
        synchronized(lock) {
            runCatching { reconcileSupported(app, items) }
                .onFailure { ReceiverDiagnostics.record("tvhome.reconcile", it::class.java.simpleName) }
        }
    }

    fun suppressProgram(context: Context, programId: Long) {
        val app = context.applicationContext
        if (programId < 0 || !supportsTvProvider(app)) return
        synchronized(lock) {
            runCatching {
                val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val helper = PreviewChannelHelper(app)
                val row = helper.getPreviewProgram(programId)
                val providerId = if (row != null) {
                    if (!TvHomeChannelPolicy.ownsProgram(row.packageName, app.packageName, row.internalProviderId)) {
                        return@runCatching
                    }
                    requireNotNull(row.internalProviderId)
                } else {
                    preferences.getString(PROGRAM_MAP_PREFIX + programId, null)
                        ?.takeIf { it.startsWith("4789:") }
                        ?: return@runCatching
                }
                val suppressed = preferences.getStringSet(SUPPRESSED_PROGRAMS, emptySet()).orEmpty().toMutableSet()
                suppressed += providerId
                preferences.edit()
                    .putStringSet(SUPPRESSED_PROGRAMS, suppressed)
                    .remove(PROGRAM_MAP_PREFIX + programId)
                    .apply()
                helper.deletePreviewProgram(programId)
            }.onFailure { ReceiverDiagnostics.record("tvhome.remove", it::class.java.simpleName) }
        }
    }

    private fun reconcileSupported(context: Context, items: List<CatalogItem>) {
        val helper = PreviewChannelHelper(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(CHANNEL_REMOVED, false)) return

        val savedId = preferences.getLong(CHANNEL_ID, -1L)
        val saved = savedId.takeIf { it >= 0 }
            ?.let(helper::getPreviewChannel)
            ?.takeIf { it.internalProviderId == TvHomeChannelPolicy.CHANNEL_PROVIDER_ID }
        val channel = saved ?: helper.getAllChannels()
            .firstOrNull { it.internalProviderId == TvHomeChannelPolicy.CHANNEL_PROVIDER_ID }
        if (savedId >= 0 && channel == null) {
            preferences.edit().putBoolean(CHANNEL_REMOVED, true).apply()
            return
        }

        val channelId = if (channel == null) {
            helper.publishDefaultChannel(channel(context)).also {
                preferences.edit().putLong(CHANNEL_ID, it).apply()
            }
        } else {
            preferences.edit().putLong(CHANNEL_ID, channel.id).apply()
            if (!channel.isBrowsable) return
            val update = channel(context)
            if (channel.hasAnyUpdatedValues(update)) helper.updatePreviewChannel(channel.id, update)
            channel.id
        }

        val suppressed = preferences.getStringSet(SUPPRESSED_PROGRAMS, emptySet()).orEmpty()
        val wanted = TvHomeChannelPolicy.programs(
            items = items,
            authority = "${context.packageName}.search",
            suppressedIds = suppressed,
        ).associateBy(TvHomeProgram::providerId)
        val existing = programs(context, channelId)
        val existingByProviderId = existing.mapNotNull { program ->
            program.internalProviderId?.let { it to program }
        }.toMap()

        existing.forEach { program ->
            val providerId = program.internalProviderId
            if (providerId == null || providerId !in wanted || existingByProviderId[providerId]?.id != program.id) {
                helper.deletePreviewProgram(program.id)
                preferences.edit().remove(PROGRAM_MAP_PREFIX + program.id).apply()
            }
        }

        wanted.values.forEachIndexed { index, program ->
            val desired = previewProgram(channelId, index, program)
            val current = existingByProviderId[program.providerId]
            val rowId = if (current == null) {
                helper.publishPreviewProgram(desired)
            } else {
                if (current.hasAnyUpdatedValues(desired)) helper.updatePreviewProgram(current.id, desired)
                current.id
            }
            preferences.edit().putString(PROGRAM_MAP_PREFIX + rowId, program.providerId).apply()
        }
        ReceiverDiagnostics.record("tvhome.reconcile", "programs=${wanted.size}")
    }

    private fun channel(context: Context): PreviewChannel = PreviewChannel.Builder()
        .setDisplayName(CHANNEL_NAME)
        .setDescription("Popular movies and series from the saved public TMDB catalog")
        .setInternalProviderId(TvHomeChannelPolicy.CHANNEL_PROVIDER_ID)
        .setAppLinkIntent(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        .build()

    private fun previewProgram(channelId: Long, index: Int, program: TvHomeProgram): PreviewProgram {
        val item = program.item
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setInternalProviderId(program.providerId)
            .setContentId(program.providerId)
            .setType(
                if (item.mediaType == "movie") {
                    TvContractCompat.PreviewPrograms.TYPE_MOVIE
                } else {
                    TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                },
            )
            .setTitle(item.title)
            .setDescription(item.overview)
            .setPosterArtUri(item.posterUrl?.let(Uri::parse))
            .setThumbnailUri(item.backdropUrl?.let(Uri::parse))
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER)
            .setIntentUri(Uri.parse(program.detailUri))
            .setWeight(TvHomeChannelPolicy.MAX_PROGRAMS - index)
            .build()
    }

    private fun programs(context: Context, channelId: Long): List<PreviewProgram> {
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
        return context.contentResolver.query(uri, PreviewProgram.PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) PreviewProgram.fromCursor(cursor)?.let(::add)
            }
        }.orEmpty()
    }

    private fun supportsTvProvider(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.packageManager
        if (!manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return false
        return runCatching { manager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null }
            .getOrDefault(false)
    }
}
