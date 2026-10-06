# client-data, part B

Four packages: `library`, `catalog`, `images`, `refresh`. Part A owns `settings`, `addons`,
`meta` and `streams`. Nothing here depends on part A. Settings values arrive as functions
passed to a constructor.

Package root: `com.fourseveneightnine.tv.client.data`.

---

## library

Room, one database file called `library.db`. Room writes the schema to
`client-data/schemas/`. Library state only. No stream URL, no magnet, and no token.

### Tables

| Table | Key | Holds |
|---|---|---|
| `watch_progress` | `canonical_id` | position, duration, season, episode, `source` = local or phone |
| `recent` | `canonical_id` | title, poster, backdrop, last played, `origin` = local or phone |
| `collection` | `id` | name, accent, kind, source ref, sort index, pinned flag |
| `collection_item` | `collection_id` + `canonical_id` | title, poster, sort index, added at |
| `addon_health` | `manifest_url` | last good time, fail count, last error |
| `job` | `name` | state, start time, finish time, message |

### Types

```kotlin
enum class RowOrigin { LOCAL, PHONE }
enum class CollectionKind { MANUAL, CATALOG, LETTERBOXD, MDBLIST, SYSTEM }
enum class JobState { IDLE, RUNNING, OK, FAILED }

data class ContinueItem(canonicalId, mediaType, title, posterUrl, backdropUrl,
                        season, episode, positionMs, durationMs,
                        lastActivityMillis, origin)          // + progressFraction, remainingMs
data class PhoneRecentRow(canonicalId, mediaType, title, posterUrl, backdropUrl,
                          season, episode, positionMs, durationMs, lastPlayedAt)
data class CollectionSummary(id, name, accent, kind, count, pinnedHome, previewPosters)
data class CollectionItem(canonicalId, mediaType, title, posterUrl, sortIndex, addedAtMillis)
data class CollectionDetail(id, name, accent, kind, sourceRef, pinnedHome,
                            sortIndex, updatedAtMillis, items)
data class JobStatus(name, state, startedAtMillis, finishedAtMillis, message)

object SystemCollections {
    const val CONTINUE_WATCHING_REF = "system:continue-watching"
    const val MY_CLOUD_REF = "system:my-cloud"
}
```

### Build it

```kotlin
val database = LibraryDatabase.create(context)          // or createInMemory(context) in tests
val library = LibraryRepository(database)
```

### What each call means

| Call | Meaning |
|---|---|
| `continueWatching(): Flow<List<ContinueItem>>` | Home row 1. Merges local progress and the phone mirror. |
| `writeProgress(id, type, season?, episode?, positionMs, durationMs)` | Writes a local progress row. Call it every 15 s while playing, and on pause, stop and background. |
| `markWatched(id, type, season?, episode?)` | Writes 100% progress, so the row leaves Continue. |
| `replacePhoneRecents(rows)` | Replaces every `origin = phone` row in one go. |
| `recordLocalPlay(id, type, title, poster?, backdrop?, season?, episode?, playedAt?)` | Writes the display data for a title the box played. |
| `collections(): Flow<List<CollectionSummary>>` | Folder cards. System folders first, then yours by sort index. |
| `collection(id): Flow<CollectionDetail?>` | One folder with its titles. Emits null after a delete. |
| `create(name, accent, kind, sourceRef?): Long` | Makes a folder and returns its id. |
| `rename(id, name)` / `setAccent(id, accent)` / `setPinned(id, on)` | One field each. Saves at once. |
| `moveCollection(id, delta): Boolean` | Move mode on the grid. False at the ends and for system folders. |
| `addItem(collectionId, id, type, title, poster?)` | Adds a title. Adding it twice keeps its place. |
| `removeItem(collectionId, id)` | Removes a title and renumbers the rest. |
| `moveItem(collectionId, id, delta): Boolean` | Move mode inside a folder. False for a sourced folder. |
| `delete(id)` | Removes the folder and its titles. Refuses a system folder. |
| `collectionsContaining(id): Flow<Set<Long>>` | Which folders hold this title. Drives the add checklist. |
| `replaceSourcedItems(collectionId, items)` | The refresh result for a sourced folder. Old titles go. |
| `ensureSystemCollections(): List<Long>` | Makes Continue Watching and My Cloud. Safe to call every time. |
| `addonHealth(): Flow<List<AddonHealthEntity>>` | Read for part A. |
| `recordAddonResult(manifestUrl, ok, error?)` | Write for part A. Three failures in a row means grey, never dropped. |
| `jobs(): Flow<List<JobStatus>>` | Settings, Jobs page. |
| `updateJob(name, state, message?)` | `RUNNING` stamps the start. Any other state stamps the finish. |

### Continue Watching rules

1. Local progress beats the phone mirror for the same canonical id.
2. Sorted by last activity, newest first.
3. A row under 2% has not started. A row over 95% is finished. Both are dropped.
4. A row with an unknown duration is kept. There is no progress to judge it by.
5. A phone push never overwrites a local row. It only fills empty fields, such as a
   missing poster.

