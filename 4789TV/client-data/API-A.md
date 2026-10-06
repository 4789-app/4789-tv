# client-data, part A — the API the TV screens call

Package root: `com.fourseveneightnine.tv.client.data`.
Part A owns four packages: `settings`, `addons`, `meta`, `streams`.
Part B owns `library`, `catalog`, `images`, `refresh`.

Everything here is plain Kotlin with coroutines. No Compose. No Android UI.
Nothing here needs a `Context`. Caches take a `File` directory, so tests run on a plain JVM.

Three rules hold across the whole surface:

1. A credential never reaches a log line, a file name or a `toString`.
2. A missing fact stays null. The screen drops that chip. It is never shown as zero.
3. A resolved stream URL lives in memory for the session. Nothing writes it to disk.

---

## settings

### `AddonSource(name: String, url: String, enabled: Boolean)`

One stream or catalog add-on from the phone's export.

### `SubtitleSource(name: String, url: String, enabled: Boolean)`

One subtitle add-on.

### `SettingsReceipt(totalFields, addonSources, subtitleSources, credentials, catalogOrder, letterboxdUsernames, hasPlaybackRules)`

Counts only. This is the only shape a settings document may appear in on screen.

### `class SettingsDocument`

A typed, read-only view over the phone's `4789-settings` export.

| Member | Meaning |
|---|---|
| `SettingsDocument.parse(rawJson: String): SettingsDocument` | Parse the export. Bad JSON gives an empty document, never an exception. |
| `SettingsDocument.empty: SettingsDocument` | A document with nothing in it. |
| `addonSources: List<AddonSource>` | Every add-on, in registry order, deduplicated by URL. |
| `subtitleSources: List<SubtitleSource>` | The subtitle list plus the dedicated AIO subtitles field. |
| `aioSubtitlesURL: String?` | The AIO subtitles manifest, on its own. |
| `catalogOrder: List<String>` | Home shelf order, by catalog uid. Empty means "registry order". |
| `torboxAPIKey: String?` | TorBox key, or null when it is blank or malformed. |
| `realDebridAPIKey: String?` | Real-Debrid key. |
| `tmdbAPIKey: String?` | TMDB key. Null means metadata is Cinemeta only. |
| `mdbListAPIKey: String?` | MDBList key. Null means the ratings row does not draw. |
| `catalogServerToken: String?` | Token for the private snapshot refresh (part B uses it). |
| `letterboxdUsernames: List<String>` | Names for the Letterboxd shelf labels. |
| `uncachedDailyMax: Int?` | How many uncached grabs a day the rules allow. |
| `uncachedSlotOverride: Int?` | A one-off override of that limit. |
| `playbackRules: PlaybackRules` | The picking rules, or the stock defaults. |
| `redacted(): SettingsReceipt` | Counts, safe to print and to show. |
| `toString()` | Prints `redacted()` and nothing else. |

Add-on order is fixed: `sources[]` as stored, then `primaryManifestURLText`, then
`secondaryManifestURLText`, then `aioStreamsURLText`, then `mediaFusionURLText`.
MediaFusion is marked disabled when `mediaFusionEnabled` is false. It is never dropped.

---

## addons

### `object AddonEndpoint`

Builds and checks Stremio routes. An add-on query is always kept: for AIOStreams it is the
configuration.

| Function | Meaning |
|---|---|
| `normalize(value: String): String?` | A comparison key for "same add-on?". Null when the URL is unsafe. |
| `manifestURL(base): String?` | `…/manifest.json`, whichever form the base was in. |
| `catalogURL(base, type, id, extra: String?): String?` | `/catalog/{type}/{id}[/{extra}].json`. |
| `metaURL(base, type, id): String?` | `/meta/{type}/{id}.json`. |
| `streamURL(base, type, id): String?` | `/stream/{type}/{id}.json`. |
| `subtitlesURL(base, type, id): String?` | `/subtitles/{type}/{id}.json`. |
| `playableURL(value: String?): String?` | A URL we will hand to the player, or null. |
| `identifiers(canonicalID, imdbID, tmdbID): List<String>` | Up to three ids to try, best first. |
| `episodeIdentifier(id, season, episode): String` | `tt123:2:4` for an episode, `tt123` for a film. |
| `extraSegment(extra: CatalogExtra): String?` | Stremio's `key=value&key=value` segment. |

### `CatalogExtra(skip: Int = 0, genre: String? = null, search: String? = null)`

What a catalog call can carry.

### `AddonCatalog(type, id, name, extraSupported, extraRequired, genres)`

One catalog an add-on declares. `uid(manifestURL): String` is its stable key.

### `AddonManifest(id, name, version, description, logo, types, resources, catalogs, idPrefixes)`

What an add-on says it is.

