package com.fourseveneightnine.tv.transport

internal data class SettingsEndpointResponse(
    val statusCode: Int,
    val body: String,
)

/** Optional first-party routes; Kodi JSON-RPC remains byte-for-byte independent of this surface. */
internal interface SettingsPairingEndpoint {
    suspend fun stagePair(pairingID: String, body: String): SettingsEndpointResponse
    suspend fun stagePairCatalog(pairingID: String, body: String): SettingsEndpointResponse
    suspend fun pairStatus(pairingID: String): SettingsEndpointResponse
    suspend fun applySync(receiverID: String, body: String): SettingsEndpointResponse
}
