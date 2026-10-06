@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fourseveneightnine.tv.client.profiles.HouseholdProfile
import com.fourseveneightnine.tv.client.profiles.PinVerification
import com.fourseveneightnine.tv.client.profiles.ProfileCatalog
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.util.UUID

/** Full-screen boundary: no personal screen is composed until one profile is unlocked. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ProfileGate(
    catalog: ProfileCatalog,
    onSelect: (UUID) -> PinVerification,
    onUnlock: (UUID, CharArray) -> PinVerification,
) {
    var pinProfile by remember { mutableStateOf<HouseholdProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Changing people must destroy the prior PIN entry and its error state.
    LaunchedEffect(pinProfile?.id) { message = null }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                pinProfile = null
                message = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .semantics {
                paneTitle = if (pinProfile == null) "Choose profile" else "Profile PIN"
                isTraversalGroup = true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pinProfile == null) {
            ProfilePicker(
                catalog = catalog,
                message = message,
                onPick = { profile ->
                    when (onSelect(profile.id)) {
                        PinVerification.Required -> pinProfile = profile
                        PinVerification.PlaybackActive ->
                            message = "Finish or stop the current title before switching profiles."
                        else -> Unit
                    }
                },
            )
        } else {
            val profile = requireNotNull(pinProfile)
            ProfilePinPanel(
                title = "Unlock ${profile.name}",
                message = message ?: "Enter the 4-digit PIN.",
                onSubmit = { pin ->
                    when (val result = onUnlock(profile.id, pin)) {
                        PinVerification.Verified, PinVerification.NotRequired -> Unit
                        is PinVerification.Rejected -> {
                            message = "That PIN is not right. ${result.attemptsRemaining} tries remain."
                        }
                        is PinVerification.Locked -> {
                            val seconds = ((result.untilMillis - System.currentTimeMillis()) / 1_000L)
                                .coerceAtLeast(1L)
                            message = "Too many attempts. Try again in ${seconds}s."
                        }
                        PinVerification.PlaybackActive ->
                            message = "Finish or stop the current title before switching profiles."
                        PinVerification.Required -> message = "Enter the 4-digit PIN."
                    }
                },
                onCancel = { pinProfile = null },
            )
        }
    }

    BackHandler(enabled = pinProfile != null) { pinProfile = null }
}

@Composable
private fun ProfilePicker(
    catalog: ProfileCatalog,
    message: String?,
    onPick: (HouseholdProfile) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Who's watching?", style = TvType.HeroTitle, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(TvSpace.XS))
        Text(
            "Choose a profile to keep progress, Watchlist and collections separate.",
            style = TvType.Body,
            color = TvColor.TextSecondary,
            maxLines = 2,
        )
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier
                .width(1480.dp)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        ) {
            catalog.profiles.forEachIndexed { index, profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.id == catalog.selectedProfileId,
                    onClick = { onPick(profile) },
                    modifier = if (
                        profile.id == catalog.selectedProfileId ||
                        (catalog.profiles.none { it.id == catalog.selectedProfileId } && index == 0)
                    ) {
                        Modifier.focusRequester(initialFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
        Spacer(Modifier.height(TvSpace.M))
        Text(
            message ?: "Profiles are stored only on this TV.",
            style = TvType.Meta,
            color = if (message == null) TvColor.TextMuted else TvColor.Warning,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = if (message != null) {
                Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
            } else {
                Modifier
            },
        )
    }
    LaunchedEffect(catalog.selectedProfileId, catalog.profiles.size) {
        runCatching { initialFocus.requestFocus() }
    }
}

@Composable
private fun ProfileCard(
    profile: HouseholdProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = "${profile.name}, ${if (profile.hasPin) "PIN protected" else "Ready"}",
        selected = selected,
        clickLabel = "Choose profile",
        cornerRadius = 18.dp,
        focusScale = 1.04f,
    ) { focused ->
        Column(
            Modifier
                .width(252.dp)
                .height(316.dp)
                .clip(TvShape.Panel)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(148.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(profileColor(profile.avatarKey)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    profile.name.trim().take(1).uppercase(),
                    style = TvType.HeroTitle,
                    color = TvColor.Canvas,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(TvSpace.M))
            Text(
                profile.name,
                style = TvType.ControlLabel,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (profile.hasPin) "PIN protected" else "Ready",
                style = TvType.Meta,
                color = TvColor.TextMuted,
                maxLines = 1,
            )
        }
    }
}

/** Shared by the launch gate and Settings. PIN bytes are overwritten on reset and disposal. */
@Composable
internal fun ProfilePinPanel(
    title: String,
    message: String,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf(CharArray(0)) }
    val firstKey = remember { FocusRequester() }

    fun clear() {
        pin.fill('\u0000')
        pin = CharArray(0)
    }

    DisposableEffect(Unit) { onDispose { pin.fill('\u0000') } }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Column(
        Modifier.width(620.dp).clip(TvShape.Panel).background(TvColor.Elevated).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = TvType.PanelHeader, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(TvSpace.XS))
        Text(
            message,
            style = TvType.Meta,
            color = TvColor.TextSecondary,
            maxLines = 2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(TvSpace.M))
        Text(
            buildString { repeat(4) { append(if (it < pin.size) "●  " else "○  ") } }.trim(),
            style = TvType.data(34),
            color = TvColor.TextPrimary,
            maxLines = 1,
            modifier = Modifier.semantics {
                contentDescription = "PIN"
                password()
                stateDescription = "${pin.size} of $PIN_LENGTH digits entered"
            },
        )
        Spacer(Modifier.height(TvSpace.M))
        PIN_ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { columnIndex, label ->
                    PinKey(
                        label = label,
                        modifier = if (rowIndex == 0 && columnIndex == 0) {
                            Modifier.focusRequester(firstKey)
                        } else {
                            Modifier
                        },
                    ) {
                        when (label) {
                            "Delete" -> if (pin.isNotEmpty()) {
                                val old = pin
                                pin = old.copyOf(old.size - 1)
                                old.fill('\u0000')
                            }
                            "Done" -> if (pin.size == PIN_LENGTH) {
                                val submitted = pin.copyOf()
                                clear()
                                try {
                                    onSubmit(submitted)
                                } finally {
                                    submitted.fill('\u0000')
                                }
                            }
                            else -> if (pin.size < PIN_LENGTH) {
                                val old = pin
                                pin = old + label.single()
                                old.fill('\u0000')
                            }
                        }
                    }
                }
            }
            if (rowIndex != PIN_ROWS.lastIndex) Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(TvSpace.M))
        com.fourseveneightnine.tv.client.ui.components.TvButton(
            label = "Cancel",
            onClick = { clear(); onCancel() },
            kind = com.fourseveneightnine.tv.client.ui.components.ButtonKind.Ghost,
        )
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = when (label) {
            "Delete" -> "Delete PIN digit"
            "Done" -> "Submit PIN"
            else -> "PIN digit $label"
        },
        cornerRadius = 10.dp,
        focusScale = 1f,
    ) { focused ->
        Box(
            Modifier
                .width(if (label.length > 1) 160.dp else 112.dp)
                .height(72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else TvColor.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TvType.Key, color = TvColor.TextPrimary, maxLines = 1)
        }
    }
}

private fun profileColor(key: String): Color = when (key.lowercase()) {
    "cyan" -> TvColor.Focus
    "amber" -> TvColor.Warning
    "rose" -> Color(0xFFE98AA8)
    "green" -> TvColor.Cached
    "owner" -> Color(0xFFB6A6FF)
    else -> Color(0xFF9D8CFF)
}

private const val PIN_LENGTH = 4
private val PIN_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("Delete", "0", "Done"),
)
