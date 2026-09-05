package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCastRepositoryTest {
    @Test
    fun stagesMetadataBeforeOpeningEphemeralUrl() = runTest {
        val requests = mutableListOf<String>()
        val repository = TvCastRepository { _, bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            requests += request["method"]!!.jsonPrimitive.content
            val id = request["id"]!!.jsonPrimitive.int
            """{"jsonrpc":"2.0","id":$id,"result":"OK"}""".encodeToByteArray()
        }

        assertTrue(
            repository.cast(
                TvCastTarget("192.168.1.20"),
                DiscoverItem(id = "tt1", type = "movie", title = "One"),
                "https://media.example/one.mkv?token=memory-only",
            ),
        )
        assertEquals(listOf("X4789.NowPlaying", "Player.Open"), requests)
    }

    @Test
    fun invalidUrlOrRpcFailureStopsBeforeOpen() = runTest {
        var calls = 0
        val repository = TvCastRepository { _, _ ->
            calls += 1
            """{"jsonrpc":"2.0","id":99,"error":{"code":-1}}""".encodeToByteArray()
        }
        val item = DiscoverItem(id = "tt1", type = "movie", title = "One")

        assertFalse(repository.cast(TvCastTarget("192.168.1.20"), item, "http://media.example/one"))
        assertEquals(0, calls)
        assertFalse(repository.cast(TvCastTarget("192.168.1.20"), item, "https://media.example/one"))
        assertEquals(1, calls)
    }

    @Test
    fun remoteCommandsUseReceiverPlayerAndMatchingIds() = runTest {
        val requests = mutableListOf<String>()
        val repository = TvCastRepository { _, bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            requests += request["method"]!!.jsonPrimitive.content
            val id = request["id"]!!.jsonPrimitive.int
            """{"jsonrpc":"2.0","id":$id,"result":{"speed":1}}""".encodeToByteArray()
        }
        val target = TvCastTarget("192.168.1.20")

        assertTrue(repository.playPause(target))
        assertTrue(repository.seek(target, forward = false))
        assertTrue(repository.seek(target, forward = true))
        assertTrue(repository.stop(target))
        assertEquals(
            listOf("Player.PlayPause", "Player.Seek", "Player.Seek", "Player.Stop"),
            requests,
        )
    }

    @Test
    fun invalidTargetFailsBeforeNetworkWork() = runTest {
        var calls = 0
        val repository = TvCastRepository { _, _ ->
            calls += 1
            byteArrayOf()
        }
        val invalid = TvCastTarget("127.0.0.1")

        assertFalse(repository.playPause(invalid))
        assertFalse(repository.seek(invalid, forward = true))
        assertFalse(repository.stop(invalid))
        assertEquals(0, calls)
    }
}
