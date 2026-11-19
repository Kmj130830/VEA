#!/usr/bin/env python3
"""Report PE/CLR metadata without executing the inspected DLL."""
from __future__ import annotations

import struct
import sys
from pathlib import Path

IMAGE_FILE_MACHINE_AMD64 = 0x8664
IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR = 14
IMAGE_DIRECTORY_ENTRY_BASERELOC = 5


class PeError(ValueError):
    pass


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def rva_to_offset(data: bytes, sections: list[dict[str, int]], rva: int) -> int:
    for section in sections:
        va = section["virtual_address"]
        raw_size = section["raw_size"]
        span = max(section["virtual_size"], raw_size)
        if va <= rva < va + span:
            delta = rva - va
            if delta >= raw_size:
                raise PeError(f"RVA 0x{rva:x} maps to zero-filled virtual section data")
            offset = section["raw_pointer"] + delta
            if offset >= len(data):
                raise PeError(f"RVA 0x{rva:x} maps outside the file")
            return offset
    raise PeError(f"Unable to map RVA 0x{rva:x} to a file offset")


def inspect(path: Path) -> list[str]:
    data = path.read_bytes()
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise PeError("DOS MZ header missing")
    pe_offset = u32(data, 0x3C)
    if pe_offset + 24 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise PeError("PE signature missing")

    machine = u16(data, pe_offset + 4)
    section_count = u16(data, pe_offset + 6)
    optional_size = u16(data, pe_offset + 20)
    optional = pe_offset + 24
    if optional + optional_size > len(data):
        raise PeError("Optional header extends past file")
    magic = u16(data, optional)
    if magic == 0x20B:
        directory_offset = optional + 112
        kind = "PE32+"
    elif magic == 0x10B:
        directory_offset = optional + 96
        kind = "PE32"
    else:
        raise PeError(f"Unknown optional-header magic: 0x{magic:04x}")

    section_table = optional + optional_size
    sections: list[dict[str, int]] = []
    for index in range(section_count):
        offset = section_table + index * 40
        if offset + 40 > len(data):
            raise PeError("Section table truncated")
        sections.append({
            "virtual_size": u32(data, offset + 8),
            "virtual_address": u32(data, offset + 12),
            "raw_size": u32(data, offset + 16),
            "raw_pointer": u32(data, offset + 20),
        })

    number_of_dirs_offset = optional + (108 if magic == 0x20B else 92)
    directory_count = u32(data, number_of_dirs_offset)
    lines = [
        f"# CLR metadata inspection: {path.name}",
        "",
        f"- File size: {len(data):,} bytes",
        f"- PE kind: {kind}",
        f"- Machine: 0x{machine:04x} ({'AMD64' if machine == IMAGE_FILE_MACHINE_AMD64 else 'other'})",
        f"- Sections: {section_count}",
        f"- Data directories declared: {directory_count}",
    ]

    if directory_count <= IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR:
        lines += ["", "- CLR COM Descriptor directory: not present", "- Managed assembly: not indicated by PE data directories"]
        return lines

    clr_entry = directory_offset + IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR * 8
    clr_rva = u32(data, clr_entry)
    clr_size = u32(data, clr_entry + 4)
    lines += ["", f"- CLR COM Descriptor RVA: 0x{clr_rva:x}", f"- CLR COM Descriptor size: {clr_size} bytes"]
    if clr_rva == 0 or clr_size == 0:
        lines += ["- Managed assembly: no CLR header indicated"]
        return lines

    cli = rva_to_offset(data, sections, clr_rva)
    if cli + 24 > len(data):
        raise PeError("CLR header truncated")
    cli_header_size = u32(data, cli)
    runtime_major = u16(data, cli + 4)
    runtime_minor = u16(data, cli + 6)
    metadata_rva = u32(data, cli + 8)
    metadata_size = u32(data, cli + 12)
    flags = u32(data, cli + 16)
    entry_point = u32(data, cli + 20)

    flag_names = {
        0x00000001: "ILONLY",
        0x00000002: "32BITREQUIRED",
        0x00000004: "ILLIBRARY",
        0x00000008: "STRONGNAMESIGNED",
        0x00000010: "NATIVE_ENTRYPOINT",
        0x00010000: "TRACKDEBUGDATA",
        0x00020000: "32BITPREFERRED",
    }
    named_flags = [name for bit, name in flag_names.items() if flags & bit]
    lines += [
        f"- CLR header size: {cli_header_size} bytes",
        f"- CLR runtime version: {runtime_major}.{runtime_minor}",
        f"- CLR metadata RVA: 0x{metadata_rva:x}",
        f"- CLR metadata size: {metadata_size} bytes",
        f"- CLR flags: 0x{flags:08x} ({', '.join(named_flags) if named_flags else 'none'})",
        f"- CLR entry point token/RVA: 0x{entry_point:08x}",
        f"- IL-only flag: {'yes' if flags & 1 else 'no (mixed/native interop may be present)'}",
    ]

    metadata = rva_to_offset(data, sections, metadata_rva)
    if metadata + 16 <= len(data):
        signature = data[metadata:metadata + 4]
        if signature == b"BSJB":
            version_len = u32(data, metadata + 12)
            version_start = metadata + 16
            version_end = min(version_start + version_len, len(data))
            version = data[version_start:version_end].split(b"\0", 1)[0].decode("ascii", "replace")
            lines.append(f"- Metadata root signature: BSJB (valid)")
            lines.append(f"- Metadata runtime string: {version!r}")
        else:
            lines.append(f"- Metadata root signature: {signature!r} (unexpected)")
    return lines


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("Usage: inspect_v7_clr_metadata.py <dll> [dll ...]")
    output = ["# VOCALOID7 PE / CLR format inspection", "", "> Static metadata only; inspected binaries were not executed.", ""]
    for argument in sys.argv[1:]:
        path = Path(argument)
        output.extend(inspect(path))
        output.append("")
    rendered = "\n".join(output)
    print(rendered)
    report_path = Path("build/v7-engine-analysis/CLR_METADATA.md")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