---

## catalog

Signed snapshots on disk for the public TMDB shelves and the owner's private catalog.

### Types

```kotlin
enum class SnapshotSource { PUBLIC_TMDB, PRIVATE_CATALOG }
enum class ShelfKind { TAMILMV_POPULAR, TAMILMV_RECENT, LETTERBOXD,
                       LETTERBOXD_FRIENDS, TMDB_MOVIES, TMDB_SERIES }
enum class SnapshotState { IDLE, LOADING, READY, FAILED }

data class CatalogItem(canonicalId, mediaType, title, year, posterUrl, backdropUrl,
                       imdbId, tmdbId, overview, genres)
data class Shelf(id, title, kind, items, generation, generatedAtMillis, complete)
data class SourceStatus(source, state, lastRunMillis, generation, generatedAtMillis,
                        itemCount, complete, message)
data class SnapshotStatus(sources) { fun of(source): SourceStatus? }

interface CatalogHttp                 // the network seam; OkHttpCatalogHttp is the real one
interface SnapshotVerifier            // ContractSnapshotVerifier does the Ed25519 check
class CatalogHttpException(statusCode)
```

### Build it

```kotlin
val snapshots = SnapshotStore(
    filesDir = context.filesDir,
    okHttp = okHttpClient,
    tokenProvider = { settings.catalogServerToken },   // part A supplies this
    letterboxdUsernames = { settings.letterboxdUsernames },
)
```

The token and the usernames are functions, not a settings object. This package never
imports part A.

### What each call means

| Call | Meaning |
|---|---|
| `shelves(): StateFlow<List<Shelf>>` | Every shelf, already in Home row order. |
| `status(): StateFlow<SnapshotStatus>` | One row per source for the Jobs page. |
| `hydrate()` | Paints from disk. Head file first, then the whole document. No network. |
| `refresh(force = false)` | Checks both manifests and promotes what changed. |
| `clear(source)` | Forgets one source's saved generations. |

### How a refresh behaves

1. `hydrate()` runs first if nothing is on screen yet.
2. The manifest is asked for with `If-None-Match`. A 304 answer ends it there.
3. A manifest whose generation matches the one on disk ends it there too, unless `force`.
4. Shards are fetched four at a time.
5. After 1.2 s the shelves that have arrived are painted, marked `complete = false`.
   The rest keep loading. A part-built generation is never written to disk.
6. A complete generation is written, then the `current` pointer flips. That flip is the
   promotion.
7. Old generations are deleted oldest first once the cache passes 64 MB. The generation
   `current` points at is never deleted.
8. Any failure, whether network, HTTP or signature, leaves the last good shelves on screen
   and sets the source state to `FAILED` with a message.

### Two traps

- A `Shelf` holds up to 2,000 items, so comparing two shelves compares every item. Key any
  UI state on `shelf.generation`, never on the shelf itself. Keying the Tamil MV hero on
  the whole snapshot reset it to card zero on every catalog write.
- A shelf that is still loading has no items. Never point a `FocusRequester` at a row that
  may be empty.

---

## images

### Build it

```kotlin
val loader = ImageLoaderFactory.create(context, okHttpClient, File(context.cacheDir, "artwork"))
```

Disk cache 256 MB. Memory cache 15% of the heap. `Cache-Control` and ETag from the server
are honoured. Crossfade 190 ms.

### What each call means

| Call | Meaning |
|---|---|
| `TmdbSize.sized(url, width): String` | Rewrites the TMDB size part of a URL. Other URLs pass through. |
| `TmdbSizeInterceptor` | Applies the same rule to every request Coil makes. Already added by the factory. |
| `PosterRequest(url, width).toImageRequest(context)` | One sized request. |
| `PosterRequest.poster(url)` | Width 342, for a 2:3 card. |
| `PosterRequest.wide(url)` | Width 780, for a 16:9 card. |
| `PosterRequest.backdrop(url)` | Width 1280, for the hero. |
| `ImageLoaderFactory.isLowRamDevice(context)` | True on a box where a 32-bit bitmap per card costs too much. |

A poster drops to `RGB_565` on a low-RAM box. A backdrop does not. Banding shows across a
gradient at three metres, and it does not show on a poster.

`w1280` exists for backdrops and stills, not for posters. Ask for 1280 on backdrop URLs
only.

---

## refresh

### Build it

```kotlin
val scheduler = RefreshScheduler(
    scope = applicationScope,
    snapshotStore = snapshots,
    addonCatalogRefresher = { addonRegistry.refreshCatalogs() },   // part A supplies this
    jobs = library.asJobSink(),
)
scheduler.schedulePeriodicRefresh(context)
```

### What each call means

