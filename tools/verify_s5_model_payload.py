#!/usr/bin/env python3
"""Statically verify the recovered S5API model-payload byte transform.

Reads the supplied ZIP members as data only. It never runs vendor code, writes
model payloads to disk, or prints parameter/tensor contents.
"""
from __future__ import annotations

import argparse
import ctypes
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


class NativeDecoder:
    """Call the independently compiled Android-port C ABI for byte comparison."""

    def __init__(self, library_path: Path) -> None:
        self.library = ctypes.CDLL(str(library_path))
        self.decode = self.library.vea_s5_decode_model_payload
        byte_pointer = ctypes.POINTER(ctypes.c_uint8)
        self.decode.argtypes = [
            byte_pointer,
            ctypes.c_size_t,
            byte_pointer,
            ctypes.c_size_t,
            ctypes.POINTER(ctypes.c_size_t),
        ]
        self.decode.restype = ctypes.c_int

    def __call__(self, data: bytes) -> bytes:
        source_type = ctypes.c_uint8 * len(data)
        source = source_type.from_buffer_copy(data) if data else None
        source_pointer = ctypes.cast(source, ctypes.POINTER(ctypes.c_uint8)) if source else None

        required = ctypes.c_size_t()
        status = self.decode(source_pointer, len(data), None, 0, ctypes.byref(required))
        if status not in (0, 4):
            raise RuntimeError("Native S5 decoder size query failed: status=" + str(status))

        output_type = ctypes.c_uint8 * required.value
        output = output_type()
        output_pointer = ctypes.cast(output, ctypes.POINTER(ctypes.c_uint8)) if required.value else None
        status = self.decode(
            source_pointer,
            len(data),
            output_pointer,
            required.value,
            ctypes.byref(required),
        )
        if status != 0:
            raise RuntimeError("Native S5 decoder failed: status=" + str(status))
        return bytes(output)


def inspect_archive(path: Path, native_decoder: NativeDecoder | None) -> int:
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
            if native_decoder is not None:
                native_decoded = native_decoder(data)
                if native_decoded != decoded:
                    raise SystemExit(
                        "NATIVE_DIFFERENTIAL_MISMATCH "
                        + f"archive={path.name}!/{item.filename}"
                    )
                print(
                    "NATIVE_MATCH",
                    f"archive={path.name}!/{item.filename}",
                    f"decoded_bytes={len(native_decoded)}",
                )
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
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--native-library",
        type=Path,
        default=None,
        help="Optional host build of libveas5tensor.so for byte-for-byte differential checks.",
    )
    args = parser.parse_args()
    native_decoder = NativeDecoder(args.native_library) if args.native_library else None

    total = 0
    for archive in (Path("megpoidV7.zip"), Path("AI Model.zip")):
        total += inspect_archive(archive, native_decoder)
    print("S5_JSON_OBJECTS_RECOVERED", total)
    if native_decoder is not None:
        print("S5_NATIVE_DIFFERENTIAL", "PASS")


if __name__ == "__main__":
    main()
