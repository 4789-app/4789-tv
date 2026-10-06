package com.fourseveneightnine.tv.client.iptv

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stable, TV-local decisions. Jev results are bundled once, then persisted per observed group. */
internal class IptvGroupRanker(context: Context, http: OkHttpClient) {
    private val decisionHttp = http.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
    private val bundled: Map<String, IptvGroupDecision> = runCatching {
        context.assets.open("iptv-group-decisions.json").bufferedReader().use { reader ->
            Json.decodeFromString<Map<String, IptvGroupDecision>>(reader.readText())
        }.mapKeys { it.key.trim().lowercase() }
    }.getOrDefault(emptyMap())

    private val bundledVod: Map<String, IptvGroupDecision> = runCatching {
        context.assets.open("iptv-vod-decisions.json").bufferedReader().use {
            Json.decodeFromString<Map<String, IptvGroupDecision>>(it.readText())
        }
    }.getOrDefault(emptyMap())

    suspend fun assignVod(accounts: IptvAccounts, catalog: IptvCatalog,
                          allowRemote: Boolean = true): Map<String, IptvGroupDecision> = withContext(Dispatchers.Default) {
        val categories = (catalog.vod.map { IptvVodCategory(it.sourceId, it.kind, it.category, 0) } + catalog.vodCategories)
            .filter { it.kind == "movie" || it.kind == "series" }
            .distinctBy { vodDecisionKey(it.sourceId, it.kind, it.name) }
        val decisions = accounts.vodDecisions.toMutableMap()
        val sources = accounts.sources.associateBy(IptvSource::id)
        val unknown = categories.filter { category ->
            val key = vodDecisionKey(category.sourceId, category.kind, category.name)
            val source = sources[category.sourceId]
            val knownProvider = source?.kind == IptvSourceKind.XTREAM && runCatching {
                java.net.URI(source.host).host.equals("fastshare1.com", ignoreCase = true)
            }.getOrDefault(false)
            if (knownProvider && key !in decisions) bundledVod["${category.kind}|${category.name.trim().lowercase()}"]?.let {
                decisions[key] = it
            }
            key !in decisions || decisions[key]?.model == "local-fallback"
        }
        val sampleItems = catalog.vod.groupBy { vodDecisionKey(it.sourceId, it.kind, it.category) }
        var remoteAvailable = allowRemote && !accounts.decisionApiKey.isNullOrBlank()
        for (batch in unknown.chunked(5)) {
            val names = batch.map { vodDecisionKey(it.sourceId, it.kind, it.name) }
            val samples = batch.associate { category ->
                val key = vodDecisionKey(category.sourceId, category.kind, category.name)
                key to (listOf("${category.kind} category: ${category.name}") + sampleItems[key].orEmpty().take(12).map(IptvVod::title))
            }
            val remote = if (!remoteAvailable) emptyMap() else try {
                withContext(Dispatchers.IO) { classifyBatch(names, samples, requireNotNull(accounts.decisionApiKey), vod = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { remoteAvailable = false; emptyMap() }
            batch.forEach { category ->
                val key = vodDecisionKey(category.sourceId, category.kind, category.name)
                decisions[key] = remote[key] ?: decisions[key] ?: vodFallback(category.name)
            }
        }
        decisions
    }

    suspend fun assign(accounts: IptvAccounts, channels: List<IptvChannel>,
                       allowRemote: Boolean = true): IptvAccounts = withContext(Dispatchers.Default) {
        val observed = channels.map(IptvChannel::group).distinct()
        if (observed.isEmpty()) return@withContext accounts
        val decisions = accounts.groupDecisions.mapValues { (_, decision) ->
            if (accounts.decisionPolicyVersion < 2) decision.copy(
                priority = priorities[decision.bucket] ?: decision.priority) else decision
        }.toMutableMap()
        val unknown = observed.filter { name ->
            name !in decisions || accounts.decisionApiKey != null && decisions[name]?.model == "local-fallback"
        }
        val remote = if (!allowRemote || accounts.decisionApiKey.isNullOrBlank()) emptyMap() else
            classifyNew(unknown.filterNot { bundled.containsKey(it.trim().lowercase()) },
                channels, accounts.decisionApiKey)
        observed.forEach { name ->
            if (name in unknown) decisions[name] = bundled[name.trim().lowercase()] ?: remote[name] ?: fallback(name)
        }
        val known = (accounts.groupOrder + observed).distinct()
        val order = if (accounts.manualGroupOrder) known else stableOrder(known, accounts.groupOrder, decisions)
        return@withContext if (accounts.groupDecisions == decisions && accounts.groupOrder == order &&
            accounts.decisionPolicyVersion >= 2) accounts
            else accounts.copy(groupDecisions = decisions, groupOrder = order, decisionPolicyVersion = 2)
    }

    private suspend fun classifyNew(names: List<String>, channels: List<IptvChannel>, key: String): Map<String, IptvGroupDecision> {
        val samples = channels.groupBy(IptvChannel::group).mapValues { (_, items) -> items.take(5).map(IptvChannel::name) }
        val result = LinkedHashMap<String, IptvGroupDecision>()
        for (batch in names.chunked(5)) {
            val classified = runCatching { withContext(Dispatchers.IO) { classifyBatch(batch, samples, key) } }
                .getOrNull() ?: break
            result.putAll(classified)
        }
        return result
    }

    private fun classifyBatch(batch: List<String>, samples: Map<String, List<String>>,
                              key: String, vod: Boolean = false): Map<String, IptvGroupDecision> {
        val body = buildJsonObject {
            put("model", "jev-latest")
            put("state", buildJsonObject {
                put("category_names", JsonArray(batch.map(::JsonPrimitive)))
            })
            put("questions", buildJsonObject {
                batch.forEachIndexed { index, name ->
                    put("g$index", buildJsonObject {
                        put("type", "choice")
                        put("instructions", (if (vod) "Classify this movie/series category using the supplied category and title samples. " +
                            "ORIGINAL requires clear evidence of original production language across the samples. " +
                            "DUBBED means explicitly dubbed titles; MULTI, collections, conflicting titles or insufficient evidence are MIXED. " +
                            "Never infer original language solely from the category label. "
                         else "Classify IPTV category '$name' using its language, region, and subject. ") +
                            "Example channels: ${samples[name].orEmpty().joinToString("; ")}. " +
                            "Choose the most specific supported bucket. Telugu takes precedence when clearly Telugu; " +
                            "cricket is separate from other sports.")
                        put("criteria", buildJsonObject { (if (vod) vodCriteria else criteria).forEach { (bucket, meaning) -> put(bucket, meaning) } })
                    })
                }
            })
        }
        val request = Request.Builder().url("https://api.typesafe.ai/v1/systemone")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        decisionHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Jev unavailable")
            val parsed = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: error("Invalid Jev response")
            val answers = parsed["answers"] as? JsonObject ?: error("Missing Jev decisions")
            val model = (parsed["model"] as? JsonPrimitive)?.content ?: "jev-latest"
            return batch.mapIndexedNotNull { index, name ->
                val answer = answers["g$index"] as? JsonObject ?: return@mapIndexedNotNull null
                val bucket = (answer["choice"] as? JsonPrimitive)?.content ?: return@mapIndexedNotNull null
                val priority = (if (vod) vodPriorities else priorities)[bucket] ?: return@mapIndexedNotNull null
                val confidence = (answer["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val guarded = if (vod && bucket.endsWith("_ORIGINAL") && confidence < 0.8)
                    bucket.removeSuffix("_ORIGINAL") + "_MIXED" else bucket
                name to IptvGroupDecision(guarded, if (vod) vodPriorities[guarded] ?: priority else priority, confidence, model)
            }.toMap()
        }
    }

    /** Conservative local fallback for a newly added provider whose groups have no cached Jev result. */
    private fun fallback(name: String): IptvGroupDecision {
        val text = name.lowercase()
        val (bucket, priority) = when {
            "telugu" in text -> "TELUGU" to 0
            "cricket" in text || Regex("\\b(ipl|t20|odi)\\b").containsMatchIn(text) -> "CRICKET" to 1
            "sport" in text || "espn" in text -> "SPORTS" to 2
            "hindi" in text -> "HINDI" to 3
            "tamil" in text -> "TAMIL" to 4
            "india" in text && "english" in text && "movie" in text -> "INDIA_ENGLISH_MOVIES" to 5
            "india" in text && "english" in text -> "INDIA_ENGLISH_ENTERTAINMENT" to 6
            ("usa" in text || "us |" in text) && "movie" in text -> "US_ENGLISH_MOVIES" to 8
            "english" in text && "movie" in text -> "ENGLISH_MOVIES" to 7
            "usa" in text || "us |" in text -> "US_ENGLISH_ENTERTAINMENT" to 9
            "india" in text || "indian" in text -> "OTHER_INDIAN" to 10
            "english" in text -> "OTHER_ENGLISH" to 11
            else -> "INTERNATIONAL" to 12
        }
        return IptvGroupDecision(bucket, priority, 0.0, "local-fallback")
    }

    companion object {
        internal fun vodDecisionKey(sourceId: String, kind: String, name: String): String =
            "$sourceId|$kind|${name.trim().lowercase(java.util.Locale.ROOT)}"

        private val vodPriorities = linkedMapOf(
            "TELUGU_ORIGINAL" to 0, "TELUGU_MIXED" to 1,
            "CRICKET_ORIGINAL" to 2, "CRICKET_MIXED" to 2,
            "SPORTS_ORIGINAL" to 3, "SPORTS_MIXED" to 3,
            "HINDI_ORIGINAL" to 4, "HINDI_MIXED" to 5,
            "TAMIL_ORIGINAL" to 6, "TAMIL_MIXED" to 7,
            "ENGLISH_ORIGINAL" to 9, "ENGLISH_MIXED" to 12,
            "OTHER_INDIAN_ORIGINAL" to 10, "OTHER_INDIAN_MIXED" to 13,
            "INTERNATIONAL_ORIGINAL" to 14, "INTERNATIONAL_MIXED" to 15,
            "MIXED_MIXED" to 17,
            "UNKNOWN_MIXED" to 19,
            "TELUGU_DUBBED" to 20, "HINDI_DUBBED" to 21, "TAMIL_DUBBED" to 22,
            "ENGLISH_DUBBED" to 23, "OTHER_INDIAN_DUBBED" to 24,
            "INTERNATIONAL_DUBBED" to 25, "UNKNOWN_DUBBED" to 26,
            "MIXED_DUBBED" to 27, "CRICKET_DUBBED" to 28, "SPORTS_DUBBED" to 29,
        )
        private val vodCriteria = vodPriorities.mapValues { (bucket, _) ->
            val language = bucket.substringBeforeLast('_')
            when {
                bucket.endsWith("_ORIGINAL") -> "$language productions with strong original-language evidence, or explicitly $language sports replays"
                bucket.endsWith("_DUBBED") -> "Explicitly dubbed into $language"
                else -> "$language mixed, multilingual, collections, or uncertain original-language evidence"
            }
        }
        internal fun vodFallback(name: String): IptvGroupDecision {
            val text = name.lowercase(java.util.Locale.ROOT)
            val language = when {
                "telugu" in text -> "TELUGU"
                "cricket" in text -> "CRICKET"
                "sport" in text -> "SPORTS"
                "hindi" in text -> "HINDI"
                "tamil" in text -> "TAMIL"
                "english" in text -> "ENGLISH"
                listOf("kannada", "malayalam", "marathi", "bangla").any { it in text } -> "OTHER_INDIAN"
                else -> "UNKNOWN"
            }
            val bucket = language + if ("dub" in text) "_DUBBED" else "_MIXED"
            return IptvGroupDecision(bucket, vodPriorities.getValue(bucket), 0.0, "local-fallback")
        }

        private val priorities = linkedMapOf(
            "TELUGU" to 0, "CRICKET" to 1, "SPORTS" to 2, "HINDI" to 3, "TAMIL" to 4,
            "INDIA_ENGLISH_MOVIES" to 5, "INDIA_ENGLISH_ENTERTAINMENT" to 6,
            "ENGLISH_MOVIES" to 7, "US_ENGLISH_MOVIES" to 8,
            "US_ENGLISH_ENTERTAINMENT" to 9, "OTHER_INDIAN" to 10, "OTHER_ENGLISH" to 11,
            "INTERNATIONAL" to 12, "UNRELATED" to 13,
        )
        private val criteria = linkedMapOf(
            "TELUGU" to "Primarily Telugu Indian TV, movies, news, or entertainment",
            "CRICKET" to "Cricket channels and live cricket events",
            "SPORTS" to "Other sports channels and live sports events",
            "HINDI" to "Primarily Hindi Indian channels",
            "TAMIL" to "Primarily Tamil Indian channels",
            "INDIA_ENGLISH_MOVIES" to "English movie channels from India",
            "INDIA_ENGLISH_ENTERTAINMENT" to "English entertainment or news from India",
            "OTHER_INDIAN" to "Other or mixed Indian languages",
            "ENGLISH_MOVIES" to "English movie channels without a clear country",
            "US_ENGLISH_MOVIES" to "US English movie channels",
            "US_ENGLISH_ENTERTAINMENT" to "US English entertainment or news",
            "OTHER_ENGLISH" to "Other English channels",
            "INTERNATIONAL" to "Other international categories",
            "UNRELATED" to "Unclear, shopping, adult, or unrelated category",
        )
        internal fun stableOrder(observed: List<String>, prior: List<String>,
                                 decisions: Map<String, IptvGroupDecision>): List<String> {
            val oldIndex = prior.withIndex().associate { (index, name) -> name to index }
            val providerIndex = observed.withIndex().associate { (index, name) -> name to index }
            return observed.distinct().sortedWith(compareBy<String>(
                { decisions[it]?.priority ?: 12 },
                { oldIndex[it] ?: Int.MAX_VALUE },
                { providerIndex[it] ?: Int.MAX_VALUE },
            ))
        }
    }
}
