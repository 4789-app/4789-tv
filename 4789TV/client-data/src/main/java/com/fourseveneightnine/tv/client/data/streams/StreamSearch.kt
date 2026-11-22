package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.HttpStatusException
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.addons.healthLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the Streams screen knows right now (`TV_DESIGN_SPEC.md` §10.7).
 *
 * Native row identities keep insertion order; live phone rows can expire or be revoked.
 * The count chip reads the other fields: "Searching · 0 of 4 add-ons", "7 sources · 2 add-ons pending", "12 sources · 1 add-on
 * failed".
 */
data class StreamSearchState(
    val rows: List<StreamRow> = emptyList(),
    val attempted: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val done: Boolean = false,
    val timeouts: Int = 0,
    val checkingLinks: Int = 0,
    val rejectedLinks: Int = 0,
)

/**
 * Fans out to every stream add-on and publishes rows as they land.
 *
 * Three rules, all of them paid for once already:
 *
 *  - **Per-add-on timeout AND a set deadline.** One dead endpoint used to cost the viewer the
 *    whole per-call timeout, because every endpoint was joined at once: thirty results sat
 *    finished and invisible while the slowest ran out its clock. Showing eleven sources now beats
 *    showing fourteen in fifteen seconds.
 *  - **Isolation.** One add-on throwing never cancels its siblings, and never fails the flow.
 *  - **Insertion order is final.** A row that has been published never moves in this list. The
 *    ranker re-sorts a copy; the screen decides when to apply it (§10.7), so a row cannot slide
 *    out from under the D-pad.
 */
