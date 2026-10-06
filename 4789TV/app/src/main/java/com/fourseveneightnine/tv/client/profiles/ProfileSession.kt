package com.fourseveneightnine.tv.client.profiles

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface ProfileSessionState {
    data object Loading : ProfileSessionState
    data class Selecting(val selectedProfileId: UUID) : ProfileSessionState
    data class Unlocked(val profileId: UUID) : ProfileSessionState
}

/** Runtime-only unlock state. Recreating this object always returns to profile selection. */
internal class ProfileSession(private val store: ProfileStore) {
    private val mutableState = MutableStateFlow<ProfileSessionState>(ProfileSessionState.Loading)
    val state: StateFlow<ProfileSessionState> = mutableState.asStateFlow()

    fun load(): ProfileCatalog = store.load().also { catalog ->
        val selected = catalog.profiles.first { it.id == catalog.selectedProfileId }
        mutableState.value = if (catalog.profiles.size == 1 && !selected.hasPin) {
            // Existing installs become the pinless Owner profile. Keeping that one-person upgrade
            // invisible avoids introducing a selector before the viewer has created a profile.
            ProfileSessionState.Unlocked(selected.id)
        } else {
            ProfileSessionState.Selecting(selected.id)
        }
    }

    fun select(profileId: UUID): PinVerification {
        val profile = store.load().profiles.firstOrNull { it.id == profileId }
            ?: error("profile_not_found")
        store.select(profileId)
        return if (profile.hasPin) {
            mutableState.value = ProfileSessionState.Selecting(profileId)
            PinVerification.Required
        } else {
            mutableState.value = ProfileSessionState.Unlocked(profileId)
            PinVerification.NotRequired
        }
    }

    fun unlock(profileId: UUID, pin: CharArray): PinVerification {
        store.select(profileId)
        return store.verifyPin(profileId, pin).also { result ->
            mutableState.value = when (result) {
                PinVerification.Verified,
                PinVerification.NotRequired,
                -> ProfileSessionState.Unlocked(profileId)

                PinVerification.Required,
                PinVerification.PlaybackActive,
                is PinVerification.Locked,
                is PinVerification.Rejected,
                -> ProfileSessionState.Selecting(profileId)
            }
        }
    }

    fun lock() {
        mutableState.value = ProfileSessionState.Selecting(store.load().selectedProfileId)
    }
}
