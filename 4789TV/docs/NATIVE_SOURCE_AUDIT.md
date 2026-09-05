# Native corresponding-source audit

Audited 2026-09-04. **Closure is not yet verified.** This report identifies the upstream
recipes and source revisions; it does not certify a complete release source package or a
reproducible native build. No runtime code was changed for this audit.

## Published artifact evidence

- NextLib `io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0`: upstream
  [release](https://github.com/anilbeesetti/nextlib/releases/tag/1.7.1-0.9.0), immutable
  revision `76f988cf2a4acdf66c243a222a99349a79ae3eea`.
  [Full source archive](https://github.com/anilbeesetti/nextlib/archive/76f988cf2a4acdf66c243a222a99349a79ae3eea.tar.gz).
  Maven's sources JAR contains 40,607 bytes across 17 entries, Java/Kotlin wrappers;
  it omits the native implementation and dependency sources.
- libmpv `dev.jdtech.mpv:libmpv:1.0.0`: upstream
  [release](https://github.com/jarnedemeulemeester/libmpv-android/releases/tag/v1.0.0),
  revision `fcf6745703dc1265bca88f12fee8fc355ddf251e`.
  [Full source archive](https://github.com/jarnedemeulemeester/libmpv-android/archive/fcf6745703dc1265bca88f12fee8fc355ddf251e.tar.gz).
  Maven AAR SHA-256 `df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2`
  matches the GitHub release asset digest. Maven's sources JAR contains only META-INF
  and a 25-byte manifest, **no implementation source**. The release lists native versions
  below and says there are no patches.

Maven files are available beneath
[NextLib Maven directory](https://repo.maven.apache.org/maven2/io/github/anilbeesetti/nextlib-media3ext/1.7.1-0.9.0/)
and [libmpv Maven directory](https://repo.maven.apache.org/maven2/dev/jdtech/mpv/libmpv/1.0.0/).

## NextLib native closure

Authoritative [setup recipe](https://github.com/anilbeesetti/nextlib/blob/76f988cf2a4acdf66c243a222a99349a79ae3eea/ffmpeg/setup.sh).
Preserve the complete NextLib tree, including JNI/CMake, Gradle wrapper and licence.
Its `gradle/libs.versions.toml` pins NDK `25.2.9519653`, CMake `3.22.1`, AGP `8.10.1`,
Kotlin `2.1.21`, Media3 `1.7.1`; the source tree supplies decoder lists/configuration.

| Source | Recipe URL | Resolved commit when Git-backed |
|---|---|---|
| FFmpeg 6.0 | https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz | Recipe consumes release tarball; retain exact tarball and checksum |
| libvpx 1.13.0 | https://github.com/webmproject/libvpx/archive/refs/tags/v1.13.0.tar.gz | `d6eb9696aa72473c1a11d34d928d35a3acc0c9a9` |
| Mbed TLS 3.4.1 | https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v3.4.1.tar.gz | `72718dd87e087215ce9155a826ee5a66cfbe9631` |

## libmpv native closure

Authoritative [version pins](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/include/depinfo.sh)
and [fetch recipe](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/include/download-deps.sh).
The upstream publication workflow builds native dependencies before Gradle publishes.

| Source repository | Tag | Resolved commit |
|---|---|---|
| https://github.com/Mbed-TLS/mbedtls | v3.6.6 | `0bebf8b8c7f07abe3571ded48a11aa907a1ffb20` |
| https://code.videolan.org/videolan/dav1d | 1.5.3 | `b546257f770768b2c88258c533da38b91a06f737` |
| https://github.com/FFmpeg/FFmpeg | n8.1 | `9047fa1b084f76b1b4d065af2d743df1b40dfb56` |
| https://gitlab.freedesktop.org/freetype/freetype | VER-2-14-3 | `0a0221a1347e2f1e07c395263540026e9a0aa7c7` |
| https://github.com/fribidi/fribidi | v1.0.16 | `68162babff4f39c4e2dc164a5e825af93bda9983` |
| https://github.com/harfbuzz/harfbuzz | 14.1.0 | `cfb70b0b91af933a08339c7c8eef459df1098d7b` |
| https://gitlab.gnome.org/GNOME/libxml2 | v2.15.2 | `3d840e17858de03a09fba8b202e3a89267d5795a` |
| https://gitlab.freedesktop.org/fontconfig/fontconfig | 2.17.1 | `6d0a98982ec351c165c9224c8b7dbdfca3010e47` |
| https://github.com/libass/libass | 0.17.4 | `bbb3c7f1570a4a021e52683f3fbdf74fe492ae84` |
| https://code.videolan.org/videolan/libplacebo | v7.360.1 | `cee9b076f2c63104ccfd497fa79c39a867293ec4` |
| https://github.com/mpv-player/mpv | v0.41.0 | `41f6a645068483470267271e1d09966ca3b9f413` |

Additional release tarballs:
[libunibreak 6.1](https://github.com/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-6.1.tar.gz)
and [Lua 5.2.4](https://www.lua.org/ftp/lua-5.2.4.tar.gz).

Mbed TLS 3.6.6 requires its pinned `framework` submodule. libplacebo's fetch uses recursive
submodules: `demos/3rdparty/nuklear`, `3rdparty/glad`, `3rdparty/jinja`,
`3rdparty/markupsafe`, `3rdparty/Vulkan-Headers`, and `3rdparty/fast_float`.
Ordinary GitHub source archives do not contain submodule contents. Capture the recursively
resolved revisions and their licence files, even where a configured build disables a feature.
The libplacebo recipe disables Vulkan and demos; shaderc is not fetched by this version's
`download-deps.sh` despite an unused shaderc script being present.

Toolchain pins: platform android-36, NDK `29.0.14206865`, build-tools `37.0.0`,
CMake `4.1.2`, SDK command-line-tools `14742923_latest`. The SDK setup also fetches
`FFmpeg/gas-preprocessor` from moving `master`; preserve the actual script with a checksum
and revision when preparing reproducible instructions. System packages and Meson are not
fully pinned by upstream CI, so this recipe alone does not promise byte-identical rebuilds.

## Licence correction

The wrapper's MIT licence does not describe its bundled native closure. The pinned
[FFmpeg recipe](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/scripts/ffmpeg.sh)
uses `--enable-{gpl,version3}`. The
[mpv recipe](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/scripts/mpv.sh)
does not disable GPL. Therefore the previous claim that dropping NextLib leaves an
LGPL-only mpv path was unsupported. Retain upstream COPYING/LICENSE/NOTICE files throughout
the dependency closure and reflect the actual build configuration in the public notices.

## Remaining release evidence

1. Source collection is complete for the explicit upstream fetch recipes (see staging result
   below). Validate that these sources and retained recipes configure/build the exact native
   closure. Archive inventory and checksums alone are insufficient to claim that gate passed.
2. Inspect the new signed candidate APK's native entries and hashes against both resolved AARs.
   The historical candidate mapping is recorded below.
   `:app` uses pick-first for conflicting FFmpeg SONAMEs; `:tvplay` excludes mpv-only files.
   Dependency declarations alone do not prove which native closure ships.
3. Preserve the exact application source revision and complete build inputs alongside
   native sources. An APK rebuild against Maven binaries establishes app reproducibility,
   not a successful native rebuild from the supplied source closure.
4. Rebuild native artifacts using the supplied recipes, or record the explicit limits of
   upstream provenance and verify source-to-binary identification. No native rebuild was
   attempted in this bounded audit, and no complete-closure gate has passed.

## Source staging result

The release handoff's `native-source-staging/` now contains 18 source archives: both wrapper
repositories, 14 direct native source archives, and recursive Mbed TLS 3.6.6/libplacebo
archives. `native-source-manifest.json` records source URLs, SHA-256 hashes, archive entry
counts, licence paths, and recursive git revisions. `SHA256SUMS` covers staged artifacts.
The seven submodule revisions were checked out successfully, with no unresolved entries:

| Parent/path | Revision |
|---|---|
| Mbed TLS/framework | `dff9da04438d712f7647fd995bc90fadd0c0e2ce` |
| libplacebo/3rdparty/Vulkan-Headers | `450bd2232225d6c7728a4108055ac2e37cef6475` |
| libplacebo/3rdparty/fast_float | `97b54ca9e75f5303507699d27c6b4f4efe4641a1` |
| libplacebo/3rdparty/glad | `73db193f853e2ee079bf3ca8a64aa2eaf6459043` |
| libplacebo/3rdparty/jinja | `15206881c006c79667fe5154fe80c01c65410679` |
| libplacebo/3rdparty/markupsafe | `297fc8e356e6836a62087949245d09a28e9f1b13` |
| libplacebo/demos/3rdparty/nuklear | `242f35efa067a46c595645eeda7b1771ea1f83b1` |

The current gas-preprocessor revision `ac1836309c2e77023c228b7184485597286289d3` is
staged explicitly as a present-day build helper; the original publication's moving-master
revision remains unverified. libmpv's `buildscripts/build.sh` generates a cross-file with
`wrap_mode = 'nodownload'`, so optional Meson wrap files do not independently authorize
fetching additional dependency versions. The downloaded source inventory matches the
explicit fetch recipes, including recursive submodules. This advances source collection;
it does not pass native build verification or APK source-to-binary mapping.

## Historical APK native mapping

The coordinating task supplied `historical-apk-native-map.json`, inspected in this audit.
For APK SHA-256 `70b159d4d1446abba76a09c999a64e1e660ae2ca50dc1f162fec986525adedf6`,
all 22 NextLib/libmpv native entries across arm64-v8a and armeabi-v7a hash-match the
resolved Maven AAR entries. FFmpeg codec/util/resample/scale and media3ext come from
NextLib; mpv/player, FFmpeg format/device/filter and libc++ come from libmpv. The two
remaining `libandroidx.graphics.path.so` entries are outside this bounded two-library
audit. This identifies the historical APK's binary inputs; mapping the newly signed
release candidate and verifying native rebuilds remain outstanding.

## Updated candidate evidence

The license-complete 0.1.41/code42 compatibility APK has SHA-256
`b14af1cc340e5ebab60b70e7d4dfbaba5b6307f3171a0e414d8d0833d262c21e`.
All 22 NextLib/libmpv native entries match the audited Maven AAR bytes; the two remaining
native entries are AndroidX graphics-path libraries. The refreshed APK was installed on the test receiver
with matching remote base.apk hash, and the generated H.264/AAC rapid-seek regression passed.
This is runtime evidence for that tested path, not a native source rebuild or universal codec test.