### `AddonHealth(failStreak, lastOkAtMillis, lastFailAtMillis, lastError)`

How it has been behaving. `healthy` is false after `AddonHealth.UNHEALTHY_AFTER` (3) failures
in a row.

### `Addon(name, manifestURL, enabled, manifest, health)`

One add-on in the registry.

| Member | Meaning |
|---|---|
| `key: String` | The normalised manifest URL. Use it as the list key. |
| `displayName: String` | The manifest's name, or the name the phone stored. |
| `supports(resource: String): Boolean` | Can it answer `catalog`, `meta`, `stream` or `subtitles`? |
| `usable: Boolean` | Enabled and healthy. |

### `interface AddonHealthStore`

`health(url)`, `markOk(url)`, `markFail(url, error)`, `snapshot()`.
`InMemoryAddonHealthStore(clock)` is the version this module ships. Part B's Room layer can
implement the same interface later.

### `class StremioClient(okHttp, cacheDir: File?, clock)`

Every call the TV makes to an add-on.

| Function | Meaning |
|---|---|
| `manifest(url): AddonManifest` | 24 h disk cache, revalidated with `If-None-Match`. |
| `catalog(addon, type, id, extra): CatalogPage` | One catalog page. |
| `meta(addon, type, id): Meta?` | Full metadata, or null. |
| `streams(addon, type, id): List<StreamRow>` | Playable rows. `id` already holds `:season:episode`. |
| `subtitles(addon, type, id): List<SubtitleTrack>` | Subtitle tracks. |
| `StremioClient.CINEMETA_MANIFEST` | Cinemeta's manifest URL. |

`CatalogPage(items: List<DiscoverItem>, hasMore: Boolean)` reuses the frozen `:contract` DTO, so
part B's catalog layer and the phone's rows are the same type.

### `class AddonRegistry(document, client, health)`

The ordered add-on list. Cinemeta is always first and always on.

| Member | Meaning |
|---|---|
| `addons: StateFlow<List<Addon>>` | Paints at once with names; manifests fill in after `refresh()`. |
| `refresh()` | Fetch every manifest in parallel. One failure never fails the set. |
| `streamAddons(): List<Addon>` | Usable add-ons that answer `/stream`. |
| `catalogAddons(): List<Addon>` | Usable add-ons that answer `/catalog`. |
| `metaAddons(): List<Addon>` | Usable add-ons that answer `/meta`. |
| `subtitleAddons(): List<Addon>` | The subtitle list, plus any add-on that declares `subtitles`. |
| `catalogs(): List<Pair<Addon, AddonCatalog>>` | Every catalog, in the phone's stated order. |
| `addon(manifestURL): Addon?` | Look one up by URL. |

---

## meta

### `Episode(season, episode, title, overview, thumbnail, released, id)`

One episode. `id` is the Stremio video id the stream call needs.

### `CastMember(name, role, photo)` · `MetaRef(id, type, title, poster)`

One cast row. One More Like This card.

### `Meta(...)`

Everything Detail draws. Fields: `id`, `type`, `title`, `year`, `runtimeMinutes`,
`certification`, `description`, `poster`, `backdrop`, `logo`, `genres`, `imdbID`, `tmdbID`,
`imdbRating`, `trailerYouTubeID`, `cast`, `videos`, `similar`, `seasons`.
`episodes(season: Int): List<Episode>` returns one season, in order.

Every field can be missing. Design spec §9.9 gives the fallback for each one.

### `Ratings(imdb: Double?, trakt: Int?, tmdb: Int?, letterboxd: Double?)`

The ratings row. `imdb` is out of 10. `trakt` and `tmdb` are percentages. `letterboxd` is out
of 5. `isEmpty` means the row does not draw. `Ratings.NONE` is the empty one.

### `class MetaRepository(registry, client, tmdbKey, cacheDir, okHttp, clock, tmdbBaseURL)`

| Function | Meaning |
|---|---|
| `meta(type: String, id: String): Meta?` | Cinemeta first, then TMDB fills the gaps. 24 h cache. |
| `clear()` | Drop the memory and disk caches. |

Two callers asking at once issue one fetch. No TMDB key means Cinemeta only, and the screen
still draws.

### `class RatingsRepository(mdbListKey, okHttp, clock, baseURL)`

| Function | Meaning |
|---|---|
| `ratings(imdbId: String, isShow: Boolean): Ratings` | One MDBList call. 30 min cache. |
| `clear()` | Drop the cache. |
| `RatingsRepository.parse(body: String): Ratings` | The parser, for tests. |

Uses the path form `api.mdblist.com/imdb/{movie|show}/{tt}?apikey=`.
Never `?i=`: that form answers 200 with the API index page and no ratings.

---

## streams

