#!/usr/bin/env python3
"""Decode and inspect the supplied VOCALOID7 S5API model body (offline only)."""
from __future__ import annotations

import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

BANK_ARCHIVE = Path("megpoidV7.zip")
TARGET = "AI_Megpoid.vtb2"
EXPECTED_SHA256 = "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a"
MAGICS = {b"VTMB", b"VTBR", b"VTMG", b"VPIT", b"VNPV", b"VHNV"}
MASK32 = 0xFFFFFFFF


def decode_payload(file_bytes: bytes) -> tuple[bytes, dict[str, int | str]]:
    if len(file_bytes) < 16:
        raise ValueError("container shorter than 16-byte prefix")
    magic = file_bytes[:4]
    if magic not in MAGICS:
        raise ValueError(f"unsupported S5API magic {magic!r}")

    declared_span, format_word, seed = struct.unpack_from("<III", file_bytes, 4)
    end = declared_span + 8
    if end < 16 or len(file_bytes) < end:
        end = len(file_bytes)
    payload_size = end - 16
    if payload_size < 4 and payload_size:
        raise ValueError("S5API tail transform has no initialized full-word state")

    encrypted = memoryview(file_bytes)[16:end]
    word_count, remainder = divmod(payload_size, 4)
    output = bytearray(payload_size)

    state = seed
    stream_word = 0x05491333
    state_a = 0x159A55E5
    state_b = 0x1F123BB5

    for word in range(word_count):
        previous_stream = stream_word
        state = ((state << 11) ^ state) & MASK32
        stream_word = (
            previous_stream
            ^ ((((previous_stream >> 11) ^ state) >> 8) & MASK32)
            ^ state
        ) & MASK32
        offset = word * 4
        cipher_word = int.from_bytes(encrypted[offset:offset + 4], "little")
        output[offset:offset + 4] = (cipher_word ^ stream_word).to_bytes(4, "little")

        last_uvar5 = state_a
        state = last_uvar5
        state_a = state_b
        state_b = previous_stream

    if remainder:
        tail_offset = word_count * 4
        tail_word = int.from_bytes(encrypted[tail_offset:], "little")
        folded_state = ((state << 11) ^ state) & MASK32
        mixed = (
            ((((stream_word >> 11) ^ folded_state) >> 8) & MASK32)
            ^ folded_state ^ stream_word ^ state ^ tail_word
        ) & MASK32
        output[tail_offset:] = mixed.to_bytes(4, "little")[:remainder]

    metadata = {
        "magic": magic.decode("ascii"),
        "declared_span": declared_span,
        "format_word": format_word,
        "seed": seed,
        "file_size": len(file_bytes),
        "decoded_size": payload_size,
        "untouched_trailing_bytes": len(file_bytes) - end,
        "sha256": hashlib.sha256(file_bytes).hexdigest(),
    }
    return bytes(output), metadata


def walk_json(value, path="$", depth=0, results=None):
    if results is None:
        results = {"object_paths": [], "array_paths": [], "strings": []}
    if depth > 48:
        return results
    if isinstance(value, dict):
        results["object_paths"].append((path, len(value), list(value.keys())[:40]))
        for key, child in value.items():
            results["strings"].append((f"{path}.[key]", str(key)[:256]))
            walk_json(child, f"{path}.{key}", depth + 1, results)
    elif isinstance(value, list):
        results["array_paths"].append((path, len(value)))
        for index, child in enumerate(value[:2000]):
            walk_json(child, f"{path}[{index}]", depth + 1, results)
    elif isinstance(value, str):
        results["strings"].append((path, value[:256]))
    return results


