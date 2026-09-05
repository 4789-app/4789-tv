#!/usr/bin/env python3
"""Fail unless every supplied ELF library supports 16 KiB load-page alignment."""

from __future__ import annotations

import struct
import sys
from pathlib import Path


MIN_ALIGNMENT = 16 * 1024
PT_LOAD = 1


def load_alignments(path: Path) -> list[int]:
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = data[5]
    if byte_order != 1:
        raise ValueError("only little-endian Android ELF files are supported")

    if elf_class == 2:
        ph_offset = struct.unpack_from("<Q", data, 32)[0]
        ph_entry_size = struct.unpack_from("<H", data, 54)[0]
        ph_count = struct.unpack_from("<H", data, 56)[0]
        type_offset, align_offset = 0, 48
        align_format = "<Q"
    elif elf_class == 1:
        ph_offset = struct.unpack_from("<I", data, 28)[0]
        ph_entry_size = struct.unpack_from("<H", data, 42)[0]
        ph_count = struct.unpack_from("<H", data, 44)[0]
        type_offset, align_offset = 0, 28
        align_format = "<I"
    else:
        raise ValueError(f"unsupported ELF class {elf_class}")

    alignments: list[int] = []
    for index in range(ph_count):
        entry = ph_offset + index * ph_entry_size
        if entry + ph_entry_size > len(data):
            raise ValueError("truncated program-header table")
        program_type = struct.unpack_from("<I", data, entry + type_offset)[0]
        if program_type == PT_LOAD:
            alignments.append(struct.unpack_from(align_format, data, entry + align_offset)[0])
    if not alignments:
        raise ValueError("ELF file has no load segments")
    return alignments


def main(arguments: list[str]) -> int:
    if not arguments:
        print("usage: check-elf-page-alignment.py <library.so> [...]", file=sys.stderr)
        return 2
    failed = False
    for raw_path in arguments:
        path = Path(raw_path)
        try:
            alignments = load_alignments(path)
        except (OSError, ValueError, struct.error) as error:
            print(f"FAIL {path}: {error}", file=sys.stderr)
            failed = True
            continue
        too_small = [alignment for alignment in alignments if alignment < MIN_ALIGNMENT]
        if too_small:
            rendered = ", ".join(str(value) for value in too_small)
            print(f"FAIL {path}: load alignment below 16384 ({rendered})", file=sys.stderr)
            failed = True
        else:
            print(f"PASS {path}: min load alignment {min(alignments)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
