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
        uuid: String?,
    ): TvCastTarget? {
        if (uuid.isNullOrBlank()) return null
        if (serviceType.trimEnd('.') != SERVICE_TYPE) return null
        if (port != TvCastTarget.PORT || receiverMarker != "1") return null
        return addresses.asSequence().mapNotNull(TvCastTargetPolicy::parse).firstOrNull()?.copy(uuid = uuid, name = serviceName)
    }
}

/** One-screen, lifecycle-owned first-party receiver discovery. */
internal class TvCastDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0
    private var resolving = false
    private val pending = LinkedHashMap<String, NsdServiceInfo>()
    private val present = mutableMapOf<String, NsdServiceInfo>()
    private val targets = mutableMapOf<String, TvCastTarget>()

    fun start(onTargets: (List<TvCastTarget>) -> Unit, onStatus: (String) -> Unit) {
        if (listener != null) return
        val current = ++generation
        fun publish() = onTargets(targets.values.distinctBy { it.uuid }.sortedBy { it.name })
        fun resolveNext() {
            if (resolving || pending.isEmpty()) return
            val key = pending.keys.first()
            val info = pending.remove(key) ?: return
            resolving = true
            fun finish(resolved: NsdServiceInfo?) = post(current) {
                resolving = false
                if (present[key] === info && resolved != null) {
                    val target = TvCastDiscoveryPolicy.target(
                        resolved.serviceName, resolved.serviceType, resolved.port,
                        resolved.attributes["x4789"]?.decodeToString(), resolved.addressStrings(),
                        resolved.attributes["uuid"]?.decodeToString(),
                    )
                    if (target != null) targets[key] = target else targets.remove(key)
                    publish()
                }
                resolveNext()
            }
            @Suppress("DEPRECATION")
            try {
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
                })
            } catch (_: RuntimeException) { finish(null) }
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = post(current) {
                present[serviceInfo.serviceName] = serviceInfo
                pending[serviceInfo.serviceName] = serviceInfo
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = post(current) {
                present.remove(serviceInfo.serviceName)
                pending.remove(serviceInfo.serviceName)
                targets.remove(serviceInfo.serviceName)
                publish()
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = post(current) {
                stop()
                onTargets(emptyList())
                onStatus("TV discovery is unavailable. Check the TV address in Source configuration.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        runCatching {
            manager.discoverServices(TvCastDiscoveryPolicy.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            stop()
            onStatus("TV discovery is unavailable. Check the TV address in Source configuration.")
        }
    }

    fun stop() {
        generation++
        resolving = false
        pending.clear()
        present.clear()
        targets.clear()
        val active = listener ?: return
        listener = null
        runCatching { manager.stopServiceDiscovery(active) }
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

}