def main():
    if not BANK_ARCHIVE.is_file():
        raise SystemExit(f"required archive missing: {BANK_ARCHIVE}")
    with zipfile.ZipFile(BANK_ARCHIVE) as archive:
        matches = [name for name in archive.namelist() if name.rsplit("/", 1)[-1] == TARGET]
        if len(matches) != 1:
            raise SystemExit(f"expected one {TARGET}, found {len(matches)}")
        original = archive.read(matches[0])

    if hashlib.sha256(original).hexdigest() != EXPECTED_SHA256:
        raise SystemExit(f"{TARGET} SHA-256 does not match the VEA catalog")

    decoded, info = decode_payload(original)
    output_dir = Path("build/s5-container-hash/decoded")
    output_dir.mkdir(parents=True, exist_ok=True)
    decoded_path = output_dir / (TARGET + ".payload")
    decoded_path.write_bytes(decoded)

    report = [
        "# AI Megpoid VTB2 decoded-body inspection",
        "",
        "> Offline inspection of the user-supplied model archive. The model is not executed.",
        "",
        "## Prefix and transform",
        "",
    ]
    report += [f"- {key}: {value}" for key, value in info.items()]
    report += [
        "",
        f"- decoded payload file: `{decoded_path.as_posix()}`",
        f"- initial 64 bytes: `{decoded[:64].hex()}`",
        f"- UTF-8 valid: {True if _is_utf8(decoded) else False}",
        "",
    ]

    text = decoded.decode("utf-8", "replace")
    printable_ratio = sum(ch.isprintable() or ch in "\r\n\t" for ch in text[:200000]) / max(1, min(len(text), 200000))
    report += [f"- printable UTF-8 ratio (first 200 KB): {printable_ratio:.4f}", ""]
    leading = text.lstrip("\ufeff\x00 \t\r\n")
    report += ["## JSON-like body", ""]
    try:
        parsed = json.loads(leading.rstrip("\x00"))
        report.append("- JSON parse: **success**")
        report.append(f"- root type: {type(parsed).__name__}")
        if isinstance(parsed, dict):
            report.append(f"- root keys ({len(parsed)}): {', '.join(str(k) for k in list(parsed.keys())[:100])}")
        tree = walk_json(parsed)
        report.append(f"- objects visited: {len(tree['object_paths'])}")
        report.append(f"- arrays visited: {len(tree['array_paths'])}")
        report.append(f"- strings visited: {len(tree['strings'])}")
        report += ["", "## Tensor/model-key strings", ""]
        pattern = re.compile(r"(?i)(rnn|weight_ih|weight_hh|bias_ih|bias_hh|pre[12]|conv[123]|cond[123]|linear|\*F16|\*F32|\*S32)")
        matched = [(path, value) for path, value in tree["strings"] if pattern.search(value)]
        report.append(f"- matching strings: {len(matched)}")
        for path, value in matched[:500]:
            report.append(f"- `{path}`: `{value[:160]}`")
        report += ["", "## Top-level object paths", ""]
        for path, count, keys in tree["object_paths"][:100]:
            report.append(f"- `{path}` ({count} keys): {', '.join(map(str, keys))}")
    except Exception as error:
        report.append(f"- JSON parse: failed ({type(error).__name__}: {error})")
        report.append(f"- first 512 decoded bytes (escaped): `{decoded[:512]!r}`")
        report.append("")
        report.append("## Candidate model/tensor strings")
        report.append("")
        matches = sorted(set(re.findall(r"[A-Za-z0-9_.*:+/-]{4,120}", text)))
        for value in [s for s in matches if pattern_search(s)][:400]:
            report.append(f"- `{value}`")

    report_path = Path("build/s5-container-hash/AI_MEGPOID_VTB2_DECODED_INSPECTION.md")
    report_path.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report[:35]))
    print(f"inspection report written to {report_path}")


def _is_utf8(data: bytes) -> bool:
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def pattern_search(value: str) -> bool:
    return bool(re.search(r"(?i)(rnn|weight_ih|weight_hh|bias_ih|bias_hh|pre[12]|conv[123]|cond[123]|linear|\*F16|\*F32|\*S32)", value))


if __name__ == "__main__":
    main()
