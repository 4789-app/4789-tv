package com.fourseveneightnine.tv.client.iptv

import kotlinx.serialization.Serializable
import java.util.Locale

/** IPTV is owned by this TV. A phone may sync other settings without replacing these accounts. */
@Serializable
internal enum class IptvSourceKind { M3U, XTREAM, STALKER }

internal fun iptvPlaybackHeaders(headers: Map<String, String>): Map<String, String> =
    if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
    else headers + ("User-Agent" to "VLC/3.0.21 LibVLC/3.0.21")

@Serializable
internal data class IptvSource(
    val id: String,
    val name: String,
    val kind: IptvSourceKind,
    val enabled: Boolean = true,
    val url: String? = null,
    val host: String? = null,
    val username: String? = null,
    val password: String? = null,
    val mac: String? = null,
    val epgUrl: String? = null,
    val maxConnections: Int? = null,
)

@Serializable
internal data class IptvChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val epgId: String? = null,
    /** M3U URL, Xtream stream ID, or Stalker command. Never print this in diagnostics. */
    val streamRef: String,
    val headers: Map<String, String> = emptyMap(),
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
)

@Serializable
internal data class IptvProgram(
    val channelKey: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val categories: List<String> = emptyList(),
)

internal enum class IptvGuideKind(val label: String) {
    Movie("MOVIE"), Sports("SPORTS"), News("NEWS"), Series("SERIES"),
    MovieChannel("MOVIE CHANNEL"), SportsChannel("SPORTS CHANNEL"), Program("PROGRAM"),
}

private val movieChannelName = Regex("\\b(movie|movies|cinema|films)\\b", RegexOption.IGNORE_CASE)
private val sportsChannelName = Regex("\\b(sport|sports|cricket)\\b", RegexOption.IGNORE_CASE)

internal fun IptvChannel.guideChannelHint(): IptvGuideKind? {
    val label = "$name $group"
    return when {
        movieChannelName.containsMatchIn(label) -> IptvGuideKind.MovieChannel
        sportsChannelName.containsMatchIn(label) -> IptvGuideKind.SportsChannel
        else -> null
    }
}

/** Distinguish a provider-labelled film from a channel that merely tends to show films. */
internal fun IptvProgram.guideKind(channel: IptvChannel): IptvGuideKind {
    val tags = categories.joinToString(" ").lowercase(Locale.ROOT)
    return when {
        listOf("movie", "film", "cinema").any { it in tags } ||
            title.trimStart().startsWith("Movie:", ignoreCase = true) -> IptvGuideKind.Movie
        listOf("sport", "cricket").any { it in tags } -> IptvGuideKind.Sports
        "news" in tags -> IptvGuideKind.News
        "series" in tags -> IptvGuideKind.Series
        else -> channel.guideChannelHint() ?: IptvGuideKind.Program
    }
}

@Serializable
internal data class IptvVod(
    val id: String,
    val sourceId: String,
    val kind: String,
    val title: String,
    val category: String,
    val image: String? = null,
    val streamId: String? = null,
    val extension: String? = null,
    val directUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val description: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val rating: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val released: String? = null,
    val seriesId: String? = null,
    val portalCommand: String? = null,
    val portalType: String? = null,
)

internal data class IptvSeriesDetails(val series: IptvVod, val episodes: List<IptvVod>)

private val iptvEpisodeMarker = Regex("(?i)\\b(?:S(\\d+)\\s*E(?:P)?\\s*(\\d+)|(\\d+)x(\\d+)|EP\\s*(\\d+))\\b")

internal fun orderedIptvEpisodes(episodes: List<IptvVod>, order: String): List<IptvVod> {
    val numbered = episodes.map { item ->
        val match = iptvEpisodeMarker.find(item.title)
        val season = item.seasonNumber ?: match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(3)?.toIntOrNull() ?: 0
        val episode = item.episodeNumber ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: match?.groupValues?.get(4)?.toIntOrNull() ?: match?.groupValues?.get(5)?.toIntOrNull() ?: 0
        Triple(season, episode, item)
    }
    val byNumber = compareBy<Triple<Int, Int, IptvVod>> { it.first }.thenBy { it.second }
    return when (order) {
        "Oldest episode" -> numbered.sortedWith(byNumber)
        "Latest air date" -> numbered.sortedWith(compareByDescending<Triple<Int, Int, IptvVod>> {
            it.third.released?.take(10).orEmpty()
        }.then(byNumber.reversed()))
        else -> numbered.sortedWith(byNumber.reversed())
    }.map { it.third }
}

@Serializable
internal enum class IptvRecordingStatus { SCHEDULED, RECORDING, DONE, FAILED }

@Serializable
internal data class IptvRecording(
    val id: String,
    val channelId: String,
    val title: String,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val status: IptvRecordingStatus = IptvRecordingStatus.SCHEDULED,
    val fileName: String? = null,
    val error: String? = null,
)

@Serializable
internal data class IptvGroupDecision(
    val bucket: String,
    val priority: Int,
    val confidence: Double,
    val model: String,
)

