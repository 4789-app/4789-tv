package com.fourseveneightnine.phone

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineMediaStoreTest {
    private lateinit var context: Context
    private lateinit var store: OfflineMediaStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = OfflineMediaStore(context)
        store.remove(TITLE_ID)
        store.cleanInterruptedImports()
    }

    @After
    fun tearDown() {
        store.remove(TITLE_ID)
        store.cleanInterruptedImports()
    }

    @Test
    fun importsPrivateBytesAndSurvivesNewStoreInstance() {
        runBlocking {
            val source = File(context.cacheDir, "offline-import-source.bin")
            val expected = ByteArray(48 * 1024) { (it % 251).toByte() }
            source.writeBytes(expected)

            assertTrue(store.import(TITLE_ID, Uri.fromFile(source)))
            val restored = OfflineMediaStore(context).playbackUri(TITLE_ID)
            assertNotNull(restored)
            assertArrayEquals(expected, File(requireNotNull(restored).path!!).readBytes())
            assertTrue(OfflineMediaStore(context).remove(TITLE_ID))
            assertFalse(File(requireNotNull(restored).path!!).exists())
            source.delete()
        }
    }

    @Test
    fun importsCurrentRemoteBytesWithoutPersistingTheUrl() {
        runBlocking {
            val titleID = "$TITLE_ID-remote"
            val expected = ByteArray(64 * 1024) { (it % 239).toByte() }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(expected.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }.build()
            val remoteStore = OfflineMediaStore(context, client)

            assertTrue(remoteStore.importRemote(titleID, "https://cdn.example/signed?token=secret"))
            val restored = OfflineMediaStore(context).playbackUri(titleID)
            assertNotNull(restored)
            assertArrayEquals(expected, File(requireNotNull(restored).path!!).readBytes())
            assertFalse(
                context.filesDir.walkTopDown().filter(File::isFile).any {
                    it.readBytes().toString(Charsets.UTF_8).contains("cdn.example")
                },
            )
            assertTrue(remoteStore.remove(titleID))
        }
    }

    @Test
    fun summaryAndRemoveAllOnlyManagePrivateOfflineFiles() {
        runBlocking {
            val source = File(context.cacheDir, "offline-summary-source.bin")
            val expected = ByteArray(16 * 1024) { (it % 197).toByte() }
            source.writeBytes(expected)
            assertTrue(store.import("$TITLE_ID-summary-a", Uri.fromFile(source)))
            assertTrue(store.import("$TITLE_ID-summary-b", Uri.fromFile(source)))

            val summary = OfflineMediaStore(context).summary()
            assertTrue(summary.count >= 2)
            assertTrue(summary.bytes >= expected.size * 2L)
            assertTrue(OfflineMediaStore(context).removeAll())
            assertEquals(OfflineMediaSummary(0, 0), OfflineMediaStore(context).summary())
            source.delete()
        }
    }

    private companion object {
        const val TITLE_ID = "tt-offline-test"
    }
}
