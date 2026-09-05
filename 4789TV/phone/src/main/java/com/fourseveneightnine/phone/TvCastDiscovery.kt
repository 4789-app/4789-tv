package com.fourseveneightnine.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

internal object TvCastDiscoveryPolicy {
    const val SERVICE_TYPE = "_xbmc-jsonrpc-h._tcp"

    fun target(
        serviceName: String,
        serviceType: String,
        port: Int,
        receiverMarker: String?,
        addresses: List<String>,
    ): TvCastTarget? {
        if (!serviceName.startsWith("4789 TV", ignoreCase = true)) return null
        if (serviceType.trimEnd('.') != SERVICE_TYPE) return null
        if (port != TvCastTarget.PORT || receiverMarker != "1") return null
        return addresses.asSequence().mapNotNull(TvCastTargetPolicy::parse).firstOrNull()
    }
}

/** One-screen, lifecycle-owned first-party receiver discovery. */
internal class TvCastDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0
    private var resolving = false
    private var timeout: Runnable? = null

    fun start(onTarget: (TvCastTarget) -> Unit, onStatus: (String) -> Unit) {
        if (listener != null) return
        generation += 1
        val currentGeneration = generation
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = post(currentGeneration) {
                onStatus("Looking for 4789 TV on this Wi-Fi…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) = post(currentGeneration) {
                if (!resolving && serviceInfo.serviceName.startsWith("4789 TV", ignoreCase = true)) {
                    resolve(serviceInfo, currentGeneration, onTarget, onStatus)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                post(currentGeneration) {
                    stop()
                    onStatus("Automatic TV discovery is unavailable. Enter its private IP address.")
                }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        timeout = Runnable {
            if (generation == currentGeneration && listener != null) {
                stop()
                onStatus("No 4789 TV was found. Enter its private IP address if it is open.")
            }
        }.also { main.postDelayed(it, DISCOVERY_TIMEOUT_MILLIS) }
        runCatching {
            manager.discoverServices(
                TvCastDiscoveryPolicy.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure {
            stop()
            onStatus("Automatic TV discovery is unavailable. Enter its private IP address.")
        }
    }

    fun stop() {
        generation += 1
        resolving = false
        timeout?.let(main::removeCallbacks)
        timeout = null
        val active = listener ?: return
        listener = null
        runCatching { manager.stopServiceDiscovery(active) }
    }

    private fun resolve(
        serviceInfo: NsdServiceInfo,
        expectedGeneration: Int,
        onTarget: (TvCastTarget) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        resolving = true
        @Suppress("DEPRECATION")
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                post(expectedGeneration) {
                    resolving = false
                }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = post(expectedGeneration) {
                resolving = false
                val target = TvCastDiscoveryPolicy.target(
                    serviceName = serviceInfo.serviceName,
                    serviceType = serviceInfo.serviceType,
                    port = serviceInfo.port,
                    receiverMarker = serviceInfo.attributes["x4789"]?.decodeToString(),
                    addresses = serviceInfo.addressStrings(),
                ) ?: return@post
                stop()
                onTarget(target)
                onStatus("Found 4789 TV at ${target.host}.")
            }
        })
    }

    private fun post(expectedGeneration: Int, operation: () -> Unit) {
        main.post {
            if (generation == expectedGeneration && listener != null) operation()
        }
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.addressStrings(): List<String> =
        if (Build.VERSION.SDK_INT >= 34) hostAddresses.mapNotNull { it.hostAddress }
        else listOfNotNull(host?.hostAddress)

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 10_000L
    }
}