class StreamSearch(
    private val registry: AddonRegistry,
    private val client: StremioClient,
    private val perAddonTimeoutMillis: Long = PER_ADDON_TIMEOUT_MILLIS,
    private val setDeadlineMillis: Long = SET_DEADLINE_MILLIS,
    private val torbox: com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver? = null,
    private val phoneRows: ((String, List<String>) -> Flow<List<StreamRow>>)? = null,
    private val diagnostics: (String, StreamExpectation?, String) -> Unit = { _, _, _ -> },
    private val validateSource: (suspend (StreamRow, StreamExpectation?) -> StreamRow?)? = null,
    private val benchmark: ((String, String) -> Unit)? = null,
) {

    fun search(
        type: String,
        id: String,
        season: Int? = null,
        episode: Int? = null,
        expectation: StreamExpectation? = null,
    ): Flow<StreamSearchState> = search(
        type, listOf(AddonEndpoint.episodeIdentifier(id, season, episode)), expectation = expectation,
    )

    /** IDs are verified aliases of the same title or selected episode, best first. */
    fun search(
        type: String,
        ids: List<String>,
        inlineRows: List<StreamRow> = emptyList(),
        expectation: StreamExpectation? = null,
        followPhoneResults: Boolean = false,
    ): Flow<StreamSearchState> {
        val native = nativeSearch(type, ids, inlineRows, expectation)
        val shared = phoneRows?.invoke(type, ids) ?: return native
        // A Sources drawer follows later phone arrivals. Automatic Play still completes at the
        // native deadline, so an absent phone cannot hold playback preparation open indefinitely.
        return combine(native, shared.distinctUntilChanged().map { verifyRows(it, expectation) }) { state, incoming ->
            fun key(row: StreamRow): String = row.playbackKey() +
                if (row.url.isNullOrBlank() && row.torboxFileID != null) "|file:${row.torboxFileID}" else ""
            val merged = state.rows.associateByTo(linkedMapOf(), ::key)
            for (checked in incoming.filter { it.playable && !it.theatricalRelease }) {
                val key = key(checked)
                val existing = merged[key]
                if (existing == null) merged[key] = checked.copy(id = key)
                else if (checked.identityVerified == true && existing.identityVerified != true) {
                    merged[key] = checked.mergingMetadata(existing).copy(id = existing.id,
                        refreshAddonKey = existing.refreshAddonKey, refreshID = existing.refreshID)
                } else merged[key] = existing.mergingMetadata(checked)
            }
            state.copy(rows = merged.values.toList(), attempted = state.attempted + if (incoming.isEmpty()) 0 else 1)
        }.transformWhile { state ->
            emit(state)
            followPhoneResults || !state.done
        }
    }

    /** Every displayed path, including inline/saved and phone-shared results, uses this gate. */
    fun validatedForDisplay(
        upstream: Flow<StreamSearchState>, expectation: StreamExpectation?, followUpdates: Boolean = false,
        rejectedIDs: Flow<Set<String>> = flowOf(emptySet()),
    ): Flow<StreamSearchState> = channelFlow {
        val latest = MutableStateFlow<StreamSearchState?>(null)
        val decisions = MutableStateFlow<Map<String, StreamRow?>>(emptyMap())
        val queued = mutableSetOf<String>()
        val gate = Semaphore(2)
        fun key(row: StreamRow) = row.playbackKey() + "|" + (row.filename ?: row.releaseName)
        launch {
            upstream.collect { state ->
                latest.value = state
                for (row in state.rows.filter { it.requiresDmmValidation && !it.knownUncached }) {
                    val id = key(row)
                    if (!queued.add(id)) continue
                    launch {
                        val checked = gate.withPermit {
                            try {
                                validateSource?.invoke(row.copy(expectation = expectation), expectation)
                                    ?.takeIf { it.playable && !it.theatricalRelease &&
                                        it.identityVerified == true && expectation?.matches(it) == true }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        }
                        diagnostics(row.addonName, expectation,
                            "stage=validation result=${if (checked == null) "rejected" else "passed"} source=${row.playbackKey().take(12)}")
                        decisions.update { it + (id to checked?.copy(id = row.id)) }
                    }
                }
            }
        }
        combine(latest.filterNotNull(), decisions, rejectedIDs) { state, checked, denied ->
            val candidates = state.rows.filter { it.requiresDmmValidation && !it.knownUncached }
            val checking = candidates.count { it.id !in denied && key(it) !in checked }
            val rejected = candidates.count { it.id in denied || (key(it) in checked && checked[key(it)] == null) }
            val admitted = linkedMapOf<String, StreamRow>()
            for (candidate in state.rows) {
                if (candidate.id in denied || candidate.knownUncached) continue
                val row = if (candidate.requiresDmmValidation) checked[key(candidate)] ?: continue
                    else candidate.takeIf { it.readyHint && it.identityVerified != false } ?: continue
                val identity = if (row.cachedHint.verified && row.cacheHash != null && row.verifiedCacheFile != null)
                    "${row.addonName}|${row.cachedHint.service}|${row.cacheHash}|${row.verifiedCacheFile}" else row.playbackKey()
                admitted[identity] = admitted[identity]?.mergingMetadata(row) ?: row
            }
            state.copy(rows = admitted.values.toList(),
                done = state.done && checking == 0, checkingLinks = checking, rejectedLinks = rejected)

        }.collect { send(it) }
    }.transformWhile { state -> emit(state); followUpdates || !state.done }

    private fun nativeSearch(
        type: String,
        ids: List<String>,
        inlineRows: List<StreamRow>,
        expectation: StreamExpectation?,
    ): Flow<StreamSearchState> = channelFlow {
        val searchID = System.nanoTime()
        val requests = registry.streamAddons().mapNotNull { addon ->
            val declared = addon.manifest?.streamResources.orEmpty()
            val routes = ids.distinct().filter { id ->
                AddonEndpoint.streamURL(addon.manifestURL, type, id) != null &&
                    (declared.isEmpty() || declared.any { it.accepts(type, id) })
            }
            routes.takeIf { it.isNotEmpty() }?.let { addon to it }
        }
        val rows = linkedMapOf<String, Pair<Int, StreamRow>>()
        fun merge(results: List<StreamRow>, priority: Int) {
            results.filter { it.playable && !it.theatricalRelease }.forEach { row ->
                val id = row.playbackKey()
                val existing = rows[id]
                // Keep identity/focus while a better-described duplicate or the earlier
                // configured provider replaces a result that happened to answer first.
                val richer = row.facts.reportedAudioLanguages.isNotEmpty()
                val existingRicher = existing?.second?.facts?.reportedAudioLanguages.orEmpty().isNotEmpty()
                val stronger = (row.identityVerified == true && existing?.second?.identityVerified != true) ||
                    (row.cachedHint.verified && existing?.second?.cachedHint?.verified != true)
                val keepStronger = (existing?.second?.identityVerified == true && row.identityVerified != true) ||
                    (existing?.second?.cachedHint?.verified == true && !row.cachedHint.verified)
                if (existing == null || stronger || (!keepStronger && ((richer && !existingRicher) ||
                    (richer == existingRicher && priority < existing.first)))
                ) rows[id] = priority to (existing?.second?.let(row::mergingMetadata) ?: row).copy(id = existing?.second?.id ?: id)
                else rows[id] = existing.first to existing.second.mergingMetadata(row)
            }
        }
        merge(verifyRows(inlineRows, expectation), -1)
        val attempted = requests.size + if (inlineRows.isEmpty()) 0 else 1
        var pending = requests.size
        benchmark?.invoke("sources.search.start", "search=$searchID providers=$pending inline=${inlineRows.size}")
        var failed = 0
        var timeouts = 0
        suspend fun publish() = send(StreamSearchState(
            rows = rows.values.map { it.second },
            attempted = attempted,
            pending = pending,
            failed = failed,
            done = pending == 0,
            timeouts = timeouts,
        ))

        try {
            publish()
            if (pending == 0) {
                benchmark?.invoke("sources.search.end", "search=$searchID rows=${rows.size} failed=0 timeouts=0")
                return@channelFlow
            }
            val answers = Channel<Pair<Int, Answer>>(capacity = requests.size)
            // The deadline owns the requests as well as the collector, so it cancels pending calls.
            withTimeoutOrNull(setDeadlineMillis) {
                coroutineScope {
                    requests.forEachIndexed { index, (addon, routes) ->
                        benchmark?.invoke("sources.provider.queued", "search=$searchID provider=$index routes=${routes.size}")
                        launch { answers.send(index to fetch(addon, type, routes, expectation, searchID, index)) }
                    }
                    repeat(requests.size) {
                        val (priority, answer) = answers.receive()
                        pending -= 1
                        if (answer.failure != null) failed += 1
                        if (answer.failure == "timeout") timeouts += 1
                        merge(answer.rows, priority)
                        if (pending > 0) publish()
                    }
                }
            }
            if (pending > 0) {
                failed += pending
                timeouts += pending
                pending = 0
            }
            publish()
            benchmark?.invoke("sources.search.end",
                "search=$searchID rows=${rows.size} failed=$failed timeouts=$timeouts elapsedMs=${(System.nanoTime() - searchID) / 1_000_000}")
        } catch (cancelled: CancellationException) {
            benchmark?.invoke("sources.search.end",
                "search=$searchID result=cancelled remaining=$pending rows=${rows.size} elapsedMs=${(System.nanoTime() - searchID) / 1_000_000}")
            throw cancelled
        }
    }

    private data class Answer(val rows: List<StreamRow>, val failure: String? = null)

    /** Refresh the same selected file, never an arbitrary new row at the old array index. */
    suspend fun refresh(row: StreamRow, type: String, expectation: StreamExpectation?,
                        timeoutMillis: Long = PER_ADDON_TIMEOUT_MILLIS): StreamRow? {
        if (row.exactSource) return null
        val addon = registry.streamAddons().firstOrNull { it.key == row.refreshAddonKey || row.id.startsWith("${it.key}|") } ?: return null
        val requestID = row.refreshID ?: row.id.takeIf { it.startsWith("${addon.key}|") }
            ?.removePrefix("${addon.key}|")?.substringBeforeLast('|') ?: return null
        val fresh = withTimeoutOrNull(timeoutMillis) { client.streams(addon, type, requestID) } ?: return null
        fun fileKey(item: StreamRow) = (item.filename ?: item.releaseName).substringBefore("|^|")
            .replace('\\', '/').substringAfterLast('/').trim().lowercase()
        val name = fileKey(row).takeIf(String::isNotBlank) ?: return null
        val matching = fresh.filter {
            fileKey(it) == name && (row.infoHash == null || row.infoHash == it.infoHash) &&
                (row.fileIdx == null || row.fileIdx == it.fileIdx) &&
                (row.bingeGroup == null || row.bingeGroup == it.bingeGroup)
        }
        val renewed = matching.firstOrNull { it.playbackKey() != row.playbackKey() } ?: matching.firstOrNull()
        return renewed?.copy(id = row.id, identityVerified = expectation?.matches(renewed), expectation = expectation)
    }

    private suspend fun fetch(
        addon: Addon, type: String, routes: List<String>, expectation: StreamExpectation?, searchID: Long, provider: Int,
    ): Answer {
        val started = System.nanoTime()
        benchmark?.invoke("sources.provider.start", "search=$searchID provider=$provider routes=${routes.size} name=${addon.name}")
        var returnedRows = 0
        var requestNs = 0L
        var validationNs = 0L
        val answer = try { withTimeoutOrNull(perAddonTimeoutMillis) {
            var validEmpty = false
            var failure: String? = null
            for (id in routes) {
                try {
                    val requestedAt = if (benchmark == null) 0L else System.nanoTime()
                    val rows = try {
                        client.streams(addon, type, id)
                    } finally {
                        if (benchmark != null) requestNs += System.nanoTime() - requestedAt
                    }
                    if (rows.isNotEmpty()) {
                        returnedRows = rows.size
                        val validatingAt = if (benchmark == null) 0L else System.nanoTime()
                        val checked = try {
                            verifyRows(rows, expectation)
                        } finally {
                            if (benchmark != null) validationNs += System.nanoTime() - validatingAt
                        }
                        return@withTimeoutOrNull Answer(checked)
                    }
                    validEmpty = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.healthLabel()
                    // A different alias can fix an unsupported route, not a failed account.
                    if (error !is HttpStatusException || error.code !in setOf(400, 404, 422)) {
                        return@withTimeoutOrNull Answer(emptyList(), failure)
                    }
                }
            }
            Answer(emptyList(), failure.takeUnless { validEmpty })
        } ?: Answer(emptyList(), "timeout") } catch (cancelled: CancellationException) {
            benchmark?.invoke("sources.provider.end",
                "search=$searchID provider=$provider result=cancelled returned=$returnedRows requestMs=${requestNs / 1_000_000} validationMs=${validationNs / 1_000_000} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            diagnostics(addon.name, expectation, "search=$searchID result=cancelled elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            throw cancelled
        }
        diagnostics(addon.name, expectation, "search=$searchID result=${answer.failure ?: "ok"} rows=${answer.rows.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        benchmark?.invoke("sources.provider.end",
            "search=$searchID provider=$provider result=${answer.failure ?: if (answer.rows.isEmpty()) "empty" else "ok"} returned=$returnedRows rows=${answer.rows.size} requestMs=${requestNs / 1_000_000} validationMs=${validationNs / 1_000_000} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        registry.recordStreamResult(addon, answer.failure)
        return answer
    }

    suspend fun verifyRows(rows: List<StreamRow>, expectation: StreamExpectation?): List<StreamRow> {
        val named = rows.map { it.copy(identityVerified = if (it.exactSource) it.identityVerified else expectation?.matches(it),
            expectation = expectation) }
        val candidates = named.filter { row ->
            !row.knownUncached && (row.identityVerified == true || row.exactSource || row.needsResolve) &&
                (row.cacheHash ?: row.infoHash) != null &&
                (row.deliveryService.equals("TorBox", true) || row.exactSource ||
                    (row.needsResolve && row.deliveryService == null))
        }
        if (torbox == null || candidates.isEmpty()) return named
        val hashes = candidates.mapNotNull { it.cacheHash ?: it.infoHash }.distinct()
        val aliases = candidates.mapNotNull { row ->
            row.v2Infohash?.let { (row.cacheHash ?: row.infoHash.orEmpty()).lowercase() to it }
        }.toMap()
        val cached = try { withTimeoutOrNull(1_500L) {
            torbox.cachedFiles(hashes, aliases)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return named
        val candidateIds = candidates.mapTo(hashSetOf()) { it.id }
        return named.map { row ->
            if (row.id !in candidateIds) return@map row
            val hash = (row.cacheHash ?: row.infoHash).orEmpty().lowercase()
            val files = cached[hash].orEmpty()
            if (row.exactSource || row.needsResolve) {
                val allowed = files.filter {
                    (row.fileIdx == null || row.fileIdx == it.torrentIndex) &&
                        (row.torboxFileID == null || row.torboxFileID == it.id)
                }
                val matches = allowed.filter {
                    (row.exactSource && row.torboxFileID == it.id) ||
                        expectation?.matches(row.withFileFacts(it.name.substringAfterLast('/'))) == true
                }
                val file = matches.singleOrNull() ?: matches.singleOrNull {
                    it.name.substringAfterLast('/').equals(row.filename ?: row.releaseName, true)
                }
                if (file != null) return@map row.withFileFacts(file.name.substringAfterLast('/'), file.sizeBytes)
                    .copy(identityVerified = true, cachedHint = CachedHint(true, "TorBox", verified = true),
                        cacheHash = hash, verifiedCacheFile = file.name, fileIdx = file.torrentIndex, torboxFileID = file.id)
                // A miss on TorBox says nothing about another service behind a cached-only feed.
                if (!row.exactSource && row.deliveryService == null && row.cachedDebrid) return@map row
                return@map row.copy(cachedHint = CachedHint(false, "TorBox", uncached = true))
            }
            val matching = files.filter { expectation?.matches(row.copy(releaseName = it.name.substringAfterLast('/'), filename = it.name.substringAfterLast('/'), identityNames = emptyList())) == true }
            val exactFiles = matching.filter { it.name.substringAfterLast('/').equals(
                (row.filename ?: row.releaseName).substringAfterLast('/'), ignoreCase = true) }
            val selectedFile = exactFiles.singleOrNull() ?: matching.singleOrNull()
            val dmmPlay = row.url?.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.host == "debridmediamanager.com" &&
                    it.pathSegments.take(2) == listOf("api", "stremio-tb") &&
                    it.pathSegments.getOrNull(3) == "play" }
            val unboundDmmPlay = dmmPlay?.takeIf { it.queryParameter("file").isNullOrBlank() }
            if (unboundDmmPlay != null && selectedFile != null) {
                // A legacy DMM season-pack URL defaults to its biggest file. Bind the
                // cached file proved to match this episode before presenting a verified badge.
                val name = selectedFile.name.substringAfterLast('/')
                row.withFileFacts(name, selectedFile.sizeBytes).copy(
                    url = unboundDmmPlay.newBuilder().setQueryParameter("file", name).build().toString(), identityVerified = true,
                    cachedHint = CachedHint(true, "TorBox", verified = true), verifiedCacheFile = selectedFile.name)
            } else if (unboundDmmPlay == null && exactFiles.size == 1) {
                row.withFileFacts(exactFiles.single().name.substringAfterLast('/'), exactFiles.single().sizeBytes)
                    .copy(cachedHint = CachedHint(true, "TorBox", verified = true), verifiedCacheFile = exactFiles.single().name)
            } else row.copy(
                cachedHint = CachedHint(false, "TorBox", uncached = hash !in cached || (files.isNotEmpty() && matching.isEmpty())),
                identityVerified = if (cached.containsKey(hash) && matching.isEmpty()) false else row.identityVerified,
            )
        }
    }

    companion object {
        /** A slow provider gets time to answer without holding back faster providers. */
        const val PER_ADDON_TIMEOUT_MILLIS: Long = 20_000L
        const val SET_DEADLINE_MILLIS: Long = 25_000L
    }
}
