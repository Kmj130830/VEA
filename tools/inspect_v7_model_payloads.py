#!/usr/bin/env python3
"""Static inspection of user-supplied VOCALOID7 model payloads; never loads or executes them."""
from __future__ import annotations

import hashlib
import json
import math
import re
import struct
import zipfile
import zlib
from collections import Counter
from pathlib import Path

ARCHIVES = (Path("AI Model.zip"), Path("megpoidV7.zip"))
TARGET_SUFFIXES = {".vtb2", ".vpit", ".vhnv", ".vtmg", ".vmanifest"}
MAGIC_MARKERS = {
    "zlib": (b"\x78\x01", b"\x78\x5e", b"\x78\x9c", b"\x78\xda"),
    "gzip": (b"\x1f\x8b",),
    "zstd": (b"\x28\xb5\x2f\xfd",),
    "lz4-frame": (b"\x04\x22\x4d\x18",),
    "zip-local": (b"PK\x03\x04",),
    "sqlite": (b"SQLite format 3",),
    "protobuf-empty": (),
}

def shannon(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    n = len(data)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())

def printable_ascii(data: bytes, limit: int = 100) -> list[dict]:
    out = []
    for m in re.finditer(rb"[\x20-\x7e]{6,}", data):
        out.append({"offset": m.start(), "text": m.group(0).decode("ascii", "replace")[:240]})
        if len(out) >= limit:
            break
    return out

def printable_utf16le(data: bytes, limit: int = 80) -> list[dict]:
    out = []
    for m in re.finditer(rb"(?:[\x20-\x7e]\x00){5,}", data):
        raw = m.group(0)
        out.append({"offset": m.start(), "text": raw.decode("utf-16le", "replace")[:240]})
        if len(out) >= limit:
            break
    return out


def compressed_streams(data: bytes, max_candidates: int = 256) -> list[dict]:
    """Return only embedded zlib/gzip streams that successfully reach EOF."""
    candidates = []
    markers = [
        ("gzip", bytes((0x1f, 0x8b)), 31),
        ("zlib", bytes((0x78, 0x01)), 15),
        ("zlib", bytes((0x78, 0x5e)), 15),
        ("zlib", bytes((0x78, 0x9c)), 15),
        ("zlib", bytes((0x78, 0xda)), 15),
    ]
    for label, marker, wbits in markers:
        pos = 0
        while len(candidates) < max_candidates:
            pos = data.find(marker, pos)
            if pos < 0:
                break
            candidates.append((pos, label, wbits))
            pos += 1
    results = []
    seen = set()
    for offset, label, wbits in sorted(candidates):
        if (offset, wbits) in seen:
            continue
        seen.add((offset, wbits))
        try:
            decoder = zlib.decompressobj(wbits)
            raw = data[offset:]
            decoded = decoder.decompress(raw, 32 * 1024 * 1024)
            if not decoder.eof:
                continue
            decoded += decoder.flush()
            consumed = len(raw) - len(decoder.unused_data)
            results.append({
                "offset": offset,
                "kind": label,
                "compressed_bytes": consumed,
                "decompressed_bytes": len(decoded),
                "sha256": hashlib.sha256(decoded).hexdigest(),
                "entropy": round(shannon(decoded), 5),
                "prefix_hex_64": decoded[:64].hex(" "),
                "ascii_strings": printable_ascii(decoded, limit=8),
                "utf8_preview": decoded[:256].decode("utf-8", "replace")
                    if decoded[:1] in (bytes((0x7b,)), bytes((0x5b,)), bytes((0x3c,))) else None,
            })
        except (zlib.error, ValueError, MemoryError):
            continue
    return results

