package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.addons.defaultAddonHttpClient
import com.fourseveneightnine.tv.client.data.addons.getText
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient

/**
 * Everything the Detail screen needs about one title.
 *
 * Cinemeta answers first, because it answers for every IMDb id and it answers fast. TMDB then
 * fills the gaps Cinemeta leaves — the logo, a clean wide backdrop, cast photos, runtime,
 * certification, the trailer id — and only when the phone synced a TMDB key. Without a key the
 * screen still draws; §9.9 gives every missing field a fallback, so a gap is a layout instruction
 * rather than an error.
 *
 * The cache is a directory of JSON files, not a table. Twenty-odd blobs with a 24-hour life do not
 * earn a schema to migrate later, and this is the same approach `TVEnrichmentDiskCache` already
 * takes in `:app`.
 */
class MetaRepository(
    private val registry: AddonRegistry,
    private val client: StremioClient,
    private val tmdbKey: String? = null,
    cacheDir: File? = null,
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tmdbBaseURL: String = TMDB_BASE_URL,
) {
    private val directory = cacheDir?.let { File(it, "meta") }
    private val memory = LinkedHashMap<String, Pair<Meta, Long>>(MAX_MEMORY_ENTRIES, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, PendingMeta>()
    private val diskLock = Any()
    @Volatile private var cacheGeneration = 0L

    private class PendingMeta {
        val base = CompletableDeferred<Meta?>()
        val result = CompletableDeferred<Meta?>()
        @Volatile var cancelled = false
        @Volatile var preview: Meta? = null
    }

    /**
     * One title's metadata, or null when no add-on knows it.
     *
     * A repeat visit inside 24 hours costs nothing, and two callers asking together — the Detail
     * screen and a prefetch — issue one fetch, not two.
     */
    suspend fun meta(type: String, id: String, onBase: ((Meta) -> Unit)? = null): Meta? {
        val generation = cacheGeneration
        val key = "$type:$id"
        val cached = synchronized(memory) {
            memory[key]?.takeIf { clock() - it.second < CACHE_TTL_MILLIS }
                ?: run { memory.remove(key); null }
        }
        cached?.let { (meta, _) -> onBase?.invoke(meta); return meta }
        withContext(Dispatchers.IO) { readDisk(key) }?.let { meta ->
            rememberMeta(key, meta, generation)
            onBase?.invoke(meta)
            return meta
        }
        suspend fun join(pending: PendingMeta): Meta? {
            if (onBase != null) pending.base.await()?.let(onBase)
            val result = pending.result.await()
            return if (pending.cancelled) meta(type, id, onBase) else result
        }
        inFlight[key]?.let { return join(it) }
        val pending = PendingMeta()
        inFlight.putIfAbsent(key, pending)?.let { return join(it) }
        var answer: Meta? = null
        try {
            answer = fetch(type, id) { base ->
                pending.preview = base
                pending.base.complete(base)
                onBase?.invoke(base)
            }
            answer?.let { complete ->
                withContext(Dispatchers.IO) { writeDisk(key, complete, generation) }
                rememberMeta(key, complete, generation)
            }
            return answer
        } catch (cancelled: CancellationException) {
            pending.cancelled = true
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            inFlight.remove(key, pending)
            pending.base.complete(null)
            pending.result.complete(answer.takeUnless { pending.cancelled })
        }
    }
    /** Only memory: playback must not wait for disk, cast photos or a metadata server. */
    fun peek(type: String, id: String): Meta? {
        val key = "$type:$id"
        return synchronized(memory) { memory[key]?.takeIf { clock() - it.second < CACHE_TTL_MILLIS }?.first }
            ?: inFlight[key]?.preview
    }

    private fun rememberMeta(key: String, meta: Meta, generation: Long) = synchronized(memory) {
        if (generation != cacheGeneration) return@synchronized
        memory[key] = meta to clock()
        while (memory.size > MAX_MEMORY_ENTRIES) memory.remove(memory.keys.first())
    }

    /** Drop everything, for Settings → Jobs → "Clear caches". */
    fun clear() {
        synchronized(diskLock) {
            synchronized(memory) { cacheGeneration++; memory.clear() }
            inFlight.clear()
            runCatching { directory?.listFiles()?.forEach(File::delete) }
        }
    }

    private suspend fun fetch(type: String, id: String, onBase: ((Meta) -> Unit)?): Meta? {
        val base = withContext(Dispatchers.Default) {
            metaAddons().firstNotNullOfOrNull { addon ->
                try { client.meta(addon, type, id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        } ?: return null
        // Show the title, poster and synopsis as soon as Cinemeta answers. TMDB's enrichment
        // can take seconds on a cold network and must not hold the whole Detail page blank.
        onBase?.invoke(base)
        val key = tmdbKey?.takeIf(String::isNotBlank) ?: return base
        return try { withContext(Dispatchers.Default) { fillFromTmdb(base, key) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { base }
    }

    /** Cinemeta first, then any other add-on that declares the `meta` resource. */
    private fun metaAddons(): List<Addon> {
        val all = registry.metaAddons()
        val cinemeta = all.filter { it.manifestURL.contains("cinemeta", ignoreCase = true) }
        return cinemeta + all.filterNot { it in cinemeta }
    }

    /**
     * Ask TMDB only for what is missing.
     *
     * Two calls at most: `find` to turn an IMDb id into a TMDB one when Cinemeta did not give it,
     * then one `append_to_response` call that returns images, credits, videos and release dates
     * together. Language-free art is asked for first — a backdrop with burnt-in English titling is
     * worse than a clean still — while a logo WANTS its lettering, so English is fine there.
     */
    private suspend fun fillFromTmdb(base: Meta, key: String): Meta {
        val kind = if (base.type == "series") "tv" else "movie"
        val tmdbID = base.tmdbID ?: base.imdbID?.let { findTmdbID(it, kind, key) } ?: return base
        val append = "images,credits,videos,${if (kind == "tv") "content_ratings" else "release_dates"}"
        val url = "$tmdbBaseURL/$kind/$tmdbID?api_key=$key&append_to_response=$append" +
            "&include_image_language=en,null"
        val body = okHttp.getText(url, MAX_RESPONSE_BYTES).body
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return base
        val images = root["images"] as? JsonObject

        return base.copy(
            tmdbID = tmdbID,
            logo = base.logo ?: images?.bestImage("logos", LOGO_SIZE, preferTextless = false),
            backdrop = base.backdrop ?: images?.bestImage("backdrops", BACKDROP_SIZE),
            poster = base.poster ?: images?.bestImage("posters", POSTER_SIZE),
            runtimeMinutes = base.runtimeMinutes ?: root.int("runtime")
                ?: (root["episode_run_time"] as? JsonArray)
                    ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.intOrNull },
            certification = base.certification ?: certification(root, kind),
            description = base.description ?: root.string("overview"),
            trailerYouTubeID = base.trailerYouTubeID ?: trailer(root),
            cast = base.cast.ifEmpty { credits(root) }.let { members ->
                if (members.all { it.photo == null }) withPhotos(members, root) else members
            },
        )
    }

    private suspend fun findTmdbID(imdbID: String, kind: String, key: String): Int? {
        val url = "$tmdbBaseURL/find/$imdbID?api_key=$key&external_source=imdb_id"
        val body = runCatching { okHttp.getText(url, MAX_RESPONSE_BYTES).body }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val listKey = if (kind == "tv") "tv_results" else "movie_results"
        return (root[listKey] as? JsonArray)?.firstNotNullOfOrNull {
            (it as? JsonObject)?.int("id")
        }
    }

    private fun withPhotos(members: List<CastMember>, root: JsonObject): List<CastMember> {
        val byName = credits(root).associateBy { it.name.lowercase(Locale.ROOT) }
        return members.map { member ->
            val match = byName[member.name.lowercase(Locale.ROOT)] ?: return@map member
            member.copy(role = member.role ?: match.role, photo = member.photo ?: match.photo)
        }
    }

    private fun credits(root: JsonObject): List<CastMember> {
        val cast = ((root["credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty()
        return cast.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            CastMember(
                name = name,
                role = entry.string("character"),
                photo = entry.string("profile_path")?.let { "$IMAGE_BASE$PROFILE_SIZE$it" },
            )
        }.take(MAX_CAST)
    }

    private fun trailer(root: JsonObject): String? {
        val videos = ((root["videos"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("site").equals("YouTube", ignoreCase = true) }
        return (
            videos.firstOrNull { it.string("type").equals("Trailer", ignoreCase = true) }
                ?: videos.firstOrNull()
            )?.string("key")
    }

    private fun certification(root: JsonObject, kind: String): String? {
        if (kind == "tv") {
            val results = ((root["content_ratings"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
            return results.firstOrNull { it.string("iso_3166_1") == "US" }?.string("rating")
                ?: results.firstNotNullOfOrNull { it.string("rating") }
        }
        val results = ((root["release_dates"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        val us = results.firstOrNull { it.string("iso_3166_1") == "US" } ?: results.firstOrNull()
        return (us?.get("release_dates") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("certification") }
            .firstOrNull(String::isNotBlank)
    }

    /**
     * [preferTextless] is the whole point of this function. A backdrop with burnt-in English
     * titling is worse than a clean still, so art asks for the language-free file first. A logo is
     * the opposite case: the lettering IS the logo, so there English wins.
     */
    private fun JsonObject.bestImage(
        key: String,
        size: String,
        preferTextless: Boolean = true,
    ): String? {
        val list = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val ordered = list.sortedWith(
            compareBy<JsonObject> {
                when (it.string("iso_639_1")) {
                    null -> if (preferTextless) 0 else 1
                    "en" -> if (preferTextless) 1 else 0
                    else -> 2
                }
            }.thenByDescending { it.double("vote_average") ?: 0.0 },
        )
        return ordered.firstNotNullOfOrNull { it.string("file_path") }?.let { "$IMAGE_BASE$size$it" }
    }

    private fun readDisk(key: String): Meta? = synchronized(diskLock) {
        val file = File(directory ?: return@synchronized null, fileName(key))
        if (!file.isFile) return@synchronized null
        runCatching {
            val text = file.readText()
            val split = text.indexOf('\n')
            if (split < 0) return@runCatching null
            val storedAt = text.substring(0, split).toLongOrNull() ?: return@runCatching null
            if (clock() - storedAt >= CACHE_TTL_MILLIS) return@runCatching null
            json.decodeFromString(Meta.serializer(), text.substring(split + 1))
        }.getOrNull()
    }

    private fun writeDisk(key: String, meta: Meta, generation: Long) = synchronized(diskLock) {
        if (generation != cacheGeneration) return@synchronized
        val dir = directory ?: return@synchronized
        runCatching {
            dir.mkdirs()
            File(dir, fileName(key)).writeText(
                "${clock()}\n" + json.encodeToString(Meta.serializer(), meta),
            )
        }
    }

    /** SHA-256 so an id with a slash or a colon cannot become a path. */
    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

    companion object {
        const val TMDB_BASE_URL: String = "https://api.themoviedb.org/3"
        const val IMAGE_BASE: String = "https://image.tmdb.org/t/p/"

        /** Wide enough for a 1080p hero without paying for `/original/`. */
        const val BACKDROP_SIZE: String = "w1280"
        const val LOGO_SIZE: String = "w500"
        const val POSTER_SIZE: String = "w500"
        const val PROFILE_SIZE: String = "w185"

        const val CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000L
        const val MAX_RESPONSE_BYTES: Int = 1 * 1_024 * 1_024
        const val MAX_CAST: Int = 20
        const val MAX_MEMORY_ENTRIES: Int = 64

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}
