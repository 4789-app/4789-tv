package com.fourseveneightnine.phone

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A download that dies half way must continue, not start again.
 *
 * The fake server here truncates the first reply, then honours a byte range on the second. The
 * point being proved is that the second attempt asks only for the missing half, that the finished
 * file is byte-for-byte right, and that no signed link ever reached the phone's own storage.
 */
class OfflineDownloadResumeTest {
    private lateinit var context: Context
    private val expected = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val half = expected.size / 2

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        OfflineMediaStore(context).apply {
            remove(TITLE_ID)
            cleanInterruptedImports()
        }
        OfflineDownloadRecipeStore(context, PREFERENCE_NAME).clearAll()
    }

    @After
    fun tearDown() {
        OfflineMediaStore(context).apply {
            remove(TITLE_ID)
            cleanInterruptedImports()
        }
        OfflineDownloadRecipeStore(context, PREFERENCE_NAME).clearAll()
    }

    @Test
    fun anInterruptedDownloadKeepsItsBytesAndAsksOnlyForTheRest() = runBlocking {
        val truncated = OfflineMediaStore(context, client { chain ->
            assertNull("first attempt must not ask for a range", chain.request().header("Range"))
            reply(chain, code = 200, body = body(0, half, expected.size.toLong()))
        })

        val first = truncated.downloadRemote(TITLE_ID, SIGNED_URL, null, 0)

        assertFalse(first.completed)
        assertEquals(half.toLong(), first.bytesDone)
        assertEquals(ETAG, first.fingerprint)
        assertNull(OfflineMediaStore(context).playbackUri(TITLE_ID))
        assertEquals(half.toLong(), OfflineMediaStore(context).partialBytes(TITLE_ID))

        val requestedBytes = AtomicInteger(0)
        val resuming = OfflineMediaStore(context, client { chain ->
            assertEquals("bytes=$half-", chain.request().header("Range"))
            assertEquals(ETAG, chain.request().header("If-Range"))
            requestedBytes.set(expected.size - half)
            reply(
                chain,
                code = 206,
                body = body(half, expected.size, (expected.size - half).toLong()),
                contentRange = "bytes $half-${expected.size - 1}/${expected.size}",
            )
        })

        val second = resuming.downloadRemote(TITLE_ID, SIGNED_URL, first.fingerprint, expected.size.toLong())

        assertTrue(second.completed)
        assertEquals(expected.size - half, requestedBytes.get())
        val saved = OfflineMediaStore(context).playbackUri(TITLE_ID)
        assertNotNull(saved)
        assertArrayEquals(expected, File(requireNotNull(requireNotNull(saved).path)).readBytes())
        assertEquals(0L, OfflineMediaStore(context).partialBytes(TITLE_ID))
        assertFalse(privateDataHolds("signed-link.example"))
        assertFalse(privateDataHolds("expires-soon"))
    }

    @Test
    fun bytesThatMovedOnTheServerRestartTheDownloadInsteadOfAppending() = runBlocking {
        val truncated = OfflineMediaStore(context, client { chain ->
            reply(chain, code = 200, body = body(0, half, expected.size.toLong()))
        })
        assertFalse(truncated.downloadRemote(TITLE_ID, SIGNED_URL, null, 0).completed)
        assertEquals(half.toLong(), OfflineMediaStore(context).partialBytes(TITLE_ID))

        // The server answers the range, but with a different ETag. The half already on disk belongs
        // to a file that no longer exists, so it must be thrown away rather than appended to.
        val moved = OfflineMediaStore(context, client { chain ->
            reply(
                chain,
                code = 206,
                body = body(half, expected.size, (expected.size - half).toLong()),
                contentRange = "bytes $half-${expected.size - 1}/${expected.size}",
                etag = "\"moved\"",
            )
        })

        val outcome = moved.downloadRemote(TITLE_ID, SIGNED_URL, ETAG, expected.size.toLong())

        assertFalse(outcome.completed)
        assertEquals(0L, outcome.bytesDone)
        assertEquals(0L, OfflineMediaStore(context).partialBytes(TITLE_ID))
    }

    @Test
    fun aQueuedTitleKeepsItsPartialFileWhenTheAppStartsAgain() = runBlocking {
        val truncated = OfflineMediaStore(context, client { chain ->
            reply(chain, code = 200, body = body(0, half, expected.size.toLong()))
        })
        assertFalse(truncated.downloadRemote(TITLE_ID, SIGNED_URL, null, 0).completed)

        val store = OfflineMediaStore(context)
        store.cleanInterruptedImports(setOf(TITLE_ID))
        assertEquals(half.toLong(), store.partialBytes(TITLE_ID))

        store.cleanInterruptedImports()
        assertEquals(0L, store.partialBytes(TITLE_ID))
    }

    @Test
    fun theRecipeStoreRoundTripsAJobWithoutHoldingALink() {
        val entry = com.fourseveneightnine.contract.StreamEntry(
            url = SIGNED_URL,
            name = "4K",
            filename = "movie.mkv",
            sizeBytes = expected.size.toLong(),
        )
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe(TITLE_ID, "movie", entry))
        val store = OfflineDownloadRecipeStore(context, PREFERENCE_NAME)
        val job = OfflineDownloadJob(recipe, OfflineDownloadState.Queued, 1, 2, 1, ETAG, "waiting")

        assertTrue(store.save(job))
        assertEquals(job, store.load(TITLE_ID))
        assertEquals(listOf(job), store.all())
        // The whole private data directory, not only `files/`. The recipe store writes into
        // `shared_prefs/`, which is a sibling of `filesDir` — checking `filesDir` alone would look
        // everywhere except the one place the record actually lands. The hash is the control: it IS
        // written, so finding it proves the sweep can see the record it is judging.
        assertTrue(privateDataHolds(recipe.selectorHash))
        assertFalse(privateDataHolds("signed-link.example"))
        assertFalse(privateDataHolds("expires-soon"))
        assertTrue(store.remove(TITLE_ID))
        assertNull(store.load(TITLE_ID))
    }

    /**
     * True when any file the app owns holds [text], in any of its private directories.
     *
     * The queue record Base64-encodes each field, so a plain-text sweep alone would report "clean"
     * for a URL that really is on disk.
     *
     * Encoding the search term and looking for that string is NOT enough either. Base64 packs three
     * bytes into four characters, so the encoding of a substring only appears verbatim when the
     * stored field starts at a multiple of three. A URL buried inside a longer encoded field slips
     * straight past that check. A mutation proved it: a real signed URL written into the recipe's
     * filename field left this sweep reporting clean.
     *
     * So three searches run, and any one of them is a hit:
     *   1. the plain text;
     *   2. the encoded form of the term, which finds a field stored as exactly this value;
     *   3. every Base64-looking run decoded back to bytes, which finds the term wherever it sits
     *      inside a longer encoded field.
     * Search 2 alone is what the mutation defeated. Searches 2 and 3 catch different things, so
     * both stay.
     */
    private fun privateDataHolds(text: String): Boolean {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())
        return requireNotNull(context.filesDir.parentFile)
            .walkTopDown()
            .filter(File::isFile)
            .any { candidate ->
                val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return@any false
                val body = bytes.toString(Charsets.ISO_8859_1)
                body.contains(text) || body.contains(encoded) || decodedRunsHold(body, text)
            }
    }

    /** True when any Base64 run inside [body] decodes to text containing [text]. */
    private fun decodedRunsHold(body: String, text: String): Boolean =
        BASE64_RUN.findAll(body).any { run ->
            val token = run.value
            // Try all three byte alignments. A field stored mid-record may not begin on a 3-byte
            // boundary, so the run as written can decode to a shifted view of the original bytes.
            (0..2).any { skip ->
                // The store uses the URL-safe alphabet, other writers use the standard one. Fold
                // both onto the standard alphabet so a single decoder covers either.
                val trimmed = token.drop(skip)
                    .replace('-', '+')
                    .replace('_', '/')
                    .trimEnd('=')
                    .let { it.dropLast(it.length % 4) }
                if (trimmed.length < 4) return@any false
                val decoded = runCatching {
                    BASE64_DECODER.decode(trimmed).toString(Charsets.ISO_8859_1)
                }.getOrNull()
                decoded?.contains(text) == true
            }
        }

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun reply(
        chain: Interceptor.Chain,
        code: Int,
        body: ResponseBody,
        contentRange: String? = null,
        etag: String = ETAG,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 206) "Partial Content" else "OK")
        .header("ETag", etag)
        .apply { if (contentRange != null) header("Content-Range", contentRange) }
        .body(body)
        .build()

    /** Bytes `from` until `until`, but declaring `declared` — so a short reply looks truncated. */
    private fun body(from: Int, until: Int, declared: Long): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType = "video/mp4".toMediaType()

        override fun contentLength(): Long = declared

        override fun source(): BufferedSource =
            Buffer().write(expected, from, until - from)
    }

    private companion object {
        const val TITLE_ID = "tt-resume-test"
        const val PREFERENCE_NAME = "offline-download-recipes-test"
        const val ETAG = "\"v1\""
        const val SIGNED_URL = "https://signed-link.example/a/movie.mkv?token=expires-soon"

        /** A run of Base64 characters long enough to hold a URL. Both alphabets are accepted. */
        val BASE64_RUN = Regex("""[A-Za-z0-9+/_-]{16,}={0,2}""")

        /** Decodes either Base64 alphabet, and tolerates missing padding. */
        val BASE64_DECODER: Base64.Decoder = Base64.getMimeDecoder()
    }
}
