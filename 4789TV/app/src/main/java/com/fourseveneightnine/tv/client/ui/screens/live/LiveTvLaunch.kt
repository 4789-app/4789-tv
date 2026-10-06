package com.fourseveneightnine.tv.client.ui.screens.live

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** A search result asks the existing Live TV route to reveal one saved item. */
internal object LiveTvLaunch {
    data class Target(val kind: String, val id: String)
    private val pending = AtomicReference<Target?>(null)
    private val iptvPlayback = AtomicBoolean(false)
    fun stage(kind: String, id: String) { pending.set(Target(kind, id)) }
    fun peek(): Target? = pending.get()
    fun consume(target: Target) { pending.compareAndSet(target, null) }
    fun markPlayback() { iptvPlayback.set(true) }
    fun isPlayback(): Boolean = iptvPlayback.get()
    fun clearPlayback() { iptvPlayback.set(false) }
    fun consumePlayback(): Boolean = iptvPlayback.getAndSet(false)
}
