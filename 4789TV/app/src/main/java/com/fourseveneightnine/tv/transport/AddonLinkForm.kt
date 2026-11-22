package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.settings.SettingsPairingCrypto
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The TV owns the session and approves each edit or link copy. */
internal class AddonLinkForm(private val now: () -> Long) {
    class Inventory(val addons: List<Addon>, val hasImports: Boolean)

    sealed interface Change {
        class Add(val url: String) : Change
        class Remove(val url: String, val name: String) : Change
        class Refresh(val url: String, val name: String) : Change
        class Toggle(val url: String, val name: String, val enabled: Boolean) : Change
        class Copy(val url: String, val name: String) : Change
        data object DeleteImports : Change
    }

    class Invitation(val address: String, val token: String, val expiresAt: Long, val key: ByteArray,
                     val inventory: () -> Inventory) {
        val connected = MutableStateFlow(false)
        val pending = MutableStateFlow<Change?>(null)
        val ids = mutableMapOf<String, String>()
        var ticket = SettingsPairingCrypto.randomID()
        val message = MutableStateFlow("")
        var copiedLink: String? = null
    }

    @Serializable
    private class Request(val action: String, val value: String = "", val ticket: String = "")

    @Serializable
    private class Row(val id: String, val name: String, val host: String, val enabled: Boolean, val builtin: Boolean)

    @Serializable
    private class Reply(val ticket: String, val pending: Boolean, val message: String,
                        val addons: List<Row>, val hasImports: Boolean, val copiedLink: String? = null)

    private var active: Invitation? = null

    @Synchronized
    fun open(host: String?, inventory: () -> Inventory): Invitation? {
        active?.let(::close)
        if (host == null || !LocalNetworkAddress.isPrivateIPv4(host)) return null
        val token = SettingsPairingCrypto.randomID()
        val key = SettingsPairingCrypto.randomBytes(32)
        val address = "http://$host:${ReceiverPorts.HTTP}/x4789/addon/$token#${SettingsPairingCrypto.encodeBase64URL(key)}"
        return Invitation(address, token, now() + LIFETIME, key, inventory).also { active = it }
    }

    @Synchronized
    fun close(invitation: Invitation) {
        if (active !== invitation) return
        active = null
        invitation.key.fill(0)
        invitation.ids.clear()
        invitation.copiedLink = null
        invitation.pending.value = null
    }

    @Synchronized
    fun isOpen(token: String?): Boolean = active?.let {
        it.token == token && (it.connected.value || now() < it.expiresAt)
    } == true

    @Synchronized
    fun finish(invitation: Invitation, change: Change, message: String, copiedLink: String? = null) {
        if (active !== invitation || invitation.pending.value !== change) return
        invitation.message.value = message
        invitation.copiedLink = copiedLink
        invitation.pending.value = null
    }

    @Synchronized
    fun submit(token: String?, body: String): SettingsEndpointResponse {
        val session = active
        if (session == null || !isOpen(token)) return SettingsEndpointResponse(410, "{}")
        val command = runCatching {
            Json.decodeFromString<Request>(SettingsPairingCrypto.decryptAddonLink(body, session.key, session.token))
        }.getOrNull() ?: return SettingsEndpointResponse(400, "{}")
        val inventory = session.inventory()
        session.ids.keys.retainAll(inventory.addons.map { it.key }.toSet())
        val rows = inventory.addons.map { addon ->
            val id = session.ids.getOrPut(addon.key) { SettingsPairingCrypto.randomID() }
            Row(id, addon.displayName.take(80), URI(addon.key).host.orEmpty(), addon.enabled, addon.key == BUILTIN)
        }
        if (command.action != "status") {
            if (session.pending.value != null || command.ticket != session.ticket) {
                return SettingsEndpointResponse(409, "{}")
            }
            val addon = inventory.addons.find { session.ids[it.key] == command.value }
            val removable = addon?.takeUnless { it.key == BUILTIN }
            val change = when (command.action) {
                "add" -> AddonEndpoint.installURL(command.value)?.let(Change::Add)
                "remove" -> removable?.let { Change.Remove(it.manifestURL, it.displayName.take(80)) }
                "toggle" -> removable?.let { Change.Toggle(it.manifestURL, it.displayName.take(80), !it.enabled) }
                "refresh" -> addon?.let { Change.Refresh(it.manifestURL, it.displayName.take(80)) }
                "copy" -> addon?.let { Change.Copy(it.manifestURL, it.displayName.take(80)) }
                "deleteImports" -> Change.DeleteImports.takeIf { inventory.hasImports }
                else -> null
            } ?: return SettingsEndpointResponse(400, "{}")
            // Consume before publishing so a delayed retry cannot stage the same edit again.
            session.ticket = SettingsPairingCrypto.randomID()
            session.copiedLink = null
            session.message.value = "Confirm on your TV."
            session.pending.value = change
        }
        session.connected.value = true
        val reply = Reply(session.ticket, session.pending.value != null, session.message.value, rows,
            inventory.hasImports, session.copiedLink)
        return SettingsEndpointResponse(200, SettingsPairingCrypto.encryptAddonReply(
            Json.encodeToString(reply), session.key, session.token))
    }

    companion object {
        private val BUILTIN = AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST)
        const val LIFETIME = 5 * 60_000L
        const val CLOSED = "<p>This session is closed. Select Manage from your phone on the TV and scan a new code.</p>"
        const val FORM = """<form method="post" autocomplete="off"><label for="link">Add-on manifest link</label>
            <input id="link" name="link" type="url" placeholder="https://…/manifest.json" maxlength="16384"
              autocapitalize="none" spellcheck="false" required><button disabled>Send to TV</button></form>
            <p role="status" aria-live="polite">Connecting to your TV…</p>
            <button id="retry" type="button">Retry connection</button>
            <div id="copy" hidden><label for="copy-link">Approved manifest link</label>
              <input id="copy-link" readonly><button id="copy-button" type="button">Copy link</button></div>
            <section hidden><h2>Installed add-ons</h2><ul></ul><button id="imports" hidden>Delete Stremio imports</button></section>
            <script type="module" src="/x4789/addon-form/send.js"></script>"""

        fun page(content: String) = """<!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>4789 TV add-ons</title>
            <style>body{font:1.05rem system-ui;margin:2rem auto;padding:0 1.25rem;max-width:30rem}
            input,button{box-sizing:border-box;font:inherit;padding:.7rem}form input,form button{width:100%;margin-top:.6rem}
            ul{list-style:none;padding:0}li{padding:.7rem 0;border-bottom:1px solid #888}
            li div{display:flex;flex-wrap:wrap;gap:.4rem;margin-top:.6rem}li span{overflow-wrap:anywhere}
            small{display:block}p{line-height:1.5}#copy{margin-top:1rem}#copy input{width:100%;margin:.6rem 0}</style>
            <main><h1>TV add-ons</h1>$content</main></html>"""
    }
}
