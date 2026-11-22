package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterboxdLinkFormTest {
    @Test fun stagesOneCanonicalPublicLinkAndRejectsUnsafeInputs() {
        val form = LetterboxdLinkForm { 10L }
        assertNull(form.open("8.8.8.8"))
        val session = requireNotNull(form.open("192.168.1.42"))
        val key = session.address.substringAfterLast('/')
        listOf(
            "http://letterboxd.com/owner/list/sample/",
            "https://letterboxd.com@192.168.1.1/owner/list/sample/",
            "https://letterboxd.com.example.org/owner/list/sample/",
            "https://letterboxd.com:444/owner/list/sample/",
            "https://letterboxd.com/owner/list/sample/page/2/",
        ).forEach { bad -> assertEquals(400, form.submit(key, post(bad)).statusCode) }
        assertNull(session.list.value)
        assertEquals(200, form.submit(key, post("https://www.letterboxd.com/Owner/list/Sample/?extra=1&x=2")).statusCode)
        assertEquals("https://letterboxd.com/owner/list/sample/", session.list.value)
        assertEquals(409, form.submit(key, post("https://letterboxd.com/owner/list/other/")).statusCode)
        assertEquals("https://letterboxd.com/owner/list/sample/", session.list.value)
    }

    @Test fun stagesOfficialShareLinkWithoutDereferencingIt() {
        val form = LetterboxdLinkForm { 10L }
        val session = requireNotNull(form.open("192.168.1.42"))
        val key = session.address.substringAfterLast('/')
        assertEquals(400, form.submit(key, post("https://boxd.it/abc/other")).statusCode)
        assertEquals(200, form.submit(key, post("https://boxd.it/1flhM?referrer=phone")).statusCode)
        assertEquals("https://boxd.it/1flhM", session.list.value)
    }

    @Test fun closedAndExpiredInvitationsCannotStageAnImport() {
        var clock = 1L
        val form = LetterboxdLinkForm { clock }
        val first = requireNotNull(form.open("192.168.1.42"))
        val second = requireNotNull(form.open("192.168.1.42"))
        val link = post("https://letterboxd.com/owner/list/sample/")
        assertEquals(410, form.submit(first.address.substringAfterLast('/'), link).statusCode)
        clock = second.expiresAt
        assertEquals(410, form.submit(second.address.substringAfterLast('/'), link).statusCode)
        assertNull(second.list.value)
        val third = requireNotNull(form.open("192.168.1.42"))
        form.close(third)
        assertEquals(410, form.submit(third.address.substringAfterLast('/'), link).statusCode)
    }

    @Test fun publicFilmMetadataSuppliesOfflineFiltersWithoutATmdbKey() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Movie","name":"Sample","description":"A local synopsis","dateCreated":"2024-03-14",
             "duration":"PT1H25M","genre":["Drama"],"inLanguage":["en"],
             "actor":[{"name":"A Person"}],"countryOfOrigin":[{"name":"Canada"}]}
            </script>
        """.trimIndent()
        val meta = requireNotNull(TVLetterboxdClient.parsePublicMetadata(html, "sample"))
        assertEquals("letterboxd-film:sample", meta.id)
        assertEquals(85, meta.runtimeMinutes)
        assertEquals(listOf("Drama"), meta.genres)
        assertEquals(listOf("A Person"), meta.castNames)
        assertEquals(listOf("Canada"), meta.countries)
        assertEquals("2024-03-14", meta.releaseDate)
    }

    @Test fun readsPublicListContextAndSeparatesPortraitsFromBackdrops() {
        val page = """
            <meta property="og:type" content="letterboxd:list">
            <meta property="og:description" content="A collection for research">
            <meta property="og:image" content="https://a.ltrbxd.com/resized/list-image.jpg?v=01">
            <meta property="og:image:width" content="1200">
            <meta property="og:image:height" content="675">
            <span class="published is-updated">Published <time datetime="2024-06-12T08:24:05Z">
            <span class="updated">Updated <time datetime="2026-11-19T02:23:53.949Z">
        """.trimIndent()
        val info = requireNotNull(TVLetterboxdClient.parseListInformation(page))
        assertEquals("A collection for research", info.description)
        assertEquals("https://a.ltrbxd.com/resized/list-image.jpg?v=01", info.backdropUrl)
        assertEquals("2024-06-12T08:24:05Z", info.publishedAt)
        assertEquals("2026-11-19T02:23:53.949Z", info.updatedAt)
        assertNull(TVLetterboxdClient.parseListInformation(page.replace("1200", "230"))?.backdropUrl)
        assertNull(TVLetterboxdClient.parseListInformation(page.replace("a.ltrbxd.com", "images.example"))?.backdropUrl)
    }

    private fun post(link: String) = "link=" + URLEncoder.encode(link, "UTF-8")
}
