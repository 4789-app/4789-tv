package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioAccountImportTest {
    @Test
    fun `device link waits for approval then returns a short-lived key without exposing it`() = runTest {
        var reads = 0
        val network = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/api/v2/create" -> FakeAnswer(body =
                    """{"result":{"code":"AB12","link":"https://link.stremio.com/AB12","qrcode":"unused"}}""")
                "/api/v2/read" -> FakeAnswer(body = if (++reads == 1) {
                    """{"error":{"code":101,"message":"Invalid or expired token"}}"""
                } else {
                    """{"result":{"authKey":"temporary-secret-key"}}"""
                })
                else -> error("unexpected request")
            }
        }
        val client = StremioAccountImport(network.client)
        val link = client.createLink()
        assertFalse(link.toString().contains("AB12"))
        assertNull(client.readAuthKey(link))
        assertEquals("temporary-secret-key", client.readAuthKey(link))
        assertTrue(network.urls.last().contains("code=AB12"))
    }

    @Test
    fun `collection keeps configured links skips local or duplicate addons and logs out`() = runTest {
        val network = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/api/addonCollectionGet" -> FakeAnswer(body = """
                    {"result":{"addons":[
                      {"transportUrl":"https://one.example/SECRET/manifest.json","manifest":{"name":"One"}},
                      {"transportUrl":"https://one.example/SECRET/manifest.json","manifest":{"name":"Duplicate"}},
                      {"transportUrl":"http://127.0.0.1:11470/local-addon/manifest.json","manifest":{"name":"Local"}},
                      {"transportUrl":"https://two.example/config?token=OTHER","manifest":{"name":"Two"}}
                    ]}}
                """.trimIndent())
                "/api/logout" -> FakeAnswer(body = """{"result":{"success":true}}""")
                else -> error("unexpected request")
            }
        }
        val result = StremioAccountImport(network.client).collectAndLogout("temporary-secret-key")
        assertEquals(listOf("One", "Two"), result.addons.map { it.name })
        assertEquals(2, result.skipped)
        assertEquals("https://one.example/SECRET/manifest.json", result.addons.first().url)
        assertFalse(result.toString().contains("SECRET"))
        assertEquals(listOf("/api/addonCollectionGet", "/api/logout"),
            network.requests.map { it.url.encodedPath })
    }

    @Test
    fun `logout is attempted even when collection is invalid`() = runTest {
        val network = FakeHttp { request ->
            FakeAnswer(body = if (request.url.encodedPath.endsWith("logout"))
                """{"result":{"success":true}}""" else """{"result":{}}""")
        }
        val result = runCatching {
            StremioAccountImport(network.client).collectAndLogout("temporary-secret-key")
        }
        assertTrue(result.isFailure)
        assertEquals("/api/logout", network.requests.last().url.encodedPath)
    }

    @Test
    fun `portable descriptor array imports without account credentials`() {
        val imported = StremioAccountImport(FakeHttp { error("network not expected") }.client)
            .parseCollection("""
                [{"transportUrl":"https://lists.example/user/manifest.json","manifest":{"name":"Lists"}},
                 {"transportUrl":"https://v3-cinemeta.strem.io/manifest.json","manifest":{"name":"Cinemeta"}}]
            """.trimIndent())
        assertEquals(listOf("Lists"), imported.addons.map { it.name })
        assertEquals(1, imported.skipped)
    }
}
