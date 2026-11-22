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
    private fun receiverInfo(id: String) =
        """{"jsonrpc":"2.0","id":8,"result":{"uuid":"$id","httpPort":8791,"x4789":true,"name":"Living Room"}}""".encodeToByteArray()

    @Test
    fun sameNameReceiverAtOldAddressNeverReceivesControlsOrMedia() = runTest {
        val writes = mutableListOf<Pair<String,String>>()
        val old = TvCastTarget("192.168.1.20", uuid = "A", name = "Living Room")
        val moved = old.copy(host = "192.168.1.21")
        val repo = TvCastRepository { target, bytes ->
            val req = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val method = req["method"]!!.jsonPrimitive.content
            if (method == "X4789.GetReceiverInfo") receiverInfo(if (target.host == moved.host) "A" else "B")
            else {
                writes += target.host to method
                """{"jsonrpc":"2.0","id":${req["id"]},"result":{"speed":1,"time":{"seconds":12}}}""".encodeToByteArray()
            }
        }
        assertFalse(repo.playPause(old))
        assertEquals(12000L, repo.playbackSample(moved)?.positionMillis)
        assertTrue(repo.playPause(moved))
        assertEquals(listOf(moved.host to "Player.GetProperties", moved.host to "Player.PlayPause"), writes)
    }

    @Test
    fun stagesMetadataBeforeOpeningEphemeralUrl() = runTest {
        val requests = mutableListOf<String>()
        val repository = TvCastRepository { _, bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val method = request["method"]!!.jsonPrimitive.content
            if (method == "X4789.GetReceiverInfo") return@TvCastRepository receiverInfo("A")
            requests += method
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
            val method = request["method"]!!.jsonPrimitive.content
            if (method == "X4789.GetReceiverInfo") return@TvCastRepository receiverInfo("A")
            requests += method
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
