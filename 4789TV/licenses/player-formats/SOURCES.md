# Player format artwork

Official artwork retrieved for the player prototype on 2026-11-16. Native PNG copies are rendered from the original SVG where supplied, with transparent canvas padding trimmed. Logo geometry is preserved. The player retains the AV1, HDR10+ and FLAC artwork colors; single-color marks use its existing monochrome tint. This preserves AV1’s dark numeral over the gray triangle. These marks identify source metadata, not certified receiver output.

- IMAX: https://brand.imax.com/wp-content/themes/your-theme/assets/images/logo.svg (source SHA-256 `e50f9ca432b5c118824b15b98d4d7eaa920e14eca70371094b343212e5bb9079`).
- HDR10+: https://hdr10plus.org/wp-content/uploads/2023/10/cropped-header_logo.png (source SHA-256 `b179e72d889f23eec7cfbb471d8df1d8312839fa034ac387edcbeffd9219acff`).
- AV1: https://aomedia.org/assets/images/aomedia-logo-resources/av1_logo_white.svg (source SHA-256 `f189337c7f2d19a17d7ccb73388e5fadefdfaffaa850c56678cfb2f19f684630`).
- DTS: https://images.ctfassets.net/c4ucztjx9pmu/2Px18nPyUXhKcRjxvXcQbc/0179a2325bd05473b308f39379fbb3c5/logo11.png (source SHA-256 `e1626211bfd3184504b9760de7bcce115d5a5d0f7f6a5d0d6d15f6ba82ddbe91`).
- Opus: https://opus-codec.org/assets/img/opus-logo.svg (source SHA-256 `ab2480e12b99807f8124fec7bf218ac8a41995b0ba7d51fef4100a21b2bbb2e4`).
- FLAC: https://raw.githubusercontent.com/xiph/flac/master/doc/images/logo.svg (source SHA-256 `ee6c4bb1202576a7ec339bed17fa7f05e0452c9d2244976ef0b6ec273177c590`).

Dolby Atmos and Dolby Vision reuse the unchanged originals documented in `../dolby/SOURCES.md`.
1080p, 720p and 4K badges are app graphics, with Inter SemiBold glyph outlines from the existing bundled OFL font. They are native vector drawables matching the approved SVG badge. Generic HEVC/HLG/audio labels use the same app font and are not presented as official brand artwork.

The FLAC SVG’s full-canvas black background rectangle (`rect2987`) is omitted before rendering so the native monochrome tint preserves a transparent mark instead of producing a solid box. Letterforms and equalizer geometry are unchanged.
