#!/usr/bin/env python3
"""Statically verify the recovered S5API model-payload byte transform.

Reads the supplied ZIP members as data only. It never runs vendor code, writes
model payloads to disk, or prints parameter/tensor contents.
"""
from __future__ import annotations

import json
import struct
import zipfile
from pathlib import Path

MAGICS = {b"VTMB", b"VTBR", b"VTMG", b"VPIT", b"VPNV", b"VHNV"}
MASK32 = 0xFFFFFFFF


def read_u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def xorshift11(value: int) -> int:
    return (((value << 11) & MASK32) ^ value) & MASK32


def decode_payload(data: bytes) -> bytes:
    """Port FUN_18002aa90 for recognized V7 model chunk magics."""
    if len(data) < 16:
        return b""
    if data[:4] not in MAGICS:
        return data

    end = read_u32(data, 4) + 8
    if end < 16 or len(data) < end:
        end = len(data)
    output_size = end - 16
    word_count, tail_count = divmod(output_size, 4)

    seed = read_u32(data, 12)
    state_a = 0x159A55E5
    state_b = 0x1F123BB5
    state_c = 0x05491333
    out = bytearray(output_size)
    last_seed_source = seed

    for index in range(word_count):
        last_seed_source = state_a
        previous_c = state_c
        stepped_seed = xorshift11(seed)
        state_c = (
            previous_c
            ^ (((previous_c >> 11) ^ stepped_seed) >> 8)
            ^ stepped_seed
        ) & MASK32
        cipher_word = read_u32(data, 16 + index * 4)
        struct.pack_into("<I", out, index * 4, state_c ^ cipher_word)

        seed = state_a
        state_a = state_b
        state_b = previous_c

    if tail_count:
        tail_offset = 16 + word_count * 4
        packed_tail = 0
        for index in range(tail_count):
            packed_tail |= data[tail_offset + index] << (index * 8)

        tail_state = xorshift11(last_seed_source)
        mixed = (
            ((((state_c >> 11) ^ tail_state) >> 8)
             ^ tail_state ^ state_c ^ packed_tail)
            & MASK32
        )
        for index in range(tail_count):
            out[word_count * 4 + index] = (mixed >> (index * 8)) & 0xFF

    return bytes(out)


def try_json_object(payload: bytes) -> dict | None:
    # The native JSON parser receives a byte range, not necessarily a
    # NUL-terminated C string. Permit only trailing JSON whitespace/NUL padding.
    candidates = [payload]
    trimmed = payload.rstrip(b"\x00 \t\r\n")
    if trimmed != payload:
        candidates.append(trimmed)
    for candidate in candidates:
        try:
            text = candidate.decode("utf-8-sig", errors="strict")
            value = json.loads(text)
            if isinstance(value, dict):
                return value
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    return None


def inspect_archive(path: Path) -> int:
    if not path.is_file():
        print("MISSING_ARCHIVE", path)
        return 0

    recovered = 0
    with zipfile.ZipFile(path) as archive:
        bad_member = archive.testzip()
        if bad_member:
            raise SystemExit("ZIP CRC failure: " + bad_member)

        for item in archive.infolist():
            if item.is_dir():
                continue
            data = archive.read(item)
            if data[:4] not in MAGICS:
                continue

            decoded = decode_payload(data)
            root = try_json_object(decoded)
            if root is not None:
                keys = list(root.keys())
                sample = [str(key)[:32] for key in keys[:4]]
                print(
                    "JSON_OK",
                    f"archive={path.name}!/{item.filename}",
                    f"magic={data[:4].decode('ascii')}",
                    f"file_bytes={len(data)}",
                    f"decoded_bytes={len(decoded)}",
                    f"root_entries={len(root)}",
                    f"key_sample={sample}",
                )
                recovered += 1
            else:
                prefix = decoded[:24]
                safe = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in prefix)
                print(
                    "JSON_NOT_CONFIRMED",
                    f"archive={path.name}!/{item.filename}",
                    f"magic={data[:4].decode('ascii')}",
                    f"file_bytes={len(data)}",
                    f"decoded_bytes={len(decoded)}",
                    f"decoded_prefix_hex={prefix.hex()}",
                    f"decoded_prefix_ascii={safe}",
                )
    return recovered


def main() -> None:
    total = 0
    for archive in (Path("megpoidV7.zip"), Path("AI Model.zip")):
        total += inspect_archive(archive)
    print("S5_JSON_OBJECTS_RECOVERED", total)


if __name__ == "__main__":
    main()
