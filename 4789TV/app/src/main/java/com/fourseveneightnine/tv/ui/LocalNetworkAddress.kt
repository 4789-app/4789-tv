package com.fourseveneightnine.tv.ui

import java.net.Inet4Address
import java.net.NetworkInterface

/** Small display-only helper for the manual connection address shown on the Ready screen. */
object LocalNetworkAddress {
    fun currentIPv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { network -> network.isUp && !network.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { address -> address.isLoopbackAddress || address.isLinkLocalAddress }
                    .map { address -> address.hostAddress.orEmpty() }
            }
        preferredIPv4(candidates)
    }.getOrNull()

    fun preferredIPv4(candidates: List<String>): String? =
        candidates.firstOrNull(::isPrivateIPv4) ?: candidates.firstOrNull()

    private fun isPrivateIPv4(address: String): Boolean {
        val octets = address.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
