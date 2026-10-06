package com.fourseveneightnine.tv.client.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IptvGuideKindTest {
    private val channel = IptvChannel("live:1", "source", "General TV", "Entertainment", streamRef = "1")
    private fun program(title: String = "Evening Show", categories: List<String> = emptyList()) =
        IptvProgram(channel.id, title, 1L, 2L, categories = categories)

    @Test fun indexedScheduleHandlesUnsortedRowsBoundariesAndGaps() {
        val early = IptvProgram("a", "Early", 10, 20)
        val late = IptvProgram("a", "Late", 30, 40)
        val other = IptvProgram("b", "Other", 0, 100)
        val catalog = IptvCatalog(programs = listOf(late, other, early))
        assertEquals(early, catalog.now("a", 10))
        assertEquals(null, catalog.now("a", 20))
        assertEquals(late, catalog.next("a", 20))
        assertEquals(late, catalog.now("a", 30))
        assertEquals(null, catalog.next("a", 30))
        assertEquals(null, catalog.now("missing", 15))
        assertEquals(other, catalog.now("b", 30))
        assertSame(catalog.programsByChannel, catalog.programsByChannel)
    }

    @Test fun explicitProviderCategoryIdentifiesMovie() {
        assertEquals(IptvGuideKind.Movie, program(categories = listOf("Movie / Drama")).guideKind(channel))
        assertEquals(IptvGuideKind.Movie, program("Movie: A Story").guideKind(channel))
    }

    @Test fun channelNameIsHonestAboutUnclassifiedProgram() {
        val movieChannel = channel.copy(name = "TELUGU-MOVIES 2 HD")
        assertEquals(IptvGuideKind.MovieChannel, program().guideKind(movieChannel))
        assertEquals(IptvGuideKind.Program, program().guideKind(channel))
    }

    @Test fun sportsAndNewsUseProviderLabels() {
        assertEquals(IptvGuideKind.Sports, program(categories = listOf("Cricket")).guideKind(channel))
        assertEquals(IptvGuideKind.News, program(categories = listOf("News")).guideKind(channel))
    }
}
