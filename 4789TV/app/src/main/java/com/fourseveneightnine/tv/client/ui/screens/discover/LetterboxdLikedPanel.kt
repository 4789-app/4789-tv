package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType

@Composable
internal fun LetterboxdLikedPanel(
    username: String,
    loading: Boolean,
    message: String?,
    choices: List<DiscoverCatalogChoice>,
    selectedKey: String?,
    listState: LazyListState,
    active: Boolean,
    onSetUsername: (String) -> Unit,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val mirrored = remember(choices, username) { DiscoverPlan.phoneLikedChoices(choices, username) }
    val live = remember(choices) { choices.filter { it.likedList != null } }
    val saved = remember(choices) {
        choices.filter { it.shelfId?.startsWith("letterboxd:liked:") == true }
    }
    val showingSaved = mirrored.isEmpty() && live.isEmpty() && saved.isNotEmpty() && (loading || message != null)
    val shown = when {
        live.isNotEmpty() -> live
        mirrored.isNotEmpty() -> mirrored
        showingSaved -> saved
        else -> live
    }
    val changeFocus = remember { FocusRequester() }
    val firstKey = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    BackHandler(enabled = active && editing) { editing = false }
    LaunchedEffect(active, editing) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        when {
            editing -> firstKey.requestFocus()
            selectedKey != null && shown.any { it.key == selectedKey } -> {
                listState.scrollToItem(shown.indexOfFirst { it.key == selectedKey })
                withFrameNanos { }
                if (!selectedFocus.requestFocus()) changeFocus.requestFocus()
            }
            else -> changeFocus.requestFocus()
        }
    }

    SidePanel(header = "Letterboxd liked lists", closeOnLeft = false, onClose = onClose) {
        if (editing) {
            Text(
                if (invalid) "Enter a Letterboxd username" else draft.ifBlank { "Letterboxd username" },
                color = if (invalid) TvColor.Error else TvColor.TextPrimary,
                style = TvType.ControlLabel,
                maxLines = 1,
            )
            Spacer(Modifier.height(18.dp))
            TvKeyboard(
                modal = true,
                usernameInput = true,
                keyWidth = 85.dp,
                keyHeight = 54.dp,
                gap = 10.dp,
                firstKeyModifier = Modifier.focusRequester(firstKey),
                onKey = { stroke ->
                    invalid = false
                    when (stroke) {
                        is KeyStroke.Character -> draft = (draft + stroke.value).take(32)
                        KeyStroke.Backspace -> draft = draft.dropLast(1)
                        KeyStroke.Space -> Unit
                        KeyStroke.Commit -> {
                            val value = draft.trim().lowercase()
                            if (TVLetterboxdClient.validUsername(value)) {
                                onSetUsername(value)
                                editing = false
                            } else invalid = true
                        }
                    }
                },
            )
        } else {
            Text(
                username.ifBlank { "Set your Letterboxd username" },
                color = TvColor.TextPrimary,
                style = TvType.ControlLabel,
                maxLines = 1,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Change username", {
                    draft = username
                    invalid = false
                    editing = true
                }, modifier = Modifier.focusRequester(changeFocus))
                if (username.isNotBlank()) TvButton("Refresh", onRefresh)
            }
            Spacer(Modifier.height(14.dp))
            val status = when {
                live.isNotEmpty() -> "${live.size} liked lists"
                mirrored.isNotEmpty() -> "Synced from iPhone · ${mirrored.size} lists"
                loading -> "Fetching liked lists…"
                message != null -> message
                username.isBlank() -> "Use your public Letterboxd profile."
                shown.isEmpty() -> "No public liked lists found."
                else -> "${shown.size} liked lists"
            }
            Text(status, color = TvColor.TextSecondary, style = TvType.Meta, maxLines = 2)
            if (mirrored.isNotEmpty() && live.isEmpty()) {
                Text("Reconnect your iPhone to sync changes.", color = TvColor.TextSecondary, style = TvType.Meta)
            } else if (showingSaved) {
                Text("Saved liked lists from TV sync · ${shown.size}", color = TvColor.TextSecondary, style = TvType.Meta)
            }
            Spacer(Modifier.height(14.dp))
            LazyColumn(state = listState) {
                items(shown, key = { it.key }) { choice ->
                    SidePanelRow(
                        label = choice.catalogName,
                        selected = choice.key == selectedKey,
                        onClick = { onPick(choice.key) },
                        modifier = if (choice.key == selectedKey) Modifier.focusRequester(selectedFocus) else Modifier,
                    )
                }
            }
        }
    }
}
