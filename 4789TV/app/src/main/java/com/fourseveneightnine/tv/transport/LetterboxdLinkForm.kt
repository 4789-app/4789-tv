package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.settings.SettingsPairingCrypto
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A short-lived invitation to stage one public list link for review on the TV. */
internal class LetterboxdLinkForm(private val now: () -> Long) {
    class Invitation(val address: String, internal val token: String, val expiresAt: Long) {
        internal val pending = MutableStateFlow<String?>(null)
        val list = pending.asStateFlow()
    }

    private var active: Invitation? = null

    @Synchronized
    fun open(host: String?): Invitation? {
        active = null
        if (host == null || !LocalNetworkAddress.isPrivateIPv4(host)) return null
        val token = SettingsPairingCrypto.randomID()
        return Invitation("http://$host:${ReceiverPorts.HTTP}/x4789/letterboxd/$token", token, now() + LIFETIME)
            .also { active = it }
    }

    @Synchronized
    fun close(session: Invitation) {
        if (active === session) active = null
    }

    @Synchronized
    fun isOpen(token: String?): Boolean = active?.let { it.token == token && now() < it.expiresAt } == true

    @Synchronized
    fun submit(token: String?, body: String): SettingsEndpointResponse {
        val session = active
        if (session == null || !isOpen(token)) return SettingsEndpointResponse(410, CLOSED)
        if (session.pending.value != null) return SettingsEndpointResponse(409, SENT)
        val url = body.takeIf { it.startsWith("link=") && '&' !in it }
            ?.let { runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrNull() }
        val list = url?.let { TVLetterboxdClient.importLink(it) } ?: return SettingsEndpointResponse(400, INVALID)
        session.pending.value = list
        return SettingsEndpointResponse(200, SENT)
    }

    companion object {
        const val LIFETIME = 5 * 60_000L
        const val CLOSED = "<p>This code has expired. Open Letterboxd on your TV for a new code.</p>"
        private const val SENT = "<p>Link sent. Select Import list on your TV to begin.</p>"
        private const val FORM_FIELDS = """<form method="post"><label for="link">Letterboxd list link</label>
            <input id="link" name="link" type="url" required maxlength="512" autocomplete="off"
            autocapitalize="none" spellcheck="false"
            placeholder="https://letterboxd.com/name/list/list-name/"><button>Send to TV</button></form>
            <p role="status">Keep the QR screen open on your TV while sending.</p>
            <script defer src="/x4789/letterboxd-form/send.js"></script>"""
        private const val INVALID = "<p>Enter the address of a public Letterboxd list.</p>" + FORM_FIELDS
        const val FORM = FORM_FIELDS

        const val SEND_SCRIPT = """
            const form = document.querySelector("form");
            const status = document.querySelector("[role=status]");
            form.addEventListener("submit", async (event) => {
                event.preventDefault();
                const button = form.querySelector("button");
                button.disabled = true;
                status.textContent = "Sending to your TV…";
                const controller = new AbortController();
                const timeout = setTimeout(() => controller.abort(), 8000);
                try {
                    const response = await fetch(location.pathname, {
                        method: "POST",
                        body: new URLSearchParams(new FormData(form)),
                        signal: controller.signal
                    });
                    if (response.ok || response.status === 409) {
                        status.textContent = response.ok
                            ? "Link sent. Select Import list on your TV to begin."
                            : "A link is already waiting. Select Import list on your TV.";
                        form.hidden = true;
                    } else if (response.status === 410) {
                        status.textContent = "This code expired. Open a new code on your TV and scan again.";
                        form.hidden = true;
                    } else {
                        status.textContent = response.status === 400
                            ? "Enter the address of a public Letterboxd list."
                            : "Your TV could not accept the link. Try sending again.";
                    }
                } catch {
                    status.textContent = "Could not confirm delivery. Check your TV for Import list, then try again if needed.";
                } finally {
                    clearTimeout(timeout);
                    button.disabled = false;
                }
            });
        """

        fun page(content: String) = """<!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>Send a Letterboxd list</title>
            <style>body{font:1.1rem system-ui;margin:2rem auto;padding:0 1rem;max-width:30rem}input,button
            {box-sizing:border-box;display:block;width:100%;padding:.7rem;margin-top:.6rem;font:inherit}</style>
            <main><h1>Send a Letterboxd list</h1>$content</main></html>"""
    }
}
