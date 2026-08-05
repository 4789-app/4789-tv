package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.protocol.rpcError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KodiRequestHandlerTest {
    @Test
    fun dispatchForwardsPayloadAndPreservesDispatcherResponse() = runTest {
        val payload = """{ "jsonrpc": "2.0", "method": "Player.GetActivePlayers" }"""
        val dispatcherResponse = "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":7}"
        var dispatchedPayload: String? = null
        val handler = KodiRequestHandler { request ->
            dispatchedPayload = request
            dispatcherResponse
        }

        assertEquals(dispatcherResponse, handler.dispatch(payload))
        assertEquals(payload, dispatchedPayload)
    }

    @Test
    fun dispatchHidesUnexpectedFailureDetails() = runTest {
        val handler = KodiRequestHandler {
            error("private dispatcher failure")
        }

        val response = handler.dispatch("{}")

        assertEquals(rpcError(null, -32_603, "Internal error"), response)
        assertFalse(response.contains("private dispatcher failure"))
    }
}
