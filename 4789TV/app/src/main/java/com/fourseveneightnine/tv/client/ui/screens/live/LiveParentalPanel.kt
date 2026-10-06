package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch

@Composable
internal fun LiveParentalPanel(state: IptvState, repo: IptvRepository,
                               onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var authenticated by remember { mutableStateOf(state.accounts.parentalPinHash == null) }
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(authenticated, state.accounts.parentalPinHash) {
        kotlinx.coroutines.delay(20)
        runCatching { first.requestFocus() }
    }
    SidePanel("Parental controls", onClose, width = 760.dp) {
        if (!authenticated) {
            Text("Enter your PIN to manage locked channel groups.", style = TvType.Body,
                color = TvColor.TextSecondary)
            Spacer(Modifier.height(22.dp))
            LiveFormField("PIN", pin, modifier = Modifier.focusRequester(first), password = true,
                onChange = { pin = it.filter(Char::isDigit).take(8) })
            Spacer(Modifier.height(18.dp))
            TvButton("Unlock controls", onClick = {
                if (repo.checkParentalPin(pin)) { authenticated = true; pin = "" }
                else onMessage("Incorrect PIN")
            })
        } else if (state.accounts.parentalPinHash == null) {
            Text("Set a 4–8 digit PIN before locking a group.", style = TvType.Body,
                color = TvColor.TextSecondary)
            Spacer(Modifier.height(22.dp))
            LiveFormField("New PIN", pin, modifier = Modifier.focusRequester(first), password = true,
                onChange = { pin = it.filter(Char::isDigit).take(8) })
            Spacer(Modifier.height(18.dp))
            TvButton("Save PIN", onClick = {
                scope.launch {
                    if (repo.setParentalPin(pin)) { pin = ""; onMessage("PIN saved on this TV.") }
                    else onMessage("Choose a 4–8 digit PIN.")
                }
            })
        } else {
            Text("Choose groups that require a PIN to open or play.", style = TvType.Body,
                color = TvColor.TextSecondary)
            Spacer(Modifier.height(22.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.orderedGroups, key = { it }) { group ->
                    SidePanelRow("${if (group in state.accounts.lockedGroups) "Locked" else "Open"} · $group",
                        selected = group in state.accounts.lockedGroups,
                        onClick = { scope.launch { repo.setGroupLocked(group, group !in state.accounts.lockedGroups) } },
                        modifier = if (group == state.orderedGroups.first()) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
}

@Composable
internal fun LivePinPanel(group: String, repo: IptvRepository, onClose: () -> Unit,
                          onUnlock: () -> Unit, onMessage: (String) -> Unit) {
    val first = remember { FocusRequester() }
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Unlock $group", onClose, width = 680.dp) {
        Column {
            Text("This channel group is locked on this TV.", style = TvType.Body,
                color = TvColor.TextSecondary)
            Spacer(Modifier.height(20.dp))
            LiveFormField("PIN", pin, modifier = Modifier.focusRequester(first), password = true,
                onChange = { pin = it.filter(Char::isDigit).take(8) })
            Spacer(Modifier.height(20.dp))
            TvButton("Unlock group", onClick = {
                if (repo.checkParentalPin(pin)) onUnlock() else onMessage("Incorrect PIN")
            })
        }
    }
}
