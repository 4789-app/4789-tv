package com.fourseveneightnine.tv.transport

/**
 * Ports reserved for the first-party receiver. 8080 is claimed by Amazon system services on
 * Fire OS, which pushes Kodi's web server onto 8090 — so both 8080 and 8090 (and Kodi's 9090
 * notification port) are off limits. 8791/9791 are unassigned and echo the app name.
 */
object ReceiverPorts {
    const val HTTP = 8_791
    const val WEB_SOCKET = 9_791
}
