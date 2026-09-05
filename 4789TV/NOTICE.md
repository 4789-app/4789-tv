# Third-party components and why this project is GPL-3.0

4789 TV is a receiver application. It is assembled from libraries whose licences decide the
licence of the whole, so this file records what is linked, under what terms, and what that
obliges anyone who distributes a build.

## Why the whole receiver is GPL-3.0

The receiver links **NextLib** (`io.github.anilbeesetti:nextlib-media3ext`), which is licensed
**GPL-3.0** and ships FFmpeg decoders compiled into the APK. GPL-3.0 is a strong copyleft
licence: a work that links it and is then distributed to anyone else must itself be offered
under GPL-3.0, with complete corresponding source.

That is not a preference. Publishing the APK is distribution, so the obligation attaches the
moment a build leaves this machine — including a GitHub release, a Downloader code, or an APK
handed to a friend. This repository exists so that obligation is met rather than ignored.

Removing NextLib alone is insufficient to establish an LGPL-only native stack: the pinned
libmpv Android 1.0.0 recipe also enables FFmpeg GPL components. Any future licence change
requires auditing the actual replacement native build.

## What is linked

| Component | Version | Licence | Effect on this project |
|---|---|---|---|
| NextLib media3 extension (bundled FFmpeg) | 1.7.1-0.9.0 | **GPL-3.0** | Forces GPL-3.0 on the whole receiver |
| libmpv for Android (`dev.jdtech.mpv:libmpv`) | 1.0.0 | MIT wrapper; native recipe enables GPL FFmpeg | Full native source/build closure must be audited |
| AndroidX Media3 — ExoPlayer, UI, effect, OkHttp datasource | 1.7.1 | Apache-2.0 | Attribution notice |
| Ktor server — CIO, WebSockets | 3.3.2 | Apache-2.0 | Attribution notice |
| Kotlin, kotlinx-coroutines, kotlinx-serialization | 2.2.20 | Apache-2.0 | Attribution notice |
| AndroidX core-ktx, appcompat, lifecycle-service | see `gradle/libs.versions.toml` | Apache-2.0 | Attribution notice |

The native-source audit, immutable upstream revisions, and outstanding release evidence are
recorded in [`docs/NATIVE_SOURCE_AUDIT.md`](docs/NATIVE_SOURCE_AUDIT.md). A Maven sources JAR
is not the full native source: libmpv 1.0.0 publishes only a manifest in that JAR, and
NextLib's sources JAR contains Java/Kotlin wrappers without FFmpeg and JNI build sources.

Exact versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml); that file
is the authority, not this table.

## Receiver interface fonts

The Android TV receiver bundles Bricolage Grotesque, Figtree, and Space Mono from the
[Google Fonts repository](https://github.com/google/fonts), each under the SIL Open Font
License 1.1. Bricolage Grotesque is used for display titles, Figtree for interface copy, and
Space Mono for receiver/data labels. The corresponding OFL texts are available in the upstream
Google Fonts family directories (`ofl/bricolagegrotesque`, `ofl/figtree`, and `ofl/spacemono`).
The Figtree and Bricolage resources are static weight instances produced from those official
Google Fonts variable binaries (400/500/600/700 and 700/800 respectively), so Android resource
selection and legacy `Typeface` calls receive the requested weight rather than duplicated bytes.
The complete bundled licence texts are shipped in each receiver APK at
`assets/licenses/OFL-BricolageGrotesque.txt`, `assets/licenses/OFL-Figtree.txt`, and
`assets/licenses/OFL-SpaceMono.txt`; their single source of truth is `4789TV/licenses/`.

## The iOS and macOS siblings are a separate question

The iOS application links **MPVKit**, and specifically the `MPVKit` product rather than the
`MPVKit-GPL` product — see the `product: MPVKit` line in the app's `project.yml`. That variant is
**LGPL-3.0**, which does not reach into the application's own source the way GPL does. The iOS
app is therefore not covered by this repository or its licence, and nothing here should be read
as opening it.

Anyone distributing that iOS build still owes the LGPL obligations: ship the licence text and the
attribution notice, and keep the user's ability to relink against a modified MPVKit.

## Trademarks

Application logos served by the Mac-side installer (`installer/logos/`) are the trademarks of
their respective projects. They are used only to identify each application in a launcher. They do
not imply that any of those projects endorse or are affiliated with this one. Each was downloaded
from the project's own repository or website by `installer/refresh-logos.sh`.