@Serializable
internal data class IptvAccounts(
    val sources: List<IptvSource> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val favoriteLists: Map<String, Set<String>> = emptyMap(),
    val recentIds: List<String> = emptyList(),
    val hiddenGroups: Set<String> = emptySet(),
    val parentalPinHash: String? = null,
    val lockedGroups: Set<String> = emptySet(),
    val recordings: List<IptvRecording> = emptyList(),
    val groupDecisions: Map<String, IptvGroupDecision> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val vodDecisions: Map<String, IptvGroupDecision> = emptyMap(),
    /** Optional TV-local key for new group names. Never bundled in the APK or displayed. */
    val decisionApiKey: String? = null,
    val decisionPolicyVersion: Int = 0,
    val manualGroupOrder: Boolean = false,
)

/** Ranking may finish after a Favorite/PIN/manual-order edit; it owns only group decisions. */
internal fun mergeIptvGroupRanking(latest: IptvAccounts, ranked: IptvAccounts): IptvAccounts = latest.copy(
    groupDecisions = latest.groupDecisions + ranked.groupDecisions,
    groupOrder = if (latest.manualGroupOrder) latest.groupOrder else ranked.groupOrder,
    decisionPolicyVersion = maxOf(latest.decisionPolicyVersion, ranked.decisionPolicyVersion),
)

@Serializable
internal data class IptvVodCategory(
    val sourceId: String,
    val kind: String,
    val name: String,
    val count: Int,
)

@Serializable
internal data class IptvCatalog(
    val channels: List<IptvChannel> = emptyList(),
    val programs: List<IptvProgram> = emptyList(),
    val vod: List<IptvVod> = emptyList(),
    val episodes: List<IptvVod> = emptyList(),
    val refreshedAtMillis: Long = 0,
    val vodCategories: List<IptvVodCategory> = emptyList(),
) {
    // Cached derived data is not serialized into the encrypted catalog.
    val programsByChannel: Map<String, List<IptvProgram>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        programs.groupBy(IptvProgram::channelKey).mapValues { (_, rows) -> rows.sortedBy(IptvProgram::startMillis) }
    }

    private fun firstAfter(rows: List<IptvProgram>, at: Long): Int {
        var low = 0
        var high = rows.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (rows[middle].startMillis <= at) low = middle + 1 else high = middle
        }
        return low
    }

    fun now(channelId: String, at: Long): IptvProgram? {
        val rows = programsByChannel[channelId] ?: return null
        return rows.getOrNull(firstAfter(rows, at) - 1)?.takeIf { it.endMillis > at }
    }

    fun next(channelId: String, at: Long): IptvProgram? {
        val rows = programsByChannel[channelId] ?: return null
        return rows.getOrNull(firstAfter(rows, at))
    }
}

internal data class IptvState(
    val accounts: IptvAccounts = IptvAccounts(),
    val catalog: IptvCatalog = IptvCatalog(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val catalogLoading: Boolean = true,
) {
    val sources: List<IptvSource> get() = accounts.sources
    val channels: List<IptvChannel> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val enabled = sources.filter(IptvSource::enabled).map(IptvSource::id).toSet()
        catalog.channels.filter { it.sourceId in enabled }
    }
    val favorites: List<IptvChannel> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        channels.filter { it.id in accounts.favoriteIds }
    }
    val orderedGroups: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val observed = channels.map(IptvChannel::group).distinct()
        val observedSet = observed.toSet()
        accounts.groupOrder.filter { it in observedSet } + observed.filterNot { it in accounts.groupOrder }
    }
}

internal object IptvIndex {
    fun search(channels: List<IptvChannel>, programs: List<IptvProgram>, query: String,
               limit: Int = 40, now: Long = System.currentTimeMillis(),
               groupOrder: List<String> = emptyList()): List<IptvChannel> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val current = programs.filter { it.startMillis <= now && it.endMillis > now }
            .associateBy(IptvProgram::channelKey)
        val upcoming = programs.asSequence().filter { it.endMillis > now && it.startMillis < now + 7 * 86_400_000L }
            .groupBy(IptvProgram::channelKey)
        val groupRank = groupOrder.withIndex().associate { it.value to it.index }
        return channels.asSequence().mapNotNull { channel ->
            val title = channel.name.lowercase()
            val program = current[channel.id]?.title?.lowercase().orEmpty()
            val schedule = upcoming[channel.id].orEmpty().joinToString(" ") { it.title.lowercase() }
            val haystack = "$title ${channel.group.lowercase()} $program $schedule"
            if (!words.all(haystack::contains)) return@mapNotNull null
            val rank = when {
                title == query.trim().lowercase() -> 0
                title.startsWith(query.trim().lowercase()) -> 1
                title.contains(query.trim().lowercase()) -> 2
                program.contains(query.trim().lowercase()) -> 3
                schedule.contains(query.trim().lowercase()) -> 4
                else -> 5
            }
            rank to channel
        }.sortedWith(compareBy<Pair<Int, IptvChannel>>({ it.first },
            { groupRank[it.second.group] ?: Int.MAX_VALUE }, { it.second.name }))
            .take(limit).map(Pair<Int, IptvChannel>::second).toList()
    }
}
