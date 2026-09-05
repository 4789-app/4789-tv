package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.ReceiverAudioProfile

internal data class ReceiverAudioProfilePlan(
    val automatic: Boolean,
    val passthrough: Boolean,
    val codecRequests: Map<String, Boolean>,
)

internal object ReceiverAudioProfilePolicy {
    private val lossy = mapOf(
        "ac3" to true,
        "eac3" to true,
        "dts" to true,
        "dts-hd" to false,
        "truehd" to false,
    )
    private val lossless = lossy.mapValues { true }

    fun plan(profile: ReceiverAudioProfile): ReceiverAudioProfilePlan = when (profile) {
        ReceiverAudioProfile.AUTOMATIC -> ReceiverAudioProfilePlan(
            automatic = true,
            passthrough = false,
            codecRequests = emptyMap(),
        )
        ReceiverAudioProfile.STEREO,
        ReceiverAudioProfile.SURROUND_DECODE -> ReceiverAudioProfilePlan(
            automatic = false,
            passthrough = false,
            codecRequests = emptyMap(),
        )
        ReceiverAudioProfile.SURROUND_LOSSY -> ReceiverAudioProfilePlan(
            automatic = false,
            passthrough = true,
            codecRequests = lossy,
        )
        ReceiverAudioProfile.SURROUND_LOSSLESS -> ReceiverAudioProfilePlan(
            automatic = false,
            passthrough = true,
            codecRequests = lossless,
        )
    }
}
