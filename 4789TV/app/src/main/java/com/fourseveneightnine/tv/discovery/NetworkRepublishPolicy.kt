package com.fourseveneightnine.tv.discovery

/**
 * Whether a network change invalidates the current DNS-SD advertisement.
 *
 * The HTTP and WebSocket listeners bind `0.0.0.0`, so they survive an address change on their own.
 * The advertisement does not: Android registered it against the address the box held at the time.
 * After a router reboot or a DHCP lease change the record still resolves, to an address nothing
 * answers on — the box looks present in the phone's list and then fails to connect. Re-registering
 * is the only way to correct the published address.
 */
internal enum class NetworkRepublishAction { Republish, Ignore }

internal object NetworkRepublishPolicy {

    /**
     * Android emits several link-properties callbacks while a DHCP lease settles. Re-registering on
     * each one would tear down and rebuild the advertisement repeatedly, which is precisely the
     * window in which a phone is most likely to be looking. Coalesce the burst instead.
     */
    const val COALESCE_DELAY_MS = 1_500L

    fun decide(
        previousAddresses: Set<String>?,
        currentAddresses: Set<String>,
    ): NetworkRepublishAction {
        // No address means no reachable endpoint to publish. The advertisement is stopped on loss.
        if (currentAddresses.isEmpty()) return NetworkRepublishAction.Ignore
        // First observation — the advertisement was registered with these addresses already.
        if (previousAddresses == null) return NetworkRepublishAction.Ignore
        if (previousAddresses == currentAddresses) return NetworkRepublishAction.Ignore
        return NetworkRepublishAction.Republish
    }

    /**
     * Link-local and loopback addresses never carry a cast session, and a box can acquire or drop
     * one without its real address changing at all. Ignoring them keeps a cosmetic change from
     * restarting a healthy advertisement.
     */
    fun routableAddresses(addresses: Collection<String>): Set<String> =
        addresses.asSequence()
            .map { it.substringBefore('%').lowercase() }
            .filter(::isRoutable)
            .toSet()

    private fun isRoutable(address: String): Boolean = when {
        address.isEmpty() -> false
        address.startsWith("127.") || address == "::1" -> false
        address.startsWith("169.254.") -> false
        address.startsWith("fe80:") -> false
        else -> true
    }
}
