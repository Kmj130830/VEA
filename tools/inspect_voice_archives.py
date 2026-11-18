#!/usr/bin/env python3
"""Static archive inspection only. Never extracts to disk or executes supplied files."""
from __future__ import annotations

import hashlib
import io
import re
import struct
import zipfile
from collections import Counter
from pathlib import Path

ARCHIVES = [Path("Editor dll.zip"), Path("AI Model.zip"), Path("megpoidV7.zip")]
MAX_NESTED_ARCHIVE_BYTES = 120 * 1024 * 1024
MAX_STRING_OUTPUT = 40


def pe_architecture(data: bytes) -> str:
    if not data.startswith(b"MZ") or len(data) < 0x40:
        return "not PE"
    try:
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
        if data[pe_offset:pe_offset + 4] != b"PE\0\0":
            return "MZ header, PE signature not found"
        machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
        return {
            0x014C: "PE x86 (32-bit Windows)",
            0x8664: "PE AMD64 (64-bit Windows)",
            0xAA64: "PE ARM64 Windows",
            0x01C4: "PE ARM Thumb-2 Windows",
        }.get(machine, f"PE machine=0x{machine:04x}")
    except (IndexError, struct.error):
        return "MZ header, invalid PE header"


def pe_static_symbols(data: bytes) -> tuple[list[str], list[str]]:
    """Read PE import DLL names and exported names; never loads or executes the image."""
    if not data.startswith(b"MZ") or len(data) < 0x100:
        return [], []
    try:
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        if data[pe:pe + 4] != b"PE\0\0":
            return [], []
        coff = pe + 4
        section_count = struct.unpack_from("<H", data, coff + 2)[0]
        optional_size = struct.unpack_from("<H", data, coff + 16)[0]
        optional = coff + 20
        magic = struct.unpack_from("<H", data, optional)[0]
        if magic == 0x20B:
            data_dir = optional + 112
        elif magic == 0x10B:
            data_dir = optional + 96
        else:
            return [], []

        sections = []
        section_start = optional + optional_size
        for index in range(section_count):
            offset = section_start + index * 40
            virtual_size, virtual_address, raw_size, raw_pointer = struct.unpack_from("<IIII", data, offset + 8)
            sections.append((virtual_address, max(virtual_size, raw_size), raw_pointer, raw_size))

        def rva_to_offset(rva: int) -> int | None:
            if rva == 0:
                return None
            for virtual_address, span, raw_pointer, raw_size in sections:
                if virtual_address <= rva < virtual_address + span:
                    offset = raw_pointer + (rva - virtual_address)
                    if offset < raw_pointer + raw_size and offset < len(data):
                        return offset
            return None

        def c_string(offset: int | None) -> str:
            if offset is None or offset < 0 or offset >= len(data):
                return ""
            end = data.find(b"\0", offset, min(len(data), offset + 4096))
            if end < 0:
                return ""
            return data[offset:end].decode("ascii", errors="replace")

        exports: list[str] = []
        imports: list[str] = []
        export_rva = struct.unpack_from("<I", data, data_dir)[0]
        if export_rva:
            export_offset = rva_to_offset(export_rva)
            if export_offset is not None and export_offset + 40 <= len(data):
                name_count = struct.unpack_from("<I", data, export_offset + 24)[0]
                names_rva = struct.unpack_from("<I", data, export_offset + 32)[0]
                names_offset = rva_to_offset(names_rva)
                if names_offset is not None:
                    for index in range(min(name_count, 10000)):
                        name_rva = struct.unpack_from("<I", data, names_offset + index * 4)[0]
                        name = c_string(rva_to_offset(name_rva))
                        if name:
                            exports.append(name)

        import_rva = struct.unpack_from("<I", data, data_dir + 8)[0]
        if import_rva:
            import_offset = rva_to_offset(import_rva)
            if import_offset is not None:
                for index in range(1000):
                    descriptor = import_offset + index * 20
                    if descriptor + 20 > len(data):
                        break
                    original_thunk, timestamp, chain, name_rva, first_thunk = struct.unpack_from("<IIIII", data, descriptor)
                    if not any((original_thunk, timestamp, chain, name_rva, first_thunk)):
                        break
                    name = c_string(rva_to_offset(name_rva))
                    if name:
                        imports.append(name)

        return exports, imports
    except (IndexError, struct.error, ValueError):
        return [], []


