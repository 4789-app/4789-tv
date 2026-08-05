package com.fourseveneightnine.tv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.io.Closeable

/**
 * Watches the default network and reports when the box's own address changed.
 *
 * A television box is not mobile, so this fires rarely — but the times it fires are exactly the
 * times casting breaks: a router reboot, a DHCP lease change, a move between wireless bands. See
 * [NetworkRepublishPolicy] for why the advertisement cannot survive those on its own.
 *
 * Callbacks are delivered on the main thread so the caller can drive the Activity-owned advertiser
 * directly, and are coalesced because Android emits a burst while a lease settles.
 */
internal class ReceiverNetworkMonitor(
    context: Context,
    private val onAddressChanged: () -> Unit,
) : Closeable {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastAddresses: Set<String>? = null
    private var registered = false

    private val pending = Runnable {
        ReceiverDiagnostics.record("network.republish", "addresses=${lastAddresses?.size ?: 0}")
        onAddressChanged()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handler.post { observe(addressesOf(linkProperties)) }
        }

        override fun onLost(network: Network) {
            // Keep the last set rather than clearing it: a brief drop followed by the SAME address
            // needs no republish, and treating loss as a change would restart a healthy record.
            handler.post { ReceiverDiagnostics.record("network.lost", "") }
        }
    }

    fun start() {
        if (registered) return
        val manager = connectivityManager ?: return
        registered = runCatching {
            manager.registerDefaultNetworkCallback(callback)
            true
        }.getOrElse {
            ReceiverDiagnostics.record("network.monitor.failed", it::class.java.simpleName)
            false
        }
    }

    override fun close() {
        handler.removeCallbacks(pending)
        if (!registered) return
        registered = false
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun observe(addresses: Set<String>) {
        val action = NetworkRepublishPolicy.decide(
            previousAddresses = lastAddresses,
            currentAddresses = addresses,
        )
        // Record the new set either way, so the NEXT change is measured against what is current.
        if (addresses.isNotEmpty()) lastAddresses = addresses
        if (action != NetworkRepublishAction.Republish) return
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, NetworkRepublishPolicy.COALESCE_DELAY_MS)
    }

    private fun addressesOf(linkProperties: LinkProperties): Set<String> =
        NetworkRepublishPolicy.routableAddresses(
            linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress },
        )
}
