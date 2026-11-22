package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.client.data.streams.debrid.Resolved
import okhttp3.OkHttpClient

@Suppress("UNUSED_PARAMETER")
internal class EinthusanProvider(http: OkHttpClient) {
    suspend fun find(title: String, year: Int?, imdbId: String?, originalLanguage: String?, account: EinthusanAccount): EinthusanSource? = null

    suspend fun verify(account: EinthusanAccount): Unit = unavailable()

    suspend fun resolve(source: EinthusanSource, account: EinthusanAccount): Resolved = unavailable()

    private fun unavailable(): Nothing = throw EinthusanFailure("This build does not include the Einthusan source.")

    companion object {
        const val available = false
    }
}
