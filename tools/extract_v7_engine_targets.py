#!/usr/bin/env python3
"""Extract the VOCALOID7 render-side DLLs for static analysis only."""
from __future__ import annotations

import hashlib
import zipfile
from pathlib import Path

ARCHIVE = Path("Editor dll.zip")
OUTPUT = Path("build/v7-engine-targets")
TARGETS = ("VOCALOID7.dll", "WaveOfflineRenderer.dll")
IMAGE_FILE_MACHINE_AMD64 = 0x8664


def validate_pe_amd64(data: bytes, name: str) -> None:
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise SystemExit(f"Invalid DOS/PE header: {name}")
    pe_offset = int.from_bytes(data[0x3C:0x40], "little")
    if pe_offset + 6 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise SystemExit(f"Invalid PE signature: {name}")
    machine = int.from_bytes(data[pe_offset + 4:pe_offset + 6], "little")
    if machine != IMAGE_FILE_MACHINE_AMD64:
        raise SystemExit(f"Expected PE AMD64 (0x8664), got 0x{machine:04x}: {name}")


def main() -> None:
    if not ARCHIVE.is_file():
        raise SystemExit(f"Missing input archive: {ARCHIVE}")
    OUTPUT.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(ARCHIVE) as archive:
        bad_member = archive.testzip()
        if bad_member is not None:
            raise SystemExit(f"ZIP CRC verification failed: {bad_member}")
        available = set(archive.namelist())
        missing = [name for name in TARGETS if name not in available]
        if missing:
            raise SystemExit("Missing expected DLL(s): " + ", ".join(missing))

        manifest: list[str] = []
        for name in TARGETS:
            data = archive.read(name)
            validate_pe_amd64(data, name)
            destination = OUTPUT / name
            destination.write_bytes(data)
            digest = hashlib.sha256(data).hexdigest()
            line = f"{digest}  {name}  {len(data)} bytes"
            manifest.append(line)
            print(line)

    (OUTPUT / "SHA256SUMS.txt").write_text(
        "\n".join(manifest) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