### `enum HdrFormat { NONE, HDR10, DOLBY_VISION }`

### `CachedHint(cached: Boolean, service: String?)`

A claim the add-on printed, not a probe. Real-Debrid removed instant-availability in 2024.

### `StreamRow(...)`

One row in the Streams list. Fields: `id`, `addonName`, `title`, `releaseName`, `url`,
`infoHash`, `fileIdx`, `quality`, `sizeBytes`, `codecs`, `hdr`, `audioLanguages`, `cachedHint`,
`seeders`, `headers`, `sources`, `facts`.

| Member | Meaning |
|---|---|
| `playable: Boolean` | Has a URL or a hash. |
| `needsResolve: Boolean` | Has only a hash, so it needs a debrid round trip. |
| `sizeGB: Double?` | Size in GB, or null. |
| `StreamRow.from(entry: StreamEntry, addonName, id)` | Build a row from the frozen `:contract` DTO. |

### `SubtitleTrack(id, url, language, addonName)`

### `StreamFacts(...)`

What one source states about itself. Fields: `quality`, `sizeBytes`, `bitrateMbps`, `codecs`,
`hdr`, `dolbyVisionProfile`, `audio`, `audioChannels`, `audioLanguages`, `provider`, `seeders`,
`ageText`, `cachedHint`, `releaseGroup`, `resolutionText`.

Helpers: `qualityRank`, `sizeGB`, `codec`, `isHEVC`, `isObjectAudio`, `audioLine`.
Statics: `StreamFacts.parse(texts, sizeBytes, statedQuality, seeders)`,
`StreamFacts.qualityRank(quality)`, `StreamFacts.qualityLabel(rank)`,
`StreamFacts.normalizedQuality(raw, fallbackText)`.

A bitrate is a stated number or it does not exist. Size is never divided by a guessed runtime.

### `enum StreamSort { BEST, READY, SIZE, BITRATE, NEWEST }`
### `enum SourceTapAction { PLAY, DOWNLOAD, ASK }`
### `enum DebridService { TORBOX, REAL_DEBRID }`

### `PlaybackRules(...)`

The owner's picking policy, ported from iOS. Fields: `enabled`, `minQualityRank`, `maxSizeGB`,
`readyOnly`, `hdrOnly`, `hevcOnly`, `atmosOnly`, `minSeeders`, `excludeKeywords`,
`preferredAudioLanguages`, `sort`, `tapAction`, `debridPriority`, `autoPlay`,
`autoPlayMinSources`.

| Member | Meaning |
|---|---|
| `eligible(rows): List<StreamRow>` | The allowed rows. Returns the original list rather than none. |
| `allows(row): Boolean` | Does one row pass every rule? |
| `openingSort(): StreamSort` | The sort the list opens in. |
| `effectiveTapAction(): SourceTapAction` | What OK does. |
| `checkOrder(configured): List<DebridService>` | Which provider is asked first. |
| `mayAutoPlay(eligibleCount): Boolean` | May auto-play commit on this many rows? |
| `summary(): String` | One line for the Settings row. |
| `normalizedKeywords: List<String>` | Lower-cased, trimmed, deduplicated. |
| `PlaybackRules.DEFAULT` | Stock behaviour. |
| `PlaybackRules.JUNK_KEYWORDS` | The CAM/TS family, as a one-tap preset. |
| `PlaybackRules.from(element)` / `PlaybackRules.parse(rawJson)` | Tolerant decode. |
| `PlaybackRules.excluded(text, keywords)` | Keywords of 3 characters or fewer match whole tokens. |
| `PlaybackRules.firstExcludedKeyword(text, keywords)` | The word that fired, for the reason line. |
| `PlaybackRules.sizeText(gb)` | "8 GB" / "7.5 GB". |

A row with no stated size always survives a size cap. A cap hides what is known to be too big.

### `RankReason(text: String, caution: Boolean)`

One line in the right-hand pane. `caution` draws the amber mark.

### `RankedRow(row, score, reasons, eligible)`

`cautions` is the caution subset. `eligible` is false when the rules rejected the row.

### `object StreamRanker`

| Function | Meaning |
|---|---|
| `rank(rows, rules, hardwareVideoCodecs): List<RankedRow>` | Order the list and say why. |
| `facts(row): List<Pair<String, String>>` | The label/value pairs for the facts pane. |

Two guarantees, both pinned by tests. A non-empty input never gives an empty output. The same
rows give the same order every time; ties break on row id.

Dolby Vision is demoted hard when `hardwareVideoCodecs` has no `dolbyvision`. It is never
removed. Pass the box's decoder names in lower case, read from `MediaCodecList`.

### `StreamSearchState(rows, attempted, pending, failed, done)`

What the Streams screen knows right now. `rows` only grows and never reorders.

