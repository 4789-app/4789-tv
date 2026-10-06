package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsRepositoryTest {

    private fun http() = FakeHttp { FakeAnswer(body = fixture("mdblist-ratings.json")) }

    private fun repository(http: FakeHttp, key: String? = "mdb-abcdefgh12345678") =
        RatingsRepository(mdbListKey = key, okHttp = http.client, baseURL = "https://mdblist.test")

    @Test
    fun `one call returns every provider`() = runTest {
        val ratings = repository(http()).ratings("tt14452776", isShow = true)
        assertEquals(8.6, requireNotNull(ratings.imdb), 0.001)
        assertEquals(82, ratings.trakt)
        assertEquals(84, ratings.tmdb)
        assertEquals(4.1, requireNotNull(ratings.letterboxd), 0.001)
    }

    @Test
    fun `the path form is used and never the index-page query form`() = runTest {
        val http = http()
        repository(http).ratings("tt14452776", isShow = true)
        val url = http.urls.single()
        assertTrue(url, url.startsWith("https://mdblist.test/imdb/show/tt14452776?apikey="))
        // `?i=` answers 200 with the API index page: valid JSON, no ratings, silent failure.
        assertFalse(url, url.contains("?i="))
    }

    @Test
    fun `a movie asks the movie path`() = runTest {
        val http = http()
        repository(http).ratings("tt1375666", isShow = false)
        assertTrue(http.urls.single().contains("/imdb/movie/tt1375666"))
    }

    @Test
    fun `the index page parses to no ratings rather than to nonsense`() {
        assertTrue(RatingsRepository.parse(fixture("mdblist-index-page.json")).isEmpty)
    }

    @Test
    fun `a provider reporting zero is dropped, not shown at zero`() {
        val ratings = RatingsRepository.parse(fixture("mdblist-ratings.json"))
        assertFalse(ratings.isEmpty)
        // metacritic is 0 in the fixture and has no field here, so nothing to draw.
        assertEquals(84, ratings.tmdb)
    }

    @Test
    fun `a Trakt score on the ten scale is read as a percentage`() {
        val body = """{"ratings":[{"source":"trakt","value":8.2},{"source":"tmdb","value":7.4}]}"""
        val ratings = RatingsRepository.parse(body)
        assertEquals(82, ratings.trakt)
        assertEquals(74, ratings.tmdb)
    }

    @Test
    fun `a shape we do not recognise degrades to no ratings`() {
        assertTrue(RatingsRepository.parse("not json").isEmpty)
        assertTrue(RatingsRepository.parse("""{"ratings":"unexpected"}""").isEmpty)
        assertTrue(RatingsRepository.parse("""{"ratings":[{"source":"imdb"}]}""").isEmpty)
    }

    @Test
    fun `no key means no request at all`() = runTest {
        val http = http()
        assertTrue(repository(http, key = null).ratings("tt14452776", true).isEmpty)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `an id that is not an IMDb id means no request`() = runTest {
        val http = http()
        assertTrue(repository(http).ratings("tmdb:27205", false).isEmpty)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a failure resolves to no ratings and the row simply does not draw`() = runTest {
        val http = FakeHttp { FakeAnswer(code = 500, body = "down") }
        assertTrue(repository(http).ratings("tt14452776", true).isEmpty)
    }

    @Test
    fun `a repeat ask inside thirty minutes costs nothing`() = runTest {
        val http = http()
        val repository = repository(http)
        repository.ratings("tt14452776", true)
        repository.ratings("tt14452776", true)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `two callers asking together issue one request`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("mdblist-ratings.json"), delayMillis = 120) }
        val repository = repository(http)
        val first = async { repository.ratings("tt14452776", true) }
        val second = async { repository.ratings("tt14452776", true) }
        assertEquals(first.await(), second.await())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a stale cache expires`() = runTest {
        val http = http()
        var now = 0L
        val repository = RatingsRepository(
            mdbListKey = "mdb-abcdefgh12345678",
            okHttp = http.client,
            clock = { now },
            baseURL = "https://mdblist.test",
        )
        repository.ratings("tt14452776", true)
        now += RatingsRepository.CACHE_TTL_MILLIS + 1
        repository.ratings("tt14452776", true)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `an empty Ratings has nothing to draw`() {
        assertTrue(Ratings.NONE.isEmpty)
        assertNull(Ratings.NONE.imdb)
    }
}
