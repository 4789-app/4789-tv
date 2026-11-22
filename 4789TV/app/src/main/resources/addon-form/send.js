import { gcm } from "./gcm.js";

const form = document.querySelector("form");
const input = form.elements.link;
const section = document.querySelector("section");
const list = section.querySelector("ul");
const imports = document.querySelector("#imports");
const status = document.querySelector("[role=status]");
const retry = document.querySelector("#retry");
const copy = document.querySelector("#copy");
const copyLink = document.querySelector("#copy-link");
const copyButton = document.querySelector("#copy-button");
const encoder = new TextEncoder();
const token = location.pathname.split("/").pop();
const secret = location.hash.slice(1);
const key = /^[a-zA-Z0-9_-]{43}$/.test(secret) ? decode(secret) : null;
let ticket = "";
let pending = false;
let busy = false;
let closed = false;
let timer;
let shownRows;

function encode(bytes) {
    return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function decode(value) {
    return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0));
}

function controls() {
    for (const button of document.querySelectorAll("button")) button.disabled = busy || pending || !ticket || closed;
    retry.disabled = busy || closed || !key;
    copyButton.disabled = !copyLink.value || closed;
}

function render(reply) {
    ticket = reply.ticket;
    pending = reply.pending;
    status.textContent = reply.message || "Connected. Changes need confirmation on your TV.";
    retry.hidden = true;
    section.hidden = false;
    imports.hidden = !reply.hasImports;
    const link = reply.copiedLink || "";
    if (copyLink.value !== link) copyLink.value = link;
    copy.hidden = !link;
    const signature = JSON.stringify(reply.addons);
    if (signature === shownRows) return;
    shownRows = signature;
    list.replaceChildren();
    for (const addon of reply.addons) {
        const row = document.createElement("li");
        const label = document.createElement("span");
        label.textContent = addon.name;
        const host = document.createElement("small");
        host.textContent = addon.enabled ? addon.host : addon.host + " · Off";
        label.append(host);
        const actions = document.createElement("div");
        const buttons = [["Refresh", "refresh"], ["Request link", "copy"]];
        if (!addon.builtin) buttons.push([addon.enabled ? "Turn off" : "Turn on", "toggle"], ["Remove", "remove"]);
        for (const [title, action] of buttons) {
            const button = document.createElement("button");
            button.textContent = title;
            button.setAttribute("aria-label", title + " " + addon.name);
            button.onclick = () => send(action, addon.id);
            actions.append(button);
        }
        row.append(label, actions);
        list.append(row);
    }
    if (!reply.addons.length) list.textContent = "No add-ons yet.";
}

async function send(action = "status", value = "") {
    if (busy || closed || !key || (action !== "status" && (!ticket || pending))) return;
    busy = true;
    clearTimeout(timer);
    controls();
    let delay = 4000;
    const request = new AbortController();
    const timeout = setTimeout(() => request.abort(), 9000);
    try {
        const nonce = crypto.getRandomValues(new Uint8Array(12));
        const aad = encoder.encode("4789-addon-link-v1|" + token);
        const command = encoder.encode(JSON.stringify({ action, value, ticket }));
        const bytes = gcm(key, nonce, aad).encrypt(command);
        const response = await fetch(location.pathname, {
            method: "POST", cache: "no-store", signal: request.signal,
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ nonce: encode(nonce), ciphertext: encode(bytes) }),
        });
        if (response.status === 410) {
            closed = true;
            key.fill(0);
            history.replaceState(null, "", location.pathname);
            section.hidden = form.hidden = true;
            copy.hidden = retry.hidden = true;
            copyLink.value = "";
            form.reset();
            status.textContent = "Session closed. Select Manage from your phone on your TV and scan a new code.";
            return;
        }
        if (response.status === 409) {
            status.textContent = "The TV has a newer change. Refreshing the list…";
            delay = 100;
            return;
        }
        if (!response.ok) {
            status.textContent = "Could not send this change. Check the link or refresh the installed list.";
            retry.hidden = false;
            return;
        }
        const envelope = await response.json();
        const plain = gcm(key, decode(envelope.nonce), encoder.encode("4789-addon-reply-v1|" + token))
            .decrypt(decode(envelope.ciphertext));
        const reply = JSON.parse(new TextDecoder().decode(plain));
        render(reply);
        if (pending) delay = 900;
    } catch {
        status.textContent = "TV unavailable. Keep the TV screen open and reconnect to the same Wi-Fi.";
        retry.hidden = false;
    } finally {
        clearTimeout(timeout);
        busy = false;
        controls();
        if (!closed && !document.hidden) timer = setTimeout(() => send(), delay);
    }
}

form.onsubmit = event => { event.preventDefault(); send("add", input.value.trim()); };
imports.onclick = () => send("deleteImports");
retry.onclick = () => send();
copyButton.onclick = async () => {
    try {
        if (navigator.clipboard) {
            await navigator.clipboard.writeText(copyLink.value);
            status.textContent = "Link copied.";
            return;
        }
    } catch {
        // Local HTTP may block access to the clipboard API.
    }
    copyLink.focus();
    copyLink.select();
    status.textContent = "Use your phone’s Copy command on the selected link.";
};
window.addEventListener("online", () => send());
document.addEventListener("visibilitychange", () => {
    if (document.hidden) clearTimeout(timer);
    else send();
});
if (key) send();
else {
    retry.disabled = true;
    status.textContent = "Scan the code on the TV to open this session.";
}
