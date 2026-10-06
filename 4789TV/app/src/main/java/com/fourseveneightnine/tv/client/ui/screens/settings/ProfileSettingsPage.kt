package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.profiles.HouseholdProfile
import com.fourseveneightnine.tv.client.profiles.OwnerProfile
import com.fourseveneightnine.tv.client.profiles.PinVerification
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings management for the same profile store used by the launch gate. */
@Composable
internal fun ProfilesPage(actions: PaneActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember(context) { context.applicationContext.clientGraph }
    val catalog by client.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val profiles = catalog?.profiles.orEmpty()
    val activeId = client.activeProfileId()

    fun report(block: () -> String) {
        scope.launch {
            val message = withContext(Dispatchers.Default) {
                runCatching(block).getOrElse { "That profile change could not be saved." }
            }
            actions.nav.toast(message)
        }
    }

    fun setPin(profile: HouseholdProfile) {
        actions.onProfilePin(
            ProfilePinRequest("PIN for ${profile.name}") { pin ->
                val copy = pin.copyOf()
                report {
                    try {
                        client.setProfilePin(profile.id, copy)
                        "PIN updated for ${profile.name}."
                    } finally {
                        copy.fill('\u0000')
                    }
                }
            },
        )
    }

    fun rename(profile: HouseholdProfile) {
        actions.onKeyboard(
            KeyboardRequest(
                header = "Rename ${profile.name}",
                hint = "Enter a name for this profile.",
                initialText = profile.name,
                space = " ",
                onDone = { name ->
                    report {
                        val renamed = client.renameProfile(profile.id, name)
                        "Renamed profile to ${renamed.name}."
                    }
                },
            ),
        )
    }

    fun openAuthorized(profile: HouseholdProfile) {
        val options = buildList {
            if (profile.id != activeId) add(SWITCH_PROFILE)
            add(RENAME_PROFILE)
            add(if (profile.hasPin) CHANGE_PIN else SET_PIN)
            if (profile.hasPin) add(REMOVE_PIN)
            if (profile.id != OwnerProfile.id && profile.id != activeId) add(DELETE_PROFILE)
        }
        actions.onChoice(
            PanelRequest(
                header = profile.name,
                options = options,
                selected = null,
                onPick = { choice ->
                    when (choice) {
                        SWITCH_PROFILE -> scope.launch(Dispatchers.Default) {
                            when (client.selectProfile(profile.id)) {
                                PinVerification.PlaybackActive -> withContext(Dispatchers.Main) {
                                    actions.nav.toast("Finish or stop the current title before switching profiles.")
                                }
                                else -> Unit
                            }
                        }
                        RENAME_PROFILE -> rename(profile)
                        SET_PIN, CHANGE_PIN -> setPin(profile)
                        REMOVE_PIN -> report {
                            client.setProfilePin(profile.id, null)
                            "PIN removed from ${profile.name}."
                        }
                        DELETE_PROFILE -> actions.onConfirm(
                            ConfirmRequest(
                                title = "Delete ${profile.name}?",
                                body = "This permanently removes this profile's progress, Watchlist and collections from this TV.",
                                destructiveLabel = "Delete",
                                onConfirm = {
                                    report {
                                        if (client.deleteProfile(profile.id)) {
                                            "Deleted ${profile.name}."
                                        } else {
                                            "Stop local playback before deleting a profile."
                                        }
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }

    fun open(profile: HouseholdProfile) {
        if (!profile.hasPin || profile.id == activeId) {
            openAuthorized(profile)
            return
        }
        actions.onProfilePin(
            ProfilePinRequest("Unlock ${profile.name}") { pin ->
                val copy = pin.copyOf()
                scope.launch(Dispatchers.Default) {
                    val result = try {
                        client.verifyProfilePin(profile.id, copy)
                    } finally {
                        copy.fill('\u0000')
                    }
                    withContext(Dispatchers.Main) {
                        when (result) {
                            PinVerification.Verified, PinVerification.NotRequired -> openAuthorized(profile)
                            is PinVerification.Rejected ->
                                actions.nav.toast("That PIN is not right. ${result.attemptsRemaining} tries remain.")
                            is PinVerification.Locked -> actions.nav.toast("That profile is temporarily locked.")
                            else -> actions.nav.toast("That profile could not be unlocked.")
                        }
                    }
                }
            },
        )
    }

    Column {
        PaneTitle("Profiles")
        Spacer(Modifier.height(TvSpace.XS))
        PaneNote("Progress, Watchlist, collections and calendar are separate. Add-ons and refresh jobs stay shared on this TV.")
        Spacer(Modifier.height(TvSpace.S))
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            profiles.forEachIndexed { index, profile ->
                SettingRow(
                    label = profile.name,
                    value = when {
                        profile.id == activeId && profile.hasPin -> "Current · PIN"
                        profile.id == activeId -> "Current"
                        profile.hasPin -> "PIN"
                        else -> ""
                    },
                    onClick = { open(profile) },
                    modifier = if (index == 0) Modifier.focusRequester(actions.firstControl) else Modifier,
                )
            }
        }
        Spacer(Modifier.height(TvSpace.M))
        SettingRow(
            label = "Add profile",
            value = "",
            onClick = {
                actions.onKeyboard(
                    KeyboardRequest(
                        header = "Add a profile",
                        hint = "Enter a name. You can add a PIN afterward.",
                        initialText = "",
                        space = " ",
                        onDone = { name ->
                            report {
                                val created = client.createProfile(name)
                                "Added ${created.name}."
                            }
                        },
                    ),
                )
            },
        )
    }
}

private const val SWITCH_PROFILE = "Switch to this profile"
private const val RENAME_PROFILE = "Rename"
private const val SET_PIN = "Set PIN"
private const val CHANGE_PIN = "Change PIN"
private const val REMOVE_PIN = "Remove PIN"
private const val DELETE_PROFILE = "Delete profile"
