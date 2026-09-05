#!/usr/bin/env python3
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]

def png_size(path: Path) -> tuple[int, int, int]:
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"not PNG: {path}"
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    return width, height, color_type

def require_opaque(path: Path) -> None:
    _, _, color_type = png_size(path)
    assert color_type in (2, 3), f"Play artwork must not have an alpha channel: {path}"

required = {
    ROOT / "play-store/shared/app-icon-512.png": (512, 512),
    ROOT / "play-store/phone/feature-graphic-1024x500.png": (1024, 500),
    ROOT / "play-store/tv/feature-graphic-1024x500.png": (1024, 500),
}
for path, expected in required.items():
    width, height, color_type = png_size(path)
    assert (width, height) == expected, f"wrong dimensions: {path}"
    require_opaque(path)

phone = sorted((ROOT / "play-store/phone/screenshots").glob("*.png"))
tv = sorted((ROOT / "play-store/tv/screenshots").glob("*.png"))
assert len(phone) >= 2, "phone needs at least two screenshots"
assert len(tv) >= 1, "TV needs at least one screenshot"
for path in phone + tv:
    width, height, _ = png_size(path)
    assert 320 <= min(width, height) and max(width, height) <= 3840
    assert max(width, height) <= 2 * min(width, height), f"invalid Play screenshot aspect: {path}"
    require_opaque(path)

text = (ROOT / "play-store/README.md").read_text()
for package in ("com.fourseveneightnine.phone", "com.fourseveneightnine.tv.play"):
    assert package in text
for prohibited in ("#1", "best app", "download now", "install now"):
    assert prohibited.lower() not in text.lower()
print(f"Play store package passed: {len(phone)} phone screenshots, {len(tv)} TV screenshots")
