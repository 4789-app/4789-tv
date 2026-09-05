package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSourceRepositoryTest {
    @Test
    fun responseFiltersUnsafeDuplicateAndUrlLessEntriesWithoutPersistence() = runTest {
        val requested = AtomicReference<String>()
        val payload = """
            {"streams":[
              {"url":"https://cdn.example/movie.mkv?token=ephemeral","name":"A"},
              {"url":"https://cdn.example/movie.mkv?token=ephemeral","name":"duplicate"},
              {"url":"http://cdn.example/insecure.mkv","name":"insecure"},
              {"url":"https://user:secret@cdn.example/leak.mkv","name":"userinfo"},
              {"infoHash":"abc"}
            ]}
        """.trimIndent().encodeToByteArray()
        val repository = RemoteSourceRepository(fetch = { url -> requested.set(url); payload })

        val streams = repository.load(
            "https://addon.example/config/manifest.json",
            "movie",
            "tt26657236",
        )

        assertEquals("https://addon.example/config/stream/movie/tt26657236.json", requested.get())
        assertEquals(1, streams.size)
        assertEquals("https://cdn.example/movie.mkv?token=ephemeral", streams.single().url)
        assertNull(streams.single().durableURLForTest())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedInjectedBodyIsRejectedBeforeDecode() = runTest {
        RemoteSourceRepository(fetch = { ByteArray(RemoteStreamPolicy.MAXIMUM_RESPONSE_BYTES + 1) })
            .load("https://addon.example/manifest.json", "movie", "tt1")
    }
}

private fun StreamEntry.durableURLForTest(): Nothing? = null
