package com.fourseveneightnine.tv.client.profiles

import java.util.UUID

internal data class HouseholdProfile(
    val id: UUID,
    val name: String,
    val avatarKey: String,
    val hasPin: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ProfileCatalog(
    val profiles: List<HouseholdProfile>,
    val selectedProfileId: UUID,
)

internal sealed interface PinVerification {
    data object Required : PinVerification
    data object Verified : PinVerification
    data object NotRequired : PinVerification
    /** A different household profile cannot take ownership while local playback is still active. */
    data object PlaybackActive : PinVerification
    data class Rejected(val attemptsRemaining: Int) : PinVerification
    data class Locked(val untilMillis: Long) : PinVerification
}

internal object OwnerProfile {
    val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    const val name: String = "Owner"
    const val avatarKey: String = "owner"
}

/** The ownership rule is independent of player implementation details and stays unit-testable. */
internal object ProfileSwitchPolicy {
    fun allowed(selectedProfileId: UUID?, targetProfileId: UUID, localPlaybackActive: Boolean): Boolean =
        !localPlaybackActive || selectedProfileId == targetProfileId
}
