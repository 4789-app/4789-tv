package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

/**
 * Stream add-ons answer IMDb ids (`tt…`). Catalog snapshots and some add-on catalogs hand out
 * other ids (`tmdb:…`, `mdblist…`). Before a stream search, map the title to its IMDb id through
 * the metadata we already fetched for Detail; fall back to the id we were given.
 */
internal object StreamIds {
    suspend fun forStreams(services: ClientGraph.DataServices, type: String, id: String): String {
        val meta = services.meta.meta(type, id)
        meta?.defaultVideoID?.let { return it }
        if (id.startsWith("tt")) return id
        // A provider-owned library ID can identify one torrent or file. IMDb is only its
        // display identity and must not replace the playback identity.
        if (services.registry.streamAddons().any { addon ->
            addon.manifest?.idPrefixes.orEmpty().any(id::startsWith)
        }) return id
        val imdb = meta?.imdbID
        if (imdb.isNullOrBlank()) {
            ReceiverDiagnostics.record("streams.id.unmapped", "id=$id")
            return id
        }
        return imdb
    }
}
