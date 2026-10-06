package com.fourseveneightnine.tv.client

import android.content.Context
import com.fourseveneightnine.tv.startup.ReceiverApplication

/**
 * The application object for the 4789 TV client.
 *
 * It extends [ReceiverApplication] rather than replacing it, so the diagnostics log is still
 * installed before anything else runs — every trap in `4789TV/HANDOVER.md` was found by reading
 * that file off the box — and adds the one thing the client needs on top: [AppGraph].
 *
 * Nothing expensive is built here. The graph is entirely lazy, and a television process is created
 * far more often than it is used.
 */
class TvApplication : ReceiverApplication(), coil3.SingletonImageLoader.Factory {
    internal val graph: AppGraph by lazy { AppGraph(this) }
    internal val client: ClientGraph by lazy { ClientGraph(this, graph) }

    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = client.imageLoader
}

/** The data graph for this process. */
internal val Context.clientGraph: ClientGraph
    get() = (applicationContext as TvApplication).client

/** The graph for this process. Called from the Activity, never from a background component. */
internal val Context.appGraph: AppGraph
    get() = (applicationContext as TvApplication).graph
