package com.fourseveneightnine.tv.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TVRatingsRepositoryTest {

    @Test
    fun `parses the documented ratings array`() {
        val ratings = TVRatingsRepository.parse(
            """
            {"title":"Dune","ratings":[
              {"source":"imdb","value":8.0,"score":80},
              {"source":"tmdb","value":7.8,"score":78},
              {"source":"tomatoes","value":83,"score":83}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("IMDb", "TMDB", "Tomatometer"), ratings.map { it.label })
        assertEquals("8.0", ratings[0].display)
        assertEquals("83%", ratings[2].display)
    }

    @Test
    fun `falls back to score when value is missing`() {
        val ratings = TVRatingsRepository.parse("""{"ratings":[{"source":"imdb","score":7.4}]}""")
        assertEquals("7.4", ratings.single().display)
    }

    /** A provider with nothing to say reports zero rather than omitting itself. */
    @Test
    fun `drops zero and negative scores`() {
        val ratings = TVRatingsRepository.parse(
            """{"ratings":[{"source":"imdb","value":0},{"source":"tmdb","value":-1},{"source":"trakt","value":9.1}]}""",
        )
        assertEquals(listOf("Trakt"), ratings.map { it.label })
    }

    @Test
    fun `treats trakt above ten as a percentage`() {
        val ratings = TVRatingsRepository.parse("""{"ratings":[{"source":"trakt","value":86}]}""")
        assertEquals("86%", ratings.single().display)
    }

    @Test
    fun `ignores providers it cannot label rather than showing a raw key`() {
        val ratings = TVRatingsRepository.parse(
            """{"ratings":[{"source":"somenewthing","value":9},{"source":"imdb","value":6.2}]}""",
        )
        assertEquals(listOf("IMDb"), ratings.map { it.label })
    }

    @Test
    fun `malformed payloads degrade to empty rather than throwing`() {
        assertTrue(TVRatingsRepository.parse("not json").isEmpty())
        assertTrue(TVRatingsRepository.parse("[]").isEmpty())
        assertTrue(TVRatingsRepository.parse("{}").isEmpty())
        assertTrue(TVRatingsRepository.parse("""{"ratings":"nope"}""").isEmpty())
        assertTrue(TVRatingsRepository.parse("""{"ratings":[{"no_source":1}]}""").isEmpty())
    }

    @Test
    fun `caps how many ratings reach the row`() {
        val many = (1..20).joinToString(",") { """{"source":"imdb","value":$it}""" }
        assertTrue(TVRatingsRepository.parse("""{"ratings":[$many]}""").size <= TVRatingsRepository.MAX_RATINGS)
    }

    @Test
    fun `only accepts well formed imdb ids`() {
        assertTrue(TVRatingsRepository.IMDB_ID.matches("tt0111161"))
        assertTrue(!TVRatingsRepository.IMDB_ID.matches("tt12"))
        assertTrue(!TVRatingsRepository.IMDB_ID.matches("nm0000123"))
        assertTrue(!TVRatingsRepository.IMDB_ID.matches("tt0111161?apikey=leak"))
    }
}