### `class StreamSearch(registry, client, health, perAddonTimeoutMillis, setDeadlineMillis)`

`search(type, id, season?, episode?): Flow<StreamSearchState>`

Fans out to every stream add-on. Publishes rows as they land. Per add-on 8 s, whole set 12 s.
One add-on failing never stops the others.

### `Resolved(url, headers, filename, sizeBytes, service)`

A URL the player can open.

### `sealed interface DebridOutcome`

`Success(resolved)`, `MissingApiKey`, `NotCached`, `Stale`, `Error`.

### `class DebridResolver(torboxKey, rdKey, okHttp, rules, clock, torboxBaseURL, realDebridBaseURL)`

| Member | Meaning |
|---|---|
| `resolve(row, season?, episode?): DebridOutcome` | A URL passes through. A hash goes to a provider. |
| `configured: Set<DebridService>` | Which providers have a key. |
| `clear()` | Forget every resolved link. |

Results live in memory for 15 minutes. Nothing is written to disk.

### `sealed interface AutoPickDecision`

`PlayBest(choice: RankedRow, why: String)` or `ShowList(reason: Reason)`.
`Reason` is `RULES_OFF`, `TOO_FEW_SOURCES`, `NOTHING_ELIGIBLE` or `BEST_ROW_HAS_A_CAUTION`.

### `object AutoPick`

`decide(ranked, rules): AutoPickDecision` and `describe(choice): String`.

Auto-play commits only when the rules asked for it, enough sources are in hand, and the top row
carries no caution.

---

## Example call sequences

### App start

```kotlin
val document = SettingsDocument.parse(storedSettingsJson)
val health = InMemoryAddonHealthStore()
val client = StremioClient(okHttp, cacheDir = context.cacheDir)
val registry = AddonRegistry(document, client, health)

val meta = MetaRepository(registry, client, document.tmdbAPIKey, context.cacheDir)
val ratings = RatingsRepository(document.mdbListAPIKey)
val debrid = DebridResolver(
    torboxKey = document.torboxAPIKey,
    rdKey = document.realDebridAPIKey,
    rules = document.playbackRules,
)
val search = StreamSearch(registry, client, health)

scope.launch { registry.refresh() }          // manifests, cached, cheap to repeat
```

Show `document.redacted()` on the Settings screen. Never a value.

### Open Detail

```kotlin
scope.launch {
    val title = meta.meta(type = "series", id = "tt7366338") ?: return@launch showMetaError()
    paintHero(title)                                  // §9.2, inside 400 ms from the row's data

    title.imdbID?.let { imdb ->
        val scores = ratings.ratings(imdb, isShow = title.type == "series")
        if (!scores.isEmpty) paintRatingsRow(scores)  // chips fill in where they sit
    }
    paintSeasons(title.seasons)
    paintEpisodes(title.episodes(season = 1))
    paintMoreLikeThis(title.similar)
}
```

`meta()` returning null is the error state. `Ratings.NONE` drops the row and moves the synopsis
up by 56 px.

### Open Streams

```kotlin
val rules = document.playbackRules
val boxCodecs = hardwareVideoCodecNames()     // lower case, from MediaCodecList

scope.launch {
    search.search(type = "series", id = "tt7366338", season = 1, episode = 4)
        .collect { state ->
            val ranked = StreamRanker.rank(state.rows, rules, boxCodecs)
            showRows(ranked)                  // insert below focus; hold a re-sort (§10.7)
            showCountChip(state.attempted, state.pending, state.failed, state.done)
            if (state.done && state.rows.isEmpty()) showEmptyState()
        }
}
```

The focused row's pane reads `RankedRow.reasons` and `StreamRanker.facts(row)`.

### Press Play

```kotlin
scope.launch {
    val ranked = StreamRanker.rank(rowsSoFar, rules, boxCodecs)
    when (val decision = AutoPick.decide(ranked, rules)) {
        is AutoPickDecision.PlayBest -> {
            showFindingCard(decision.why)     // "AIOStreams · 1080p · cached"
            play(decision.choice.row)
        }
        is AutoPickDecision.ShowList -> openStreamsScreen(ranked, decision.reason)
    }
}

suspend fun play(row: StreamRow) {
    when (val outcome = debrid.resolve(row, season = 1, episode = 4)) {
        is DebridOutcome.Success -> player.open(outcome.resolved.url, outcome.resolved.headers)
        DebridOutcome.NotCached -> toast("That copy is not on your debrid account yet.")
        DebridOutcome.MissingApiKey -> toast("Add a debrid key in Settings.")
        DebridOutcome.Stale, DebridOutcome.Error -> toast("That source did not answer. Try another.")
    }
}
```

Never store `outcome.resolved.url`. It dies with the session.
