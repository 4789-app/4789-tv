package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableStreamRecipeTest {
    private val chosen = StreamEntry(
        url = "https://cdn.example/a/movie.mkv?token=expires-in-one-hour",
        name = "AIO  4K",
        title = "The Continental",
        quality = "2160p",
        filename = "The.Continental.2160p.WEB-DL.mkv",
        sizeBytes = 13_000_000_000,
    )

    @Test
    fun recipeCarriesNoLinkAndNoQueryString() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val written = listOfNotNull(
            recipe.titleID,
            recipe.mediaType,
            recipe.selectorHash,
            recipe.label,
            recipe.filename,
            recipe.sizeBytes?.toString(),
        ).joinToString(" ")

        assertFalse(written.contains("cdn.example"))
        assertFalse(written.contains("token"))
        assertFalse(written.contains("://"))
        assertTrue(recipe.selectorHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun aDescriptiveFieldThatHidesALinkIsNotWrittenDown() {
        val sneaky = chosen.copy(
            name = "https://cdn.example/signed?token=secret",
            title = null,
            filename = "https://cdn.example/also-a-link.mkv",
        )

        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt1", "movie", sneaky))

        assertEquals("Remote source", recipe.label)
        assertNull(recipe.filename)
    }

    @Test
    fun identityIgnoresPresentationButNotTheFileItself() {
        val restyled = chosen.copy(
            url = "https://other-cdn.example/b/movie.mkv?token=a-brand-new-signature",
            name = "aio 4k",
        )
        val differentFile = chosen.copy(sizeBytes = 9_000_000_000)

        assertEquals(
            DurableStreamRecipePolicy.selectorHash(chosen),
            DurableStreamRecipePolicy.selectorHash(restyled),
        )
        assertNotEquals(
            DurableStreamRecipePolicy.selectorHash(chosen),
            DurableStreamRecipePolicy.selectorHash(differentFile),
        )
    }

    @Test
    fun matchFindsTheSameFileInAReorderedListWithRotatedLinks() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val later = listOf(
            StreamEntry(url = "https://cdn.example/x.mkv", name = "1080p", filename = "x.mkv", sizeBytes = 4),
            chosen.copy(url = "https://cdn.example/a/movie.mkv?token=fresh-signature"),
            StreamEntry(url = "https://cdn.example/y.mkv", name = "720p", filename = "y.mkv", sizeBytes = 2),
        )

        val matched = DurableStreamRecipePolicy.match(recipe, later)

        assertEquals("https://cdn.example/a/movie.mkv?token=fresh-signature", matched?.url)
    }

    @Test
    fun matchFallsBackToFilenameAndSizeWhenTheDisplayTextChanged() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val rewritten = chosen.copy(
            url = "https://cdn.example/a/movie.mkv?token=fresh",
            name = "🎥 WEB-DL 🎞️ HEVC · new addon layout",
            title = "Continental, The",
            quality = null,
        )

        val matched = DurableStreamRecipePolicy.match(recipe, listOf(rewritten))

        assertEquals("https://cdn.example/a/movie.mkv?token=fresh", matched?.url)
    }

    @Test
    fun aVanishedOrAmbiguousSourceNeverSubstitutesAnotherFile() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val gone = listOf(
            StreamEntry(url = "https://cdn.example/z.mkv", name = "4K", filename = "z.mkv", sizeBytes = 5),
        )
        val ambiguous = listOf(
            chosen.copy(url = "https://cdn.example/one.mkv", name = "mirror one", title = null, quality = null),
            chosen.copy(url = "https://cdn.example/two.mkv", name = "mirror two", title = null, quality = null),
        )

        assertNull(DurableStreamRecipePolicy.match(recipe, gone))
        assertNull(DurableStreamRecipePolicy.match(recipe, emptyList()))
        assertNull(DurableStreamRecipePolicy.match(recipe, ambiguous))
    }

    @Test
    fun recipeRejectsAMediaTypeOrTitleTheStreamRequestCouldNotUse() {
        assertNull(DurableStreamRecipePolicy.recipe("tt1", "podcast", chosen))
        assertNull(DurableStreamRecipePolicy.recipe("../escape", "movie", chosen))
        assertEquals("series", DurableStreamRecipePolicy.recipe("tt1:1:2", "tv", chosen)?.mediaType)
    }

    @Test
    fun theQueueRecordSurvivesAWriteAndRejectsACorruptOne() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val job = OfflineDownloadJob(
            recipe = recipe,
            state = OfflineDownloadState.Queued,
            bytesDone = 4_096,
            declaredBytes = 8_192,
            attempts = 2,
            fingerprint = "\"weak|etag\"",
            detail = "The download stopped part way.",
        )

        val encoded = OfflineDownloadRecordFormat.encode(job)

        assertEquals(job, OfflineDownloadRecordFormat.decode(encoded))
        assertFalse(encoded.contains("cdn.example"))
        assertNull(OfflineDownloadRecordFormat.decode(""))
        assertNull(OfflineDownloadRecordFormat.decode("$encoded|extra"))
        assertNull(OfflineDownloadRecordFormat.decode(encoded.replaceFirst("1|", "2|")))
    }

    @Test
    fun progressAndStatusReadTrueAtEveryStage() {
        val recipe = requireNotNull(DurableStreamRecipePolicy.recipe("tt26657236", "movie", chosen))
        val half = OfflineDownloadJob(recipe, OfflineDownloadState.Running, 50, 200)
        val unknown = OfflineDownloadJob(recipe, OfflineDownloadState.Running, 50, 0)

        assertEquals(25, half.progressPercent)
        assertNull(unknown.progressPercent)
        assertEquals("Downloading 25%", OfflineDownloadQueuePolicy.statusLine(half))
        assertEquals(
            "Waiting to continue at 25%",
            OfflineDownloadQueuePolicy.statusLine(half.copy(state = OfflineDownloadState.Queued)),
        )
        assertEquals(
            "Saved for offline",
            OfflineDownloadQueuePolicy.statusLine(half.copy(state = OfflineDownloadState.Completed)),
        )
    }

    @Test
    fun retriesAreBoundedAndTheWorkNameHidesTheTitle() {
        assertTrue(OfflineDownloadQueuePolicy.shouldRetry(1))
        assertTrue(OfflineDownloadQueuePolicy.shouldRetry(OfflineDownloadQueuePolicy.MAXIMUM_ATTEMPTS - 1))
        assertFalse(OfflineDownloadQueuePolicy.shouldRetry(OfflineDownloadQueuePolicy.MAXIMUM_ATTEMPTS))
        assertFalse(OfflineDownloadQueuePolicy.workName("tt26657236").contains("tt26657236"))
        assertEquals(
            OfflineDownloadQueuePolicy.workName("tt26657236"),
            OfflineDownloadQueuePolicy.workName("tt26657236"),
        )
    }
}
