package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
internal fun LiveManageGroupsPanel(state: IptvState, repo: IptvRepository, onClose: () -> Unit,
                                   onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var selected by remember { mutableStateOf(state.orderedGroups.firstOrNull()) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Manage channel groups", onClose, width = 780.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.accounts.manualGroupOrder) "Custom order saved on this TV"
                else "Personalized order: Telugu · cricket · sports · Hindi · Tamil · English movies",
                style = TvType.Meta, color = TvColor.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(if (selected in state.accounts.hiddenGroups) "Show" else "Hide", onClick = {
                    selected?.let { name -> scope.launch { repo.setGroupHidden(name, name !in state.accounts.hiddenGroups) } }
                })
                TvButton("Move up", onClick = { selected?.let { scope.launch { repo.moveGroup(it, -1) } } })
                TvButton("Move down", onClick = { selected?.let { scope.launch { repo.moveGroup(it, 1) } } })
            }
            TvButton("Restore personalized order", onClick = {
                scope.launch {
                    if (repo.resetGroupOrder()) onMessage("Personalized group order restored.")
                }
            })
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(state.orderedGroups, key = { it }) { group ->
                    SidePanelRow("${if (group in state.accounts.hiddenGroups) "Hidden · " else ""}$group",
                        selected = group == selected, onClick = { selected = group },
                        modifier = if (group == state.orderedGroups.first()) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
}