def describe_magic(data: bytes) -> str:
    if data.startswith(b"MZ"):
        return pe_architecture(data)
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
    return "header=" + data[:20].hex(" ") if data else "empty"


def printable_strings(data: bytes) -> list[str]:
    found = re.findall(rb"[\x20-\x7e]{6,}", data)
    output = []
    for raw in found:
        value = raw.decode("ascii", errors="ignore").strip()
        if value and value not in output:
            output.append(value)
        if len(output) >= MAX_STRING_OUTPUT:
            break
    return output


def inspect_zip(zf: zipfile.ZipFile, label: str, depth: int = 0) -> None:
    infos = [item for item in zf.infolist() if not item.is_dir()]
    extensions = Counter(Path(item.filename).suffix.lower() or "[no extension]" for item in infos)

    print(f"\n=== ARCHIVE {label} (depth={depth}) ===")
    print(
        f"files={len(infos)} total_uncompressed={sum(i.file_size for i in infos)} "
        f"total_compressed={sum(i.compress_size for i in infos)}"
    )
    print("EXTENSIONS " + ", ".join(f"{key}:{value}" for key, value in extensions.most_common()))

    for item in infos:
        try:
            data = zf.read(item)
        except Exception as exc:
            print(f"READ_FAILED {item.filename}: {type(exc).__name__}: {exc}")
            continue
        digest = hashlib.sha256(data).hexdigest()
        print(
            f"FILE {len(data):>12} bytes | {item.compress_size:>12} packed | "
            f"{item.filename} | {describe_magic(data[:512])} | sha256={digest}"
        )

        suffix = Path(item.filename).suffix.lower()
        if suffix == ".dll":
            exports, imports = pe_static_symbols(data)
            if imports:
                print(f"--- PE IMPORT DLLS {item.filename} ---")
                print(", ".join(imports[:80]))
            if exports:
                print(f"--- PE EXPORTS {item.filename} ---")
                print(", ".join(exports[:160]))
            else:
                print(f"PE_EXPORTS {item.filename}: no named exports decoded")
        if suffix in {".vmanifest", ".json", ".xml", ".txt", ".md", ".ini", ".cfg", ".properties", ".vsstyle"}:
            try:
                decoded = data.decode("utf-8-sig").replace("\\x00", "")
                visible = sum(char.isprintable() or char in "\\r\\n\\t" for char in decoded) / max(1, len(decoded))
                if (visible > 0.70 or suffix == ".vmanifest") and len(decoded) <= 24000:
                    print(f"--- TEXT {label}!/{item.filename} ---")
                    print(decoded)
            except UnicodeDecodeError:
                pass

        if suffix in {".vpit", ".vhnv", ".vtmg", ".vtb2", ".vmanifest"}:
            strings = printable_strings(data)
            if strings:
                print(f"--- ASCII STRINGS {label}!/{item.filename} ---")
                for value in strings[:MAX_STRING_OUTPUT]:
                    print(value[:240])

        name = item.filename.lower()
        if depth < 2 and (
            name.endswith(".zip") or name.endswith(".jar") or name.endswith(".nupkg")
        ) and len(data) <= MAX_NESTED_ARCHIVE_BYTES:
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    inspect_zip(nested, f"{label}!/{item.filename}", depth + 1)
            except Exception as exc:
                print(f"NESTED_ARCHIVE_FAILED {item.filename}: {type(exc).__name__}: {exc}")


def main() -> None:
    for path in ARCHIVES:
        if not path.exists():
            print(f"MISSING {path}")
            continue
        data = path.read_bytes()
        print(f"\n### TOP LEVEL: {path}")
        print(
            f"size={len(data)} sha256={hashlib.sha256(data).hexdigest()} "
            f"magic={describe_magic(data[:512])}"
        )
        try:
            with zipfile.ZipFile(path) as archive:
                bad_member = archive.testzip()
                print(f"ZIP_TEST={'OK' if bad_member is None else 'BAD:' + bad_member}")
                inspect_zip(archive, str(path))
        except Exception as exc:
            print(f"ZIP_OPEN_FAILED {type(exc).__name__}: {exc}")


if __name__ == "__main__":
    main()
