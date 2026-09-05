# Install 4789 TV

The current public compatibility download is **v0.1.34**:
[Download 4789tv.apk](https://github.com/4789-app/4789-tv/releases/download/v0.1.34/4789tv.apk)
· [Release notes](https://github.com/4789-app/4789-tv/releases/tag/v0.1.34).
Newer local candidates are not this download. The APK is 59,236,985 bytes; SHA-256:

```text
bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b
```

## Check your device

Use Android TV, Google TV or NVIDIA Shield running Android 9/API 28 or later, or
Android-based Fire OS 7 or later. Check Settings → About for the actual OS version.
The APK does not run on Apple TV, Roku, Samsung Tizen, LG webOS, or Fire TVs running
[Vega OS](https://developer.amazon.com/docs/vega/0.21/app-submission).
The minimum OS allows installation; it does not certify every codec, HDR mode, audio route,
or 4K source. Installing the receiver does not supply films, channels, or a media service.

## Android TV, Google TV and Shield

1. Use the main device profile. Install [Downloader by AFTVnews](https://play.google.com/store/apps/details?id=com.esaba.downloader)
   from the TV's app store, or use a browser that supports APK downloads.
2. Open `https://4789library.com/player` in Downloader and choose **Download verified APK**.
   On GitHub, expand **Assets** and choose **4789tv.apk**, not a source ZIP.
3. Open the downloaded file. If blocked, choose **Settings** in the installation prompt.
   Under **Install unknown apps**, allow the app opening the file: Downloader, your browser,
   or your file manager. This is permission for the installer, not for 4789 TV.
4. Press Back, open the APK again, choose **Install**, then **Open**. Later use the apps list
   or Settings → Apps → 4789 TV. Keep the receiver open while connecting iPhone.
5. You may disable the installer's unknown-app permission afterward and enable it for updates.

Menu locations vary. Use the installation prompt first. Sony's example is Settings → Privacy →
Security & Restrictions → Unknown sources → Install unknown apps; see
[Sony's guide](https://helpguide.sony.net/tv/lusltn2/v1/en-003/01-02_05.html) and
[Android's alternative-installation guidance](https://developer.android.com/distribute/marketing-tools/alternative-distribution).
If unavailable, check restricted/child profiles and your manufacturer's device-policy guidance.

## Fire TV with Android-based Fire OS

1. Search the Amazon Appstore for **Downloader by AFTVnews**, install it, and open it once.
2. Open Settings → My Fire TV → Developer options. If hidden, open My Fire TV → About,
   highlight the device name, press Select seven times, then press Back. See
   [Amazon's menu-unlock instructions](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html).
3. In Developer options, open **Install unknown apps** and enable Downloader. Some versions
   call it **Apps from Unknown Sources**. See
   [Amazon's installation-permission guidance](https://www.amazonforum.com/s/question/0D5at00000Uer6ACAR/cant-install-new-apps?language=en_US).
   ADB debugging is not needed for this route.
4. In Downloader, visit `https://4789library.com/player`, download **4789tv.apk**, then
   choose Install → Open. If using a browser/file manager, permit the app opening the file.
5. Reopen from Your Apps & Channels, or Settings → Applications → Manage Installed
   Applications → 4789 TV → Launch application. Disable unknown-source installation afterward
   if desired. These instructions do not apply to Vega OS.

## Connect iPhone

1. Install the iPhone app using the [iPhone installation guide](https://4789library.com/install).
   Connect iPhone and TV to the same home router. Ethernet TV plus Wi-Fi iPhone works when
   both are on the same local network. Leave the TV receiver screen open.
2. Allow Local Network access in 4789. If previously denied, enable Settings → Privacy &
   Security → Local Network → 4789. See [Apple's instructions](https://support.apple.com/en-us/102229).
3. Choose **Connect** when the ready receiver appears. Select an accessible playable source
   and the TV destination. Try pause/resume from iPhone to check the connection.

If discovery fails, check permissions, guest Wi-Fi/client/AP isolation, and VPN local-network
access. Manual receiver setup uses the TV's displayed address and port (HTTP default **8791**),
not the router's or iPhone's address. WebSocket uses **9791**. Update saved addresses after
router changes. Manual entry cannot bypass network isolation. Use a trusted home network;
receiver control ports are unauthenticated and must not be exposed to the internet.

## Troubleshooting and updates

- **APK will not install:** confirm OS support, storage, a complete fresh download, and permission
  for the app opening the file. Record the full package/signature error before uninstalling.
- **Signature mismatch:** a different signing key cannot update an existing installation in
  place. Uninstalling clears local app settings. Preserve your setup and follow release notes;
  do not blindly uninstall. The current compatibility release preserves the established key. A future production-key
  release would use a different certificate. See [release migration](docs/PUBLIC_RELEASE.md#signing-and-existing-installations).
- **Connected but no playback:** the TV must reach the media URL itself. Try a source known to
  work on that device. Local files served by iPhone require the phone to remain reachable.
- **Support:** report model, OS, app version and visible error through
  [TV issues](https://github.com/4789-app/4789-tv/issues). Keep tokens, source URLs, local
  addresses and private library details out of public reports.
- **ADB alternative:** enable network debugging, connect to the receiver, then run
  `adb -s TV_IP:5555 install -r 4789tv.apk`. Do not use uninstall as an automatic error recovery.

Check release notes before every update. A matching SHA-256 proves downloaded byte integrity,
not build provenance. The public v0.1.34 compatibility APK is debug-signed; read
[TRUST.md](TRUST.md) for the evidence and limitations.
