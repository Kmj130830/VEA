#!/usr/bin/env python3
"""Extract only the explicitly listed G2P PE files for static Ghidra analysis.

No extracted executable is launched, loaded by the host OS, or copied into the APK.
"""
from __future__ import annotations

import hashlib
import zipfile
from pathlib import Path

ARCHIVE = Path("Editor dll.zip")
OUTPUT = Path("build/g2p-targets")
TARGETS = (
    "G2PAManager.dll",
    "G2PAManager_ENG.dll",
    "G2PAManager_JPN.dll",
    "G2PAManager_KOR.dll",
    "G2PAManager_CHS.dll",
    "G2PA_ESP.dll",
)


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

        lines: list[str] = []
        for name in TARGETS:
            data = archive.read(name)
            if not data.startswith(b"MZ"):
                raise SystemExit(f"Expected PE/MZ file, got invalid header: {name}")
            destination = OUTPUT / name
            destination.write_bytes(data)
            digest = hashlib.sha256(data).hexdigest()
            lines.append(f"{digest}  {name}  {len(data)} bytes")
            print(lines[-1])

    (OUTPUT / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
