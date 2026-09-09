package com.fourseveneightnine.tv.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TVArtworkEnrichmentRepositoryTest {

    @Test
    fun `builds full urls for the chosen backdrop and logo`() {
        val art = TVArtworkEnrichmentRepository.parse(
            """
            {"backdrops":[{"file_path":"/wide.jpg","iso_639_1":null,"vote_average":5.0}],
             "logos":[{"file_path":"/logo.png","iso_639_1":"en","vote_average":4.0}]}
            """.trimIndent(),
        )
        assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", art.backdropURL)
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", art.logoURL)
    }

    /** A still with burnt-in titling is worse than a clean one, whatever TMDB's own order says. */
    @Test
    fun `prefers a text free backdrop over a localised one`() {
        val art = TVArtworkEnrichmentRepository.parse(
            """
            {"backdrops":[
              {"file_path":"/english.jpg","iso_639_1":"en","vote_average":9.0},
              {"file_path":"/clean.jpg","iso_639_1":null,"vote_average":1.0}
            ]}
            """.trimIndent(),
        )
        assertTrue(art.backdropURL!!.endsWith("/clean.jpg"))
    }

    /** Logos are the opposite case: the lettering is the point. */
    @Test
    fun `prefers an english logo`() {
        val art = TVArtworkEnrichmentRepository.parse(
            """
            {"logos":[
              {"file_path":"/none.png","iso_639_1":null,"vote_average":9.0},
              {"file_path":"/en.png","iso_639_1":"en","vote_average":1.0}
            ]}
            """.trimIndent(),
        )
        assertTrue(art.logoURL!!.endsWith("/en.png"))
    }

    @Test
    fun `breaks ties on votes within the same language`() {
        val art = TVArtworkEnrichmentRepository.parse(
            """
            {"backdrops":[
              {"file_path":"/low.jpg","iso_639_1":null,"vote_average":2.0},
              {"file_path":"/high.jpg","iso_639_1":null,"vote_average":8.0}
            ]}
            """.trimIndent(),
        )
        assertTrue(art.backdropURL!!.endsWith("/high.jpg"))
    }

    @Test
    fun `drops entries that would build a broken url`() {
        val art = TVArtworkEnrichmentRepository.parse(
            """{"backdrops":[{"file_path":"no-leading-slash.jpg"},{"iso_639_1":null}]}""",
        )
        assertNull(art.backdropURL)
    }

    @Test
    fun `malformed payloads degrade to no artwork`() {
        listOf("not json", "[]", "{}", """{"backdrops":"nope"}""").forEach { payload ->
            val art = TVArtworkEnrichmentRepository.parse(payload)
            assertNull(art.backdropURL)
            assertNull(art.logoURL)
        }
    }
}
