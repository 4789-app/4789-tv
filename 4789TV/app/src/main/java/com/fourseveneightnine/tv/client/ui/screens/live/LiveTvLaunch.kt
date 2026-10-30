package com.fourseveneightnine.tv.client.ui.screens.live

import com.fourseveneightnine.tv.client.iptv.IptvChannel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** A search result asks the existing Live TV route to reveal one saved item. */
internal object LiveTvLaunch {
    data class Target(val kind: String, val id: String)
    private val pending = AtomicReference<Target?>(null)
    private val iptvPlayback = AtomicBoolean(false)
    private val previewRequested = AtomicBoolean(false)
    fun stage(kind: String, id: String) { pending.set(Target(kind, id)) }
    fun peek(): Target? = pending.get()
    fun consume(target: Target) { pending.compareAndSet(target, null) }
    fun markPlayback(preview: Boolean = false) { previewRequested.set(preview); iptvPlayback.set(true) }
    fun consumePreview(): Boolean = previewRequested.getAndSet(false)
    fun isPlayback(): Boolean = iptvPlayback.get()
    fun clearPlayback() { iptvPlayback.set(false); previewRequested.set(false) }
    fun consumePlayback(): Boolean = iptvPlayback.getAndSet(false)
}


/** Use the selected category's order, even when its channels span multiple providers. */
internal object LiveChannelOrder {
    fun category(channels: List<IptvChannel>,
                 preferredIds: List<String>, group: String?): List<IptvChannel> {
        val eligible = channels.filter { group == null || it.group == group }
        if (preferredIds.isEmpty()) return eligible
        val byId = eligible.associateBy { it.id }
        return preferredIds.distinct().mapNotNull(byId::get)
    }

    fun adjacent(channels: List<IptvChannel>,
                 activeId: String, direction: Int): IptvChannel? {
        if (channels.size < 2) return null
        val index = channels.indexOfFirst { it.id == activeId }
        if (index < 0) return null
        return channels[Math.floorMod(index + direction, channels.size)]
    }
}
