package com.fourseveneightnine.tv.client.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvBackupTest {
    @Test fun `episode order supports legacy cached titles and explicit provider numbers`() {
        fun episode(id: String, title: String, season: Int? = null, number: Int? = null, date: String? = null) =
            IptvVod(id, "source", "episode", title, "show", seasonNumber = season, episodeNumber = number, released = date)
        val one = episode("one", "S1 E9 · Old", date = "2020-01-01")
        val two = episode("two", "S1 E100 · New", date = "2021-01-01")
        val three = episode("three", "Special", 2, 1)
        val playlist = listOf(episode("m1", "Show S01E9"), episode("m2", "Show 1x100"), episode("m3", "Show S01E1000"))
        assertEquals(listOf("m3", "m2", "m1"), orderedIptvEpisodes(playlist, "Latest episode").map { it.id })
        assertEquals(listOf(three, two, one), orderedIptvEpisodes(listOf(one, two, three), "Latest episode"))
        assertEquals(listOf(two, one, three), orderedIptvEpisodes(listOf(one, three, two), "Latest air date"))
        assertEquals(listOf(one, two, three), orderedIptvEpisodes(listOf(three, two, one), "Oldest episode"))
    }

    @Test fun `backup retains accounts and preferences without video file references`() {
        val source = IptvSource("xtream:one", "Living room", IptvSourceKind.XTREAM,
            host = "https://provider.example", username = "viewer", password = "secret")
        val accounts = IptvAccounts(sources = listOf(source), favoriteIds = setOf("xtream:one:live:4"),
            favoriteLists = mapOf("Cricket" to setOf("xtream:one:live:4")),
            groupOrder = listOf("TELUGU | TV"), recordings = listOf(
                IptvRecording("id", "channel", "Film", 1, 2, IptvRecordingStatus.DONE, "file.ts")))
        val encrypted = IptvBackup.export(accounts, "long passphrase".toCharArray())
        assertFalse(encrypted.decodeToString().contains("secret"))
        val restored = IptvBackup.import(encrypted, "long passphrase".toCharArray())
        assertEquals(listOf(source), restored.sources)
        assertEquals(accounts.favoriteIds, restored.favoriteIds)
        assertEquals(accounts.favoriteLists, restored.favoriteLists)
        assertEquals(accounts.groupOrder, restored.groupOrder)
        assertTrue(restored.recordings.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `wrong passphrase cannot decrypt provider login`() {
        val encrypted = IptvBackup.export(IptvAccounts(), "long passphrase".toCharArray())
        IptvBackup.import(encrypted, "wrong passphrase".toCharArray())
    }
}
