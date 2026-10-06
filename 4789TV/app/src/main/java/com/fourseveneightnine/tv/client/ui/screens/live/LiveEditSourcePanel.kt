package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.fourseveneightnine.tv.client.iptv.IptvSource
import com.fourseveneightnine.tv.client.iptv.IptvSourceKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch

@Composable
internal fun LiveEditSourcePanel(source: IptvSource, repo: IptvRepository,
                                 onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var name by remember(source.id) { mutableStateOf(source.name) }
    var link by remember(source.id) { mutableStateOf(source.url.orEmpty()) }
    var host by remember(source.id) { mutableStateOf(source.host.orEmpty()) }
    var user by remember(source.id) { mutableStateOf(source.username.orEmpty()) }
    var password by remember(source.id) { mutableStateOf("") }
    var mac by remember(source.id) { mutableStateOf(source.mac.orEmpty()) }
    var epg by remember(source.id) { mutableStateOf(source.epgUrl.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Edit ${source.name}", onClose, width = 760.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Changes are saved on this TV. Existing channels stay visible while it refreshes.",
                style = TvType.Meta, color = TvColor.TextSecondary)
            LiveFormField("Source name", name, modifier = Modifier.focusRequester(first), onChange = { name = it })
            when (source.kind) {
                IptvSourceKind.M3U -> LiveFormField("Playlist URL", link, onChange = { link = it })
                IptvSourceKind.XTREAM -> {
                    LiveFormField("Provider host", host, onChange = { host = it })
                    LiveFormField("Username", user, onChange = { user = it })
                    LiveFormField("New password (leave blank to keep saved)", password,
                        password = true, onChange = { password = it })
                }
                IptvSourceKind.STALKER -> {
                    LiveFormField("Portal host", host, onChange = { host = it })
                    LiveFormField("MAC address", mac, onChange = { mac = it })
                }
            }
            LiveFormField("XMLTV guide URL (optional)", epg, onChange = { epg = it })
            Spacer(Modifier.height(8.dp))
            TvButton(if (saving) "Saving…" else "Save changes", enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val saved = repo.updateSource(source.id, name, link, host, user, password, mac, epg)
                    saving = false
                    if (saved) { onMessage("Source updated on this TV."); onClose() }
                    else onMessage("Check the source details and try again.")
                }
            })
        }
    }
}
