#!/usr/bin/env python3
"""Static inventory of voice-engine archives. Never extracts or executes archive content."""
from __future__ import annotations

import hashlib
import io
import os
import zipfile
from collections import Counter
from pathlib import Path

ARCHIVES = [
    Path("Editor dll.zip"),
    Path("AI Model.zip"),
    Path("megpoidV7.zip"),
]
TEXT_SUFFIXES = {
    ".txt", ".md", ".json", ".xml", ".yaml", ".yml", ".ini", ".cfg",
    ".config", ".manifest", ".properties", ".csv", ".toml", ".nuspec",
    ".csproj", ".props", ".targets", ".bat", ".cmd", ".ps1", ".sh",
}
MAX_TEXT_BYTES = 24000
MAX_NESTED_ARCHIVE_BYTES = 120 * 1024 * 1024


def describe_magic(data: bytes) -> str:
    if data.startswith(b"MZ"):
        return "PE/DOS header (Windows executable or DLL candidate)"
    if data.startswith(b"\x7fELF"):
        return "ELF (Linux/Android native binary candidate)"
    if data.startswith(b"PK\x03\x04"):
        return "ZIP archive"
    if data.startswith(b"\x89HDF\r\n\x1a\n"):
        return "HDF5"
    if data.startswith(b"SQLite format 3"):
        return "SQLite database"
    if data.startswith(b"\x00asm"):
        return "WebAssembly"
    return data[:12].hex(" ") if data else "empty"


def inspect_zip(zf: zipfile.ZipFile, label: str, depth: int = 0) -> None:
    infos = [i for i in zf.infolist() if not i.is_dir()]
    extensions = Counter(Path(i.filename).suffix.lower() or "[no extension]" for i in infos)
    print(f"\n=== ARCHIVE {label} (depth={depth}) ===")
    print(f"files={len(infos)} total_uncompressed={sum(i.file_size for i in infos)} total_compressed={sum(i.compress_size for i in infos)}")
    print("EXTENSIONS " + ", ".join(f"{k}:{v}" for k, v in extensions.most_common()))
    for info in infos:
        print(f"FILE {info.file_size:>12} bytes | {info.compress_size:>12} packed | {info.filename}")
    metadata_candidates = [
        i for i in infos
        if Path(i.filename).suffix.lower() in TEXT_SUFFIXES
        and i.file_size <= MAX_TEXT_BYTES
    ]
    for info in metadata_candidates[:80]:
        try:
            raw = zf.read(info)
            text = raw.decode("utf-8-sig", errors="replace")
            print(f"\n--- TEXT {label}!/{info.filename} ({info.file_size} bytes) ---")
            print(text[:MAX_TEXT_BYTES])
        except Exception as exc:
            print(f"TEXT_READ_FAILED {info.filename}: {type(exc).__name__}: {exc}")
    if depth >= 2:
        return
    for info in infos:
        name = info.filename.lower()
        if (name.endswith(".zip") or name.endswith(".jar") or name.endswith(".nupkg")) and info.file_size <= MAX_NESTED_ARCHIVE_BYTES:
            try:
                nested = zf.read(info)
                with zipfile.ZipFile(io.BytesIO(nested)) as inner:
                    inspect_zip(inner, f"{label}!/{info.filename}", depth + 1)
            except Exception as exc:
                print(f"NESTED_ARCHIVE_FAILED {info.filename}: {type(exc).__name__}: {exc}")


def main() -> None:
    for path in ARCHIVES:
        if not path.exists():
            print(f"MISSING {path}")
            continue
        data = path.read_bytes()
        print(f"\n### TOP LEVEL: {path}")
        print(f"size={len(data)} sha256={hashlib.sha256(data).hexdigest()} magic={describe_magic(data[:32])}")
        try:
            with zipfile.ZipFile(path) as zf:
                bad = zf.testzip()
                print(f"ZIP_TEST={'OK' if bad is None else 'BAD:' + bad}")
                inspect_zip(zf, str(path))
        except Exception as exc:
            print(f"ZIP_OPEN_FAILED {type(exc).__name__}: {exc}")


if __name__ == "__main__":
    main()