def float_summary(data: bytes) -> dict:
    usable = data[: min(len(data) - len(data) % 4, 1_000_000)]
    if not usable:
        return {}
    vals = struct.unpack("<" + "f" * (len(usable) // 4), usable)
    finite = [x for x in vals if math.isfinite(x)]
    plausible = [x for x in finite if abs(x) <= 1.0e6]
    return {
        "sampled_float32_count": len(vals),
        "finite_count": len(finite),
        "plausible_abs_le_1e6_count": len(plausible),
        "finite_min": min(finite) if finite else None,
        "finite_max": max(finite) if finite else None,
        "finite_abs_le_1e6_min": min(plausible) if plausible else None,
        "finite_abs_le_1e6_max": max(plausible) if plausible else None,
    }

def inspect_member(archive_name: str, member: str, data: bytes) -> dict:
    block = 4096
    entropies = [round(shannon(data[i:i+block]), 4) for i in range(0, len(data), block)]
    marker_hits = {}
    for label, markers in MAGIC_MARKERS.items():
        positions = []
        for marker in markers:
            pos = 0
            while True:
                pos = data.find(marker, pos)
                if pos < 0:
                    break
                positions.append(pos)
                pos += 1
                if len(positions) >= 32:
                    break
        if positions:
            marker_hits[label] = sorted(set(positions))[:32]

    head_ints = []
    for offset in range(0, min(len(data), 128) - 3, 4):
        head_ints.append({"offset": offset, "u32le": struct.unpack_from("<I", data, offset)[0]})

    entry = {
        "archive": archive_name,
        "member": member,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "header_hex_256": data[:256].hex(" "),
        "entropy_global": round(shannon(data), 5),
        "entropy_4k_min": min(entropies) if entropies else None,
        "entropy_4k_median": sorted(entropies)[len(entropies)//2] if entropies else None,
        "entropy_4k_max": max(entropies) if entropies else None,
        "magic_marker_offsets": marker_hits,
        "valid_compressed_streams": compressed_streams(data),
        "first_u32le": head_ints,
        "float32_sample": float_summary(data),
        "ascii_strings": printable_ascii(data),
        "utf16le_strings": printable_utf16le(data),
    }
    if member.lower().endswith(".vmanifest"):
        try:
            entry["manifest_text"] = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            entry["manifest_text"] = None
    return entry

def main() -> None:
    output = Path("build/model-payload-analysis")
    output.mkdir(parents=True, exist_ok=True)
    reports = []
    for archive_path in ARCHIVES:
        if not archive_path.exists():
            continue
        with zipfile.ZipFile(archive_path) as zf:
            for info in zf.infolist():
                if info.is_dir() or Path(info.filename).suffix.lower() not in TARGET_SUFFIXES:
                    continue
                data = zf.read(info)
                reports.append(inspect_member(archive_path.name, info.filename, data))

    payload = {"scope": "static inspection only", "files": reports}
    (output / "model-payloads.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        "# VOCALOID7 model payload static inspection",
        "",
        "> ZIP members were read as byte arrays only. No supplied model or executable was loaded.",
        "",
    ]
    for item in reports:
        lines.extend([
            f"## {item['archive']}!/{item['member']}",
            "",
            f"- Size: {item['size']} bytes",
            f"- SHA-256: {item['sha256']}",
            f"- Header (256 bytes): `{item['header_hex_256']}`",
            f"- Shannon entropy: {item['entropy_global']} bits/byte",
            f"- 4 KiB entropy min/median/max: {item['entropy_4k_min']} / {item['entropy_4k_median']} / {item['entropy_4k_max']}",
            f"- Detected marker offsets: `{json.dumps(item['magic_marker_offsets'], sort_keys=True)}`",
            f"- Float32 sample: `{json.dumps(item['float32_sample'], sort_keys=True)}`",
            "",
            "### Printable strings",
            "",
        ])
        for string in item["ascii_strings"]:
            lines.append(f"- offset 0x{string['offset']:x}: `{string['text']}`")
        for string in item["utf16le_strings"]:
            lines.append(f"- UTF16LE offset 0x{string['offset']:x}: `{string['text']}`")
        for stream in item["valid_compressed_streams"]:
            lines.append(
                "Valid " + stream["kind"] + " stream at 0x" + format(stream["offset"], "x") +
                ": " + str(stream["compressed_bytes"]) + " bytes -> " +
                str(stream["decompressed_bytes"]) + " bytes; sha256=" + stream["sha256"] +
                "; entropy=" + str(stream["entropy"])
            )
            for string in stream["ascii_strings"]:
                lines.append("  decompressed +0x" + format(string["offset"], "x") + ": " + string["text"])
        lines.append("")
        if item.get("manifest_text"):
            lines.extend(["### Manifest", "", "~~~json", item["manifest_text"], "~~~", ""])
    (output / "MODEL_PAYLOADS.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Inspected {len(reports)} model payloads.")
    for item in reports:
        print(f"{item['member']}: {item['size']} bytes sha256={item['sha256']} entropy={item['entropy_global']}")

if __name__ == "__main__":
    main()