| Call | Meaning |
|---|---|
| `onForeground(): Job` | The app came back to the front. Also sets the foreground flag. |
| `onTrustedSync(): Job` | The phone pushed new settings. |
| `manual(): Job` | The Refresh now button. Forces a refetch. |
| `refreshNow(force): Boolean` | Runs one refresh. False means another was already running. |
| `coalescedCount: Int` | How many refreshes were dropped as duplicates. |
| `lastRunAtMillis: Long?` | When the last refresh ended. |
| `schedulePeriodicRefresh(context)` | Enqueues the 6-hourly `RefreshWorker`. Unique by name. |
| `AppLifecycleFlag.onEnterForeground()` / `onLeaveForeground()` | The app sets these from its lifecycle observer. |
| `Freshness.label(generatedAtMillis, now): String?` | The muted label after a shelf header. |

Two job rows are written: `Catalog snapshots` and `Add-on catalogs`. Each goes `RUNNING`
then `OK` or `FAILED` with a message.

The 6-hourly worker does nothing while the app is in the background. It reads
`AppLifecycleFlag`.

### The label table

| Age | Words |
|---|---|
| under 30 minutes | nothing, the call returns null |
| 30 to 59 minutes | `updated 42m ago` |
| 1 to 23 hours | `updated 2h ago` |
| 24 to 47 hours | `updated yesterday` |
| 2 to 7 days | `updated 3 days ago` |
| over 7 days | `updated 12 March` |

It never says "stale". It is a fact, not a fault.

Note: the brief for this work said the label starts at one hour. The design spec, section
15.6, says 30 minutes and gives the minutes wording. The code follows the spec. Change one
constant, `Freshness.MINIMUM_AGE_MILLIS`, if the spec is wrong.

---

## Call sequences for the UI

### Home

```kotlin
// once, at startup
snapshots.hydrate()            // paints from disk, head file first
scheduler.onForeground()       // then checks the server

// in the view model
val shelves = snapshots.shelves()                 // rows 3 and below
val continueWatching = library.continueWatching() // row 1
val pinned = library.collections().map { list -> list.filter { it.pinnedHome } }  // row 2

// per row header
val label = Freshness.label(shelf.generatedAtMillis, System.currentTimeMillis())
```

Hide the label while that source is loading. Read
`snapshots.status().of(source)?.state == SnapshotState.LOADING`.

### Continue Watching

```kotlin
// the phone pushed its list
library.replacePhoneRecents(rows)

// the box started playing
library.recordLocalPlay(id, "series", title, poster, backdrop, season = 2, episode = 4)

// every 15 s, and on pause, stop and background
library.writeProgress(id, "series", 2, 4, positionMs, durationMs)

// the viewer chose Mark watched
library.markWatched(id, "series", 2, 4)
```

### Make and edit a collection

```kotlin
library.ensureSystemCollections()                     // on first open of the grid

val id = library.create("Sunday night", "#F2A0A0")    // after the TV keyboard
library.setAccent(id, "#2FD4C8")
library.setPinned(id, true)
library.addItem(id, canonicalId, "movie", title, posterUrl)
library.moveItem(id, canonicalId, -1)                 // move mode, LEFT
library.moveCollection(id, +1)                        // move mode on the grid, RIGHT
library.delete(id)                                    // after the confirm dialog

// the Add to collection panel
val checked = library.collectionsContaining(canonicalId)
```

A sourced folder takes its titles from its refresh instead:

```kotlin
library.replaceSourcedItems(id, itemsFromTheList)
```

### Jobs page

```kotlin
val rows = library.jobs()            // Source, Last run, Result, Message
val perSource = snapshots.status()   // Items and Generation per source

// the Refresh now button
scheduler.manual()
```

`JobStatus.state` maps to the Result column: `OK`, `Failed`, `Running`. A running row is
not focusable while it runs.

---

## Tests

56 tests, all green.

```bash
cd "4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :client-data:testDebugUnitTest \
  --tests 'com.fourseveneightnine.tv.client.data.library.*' \
  --tests 'com.fourseveneightnine.tv.client.data.catalog.*' \
  --tests 'com.fourseveneightnine.tv.client.data.images.*' \
  --tests 'com.fourseveneightnine.tv.client.data.refresh.*'
```

| File | Tests | Covers |
|---|---|---|
| `library/LibraryRepositoryTest.kt` | 23 | Continue merge rules, phone replace, folder order, move, sourced replace, system folders |
| `catalog/SnapshotStoreTest.kt` | 13 | Head split, partial paint, promotion, ETag, signature failure, eviction |
| `refresh/RefreshSchedulerTest.kt` | 7 | Coalescing, job rows, triggers, foreground flag |
| `refresh/FreshnessTest.kt` | 8 | The label table |
| `images/TmdbSizeTest.kt` | 5 | The URL rewrite table |

`catalog/FakeCatalogServer.kt` holds the fake network and the fake verifier. It serves
plain JSON, so a test can write a manifest by hand.
