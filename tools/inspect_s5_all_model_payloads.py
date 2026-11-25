#!/usr/bin/env python3
"""Inspect all bundled VOCALOID7/Megpoid S5 containers offline.

This checks whether the recurrent parameter namespace is stored in a shared
model resource rather than assuming all tensors live in AI_Megpoid.vtb2.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
from inspect_s5_model_payload import decode_payload  # noqa: E402

ARCHIVES = {
    "megpoidV7.zip": {
        "AI_Megpoid.vtb2": "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a",
        "AI_Megpoid.vpit": "40affbf4f76331655866821c2f6b589cc02cb0b0964c21bf48c92f283c0e846f",
    },
    "AI Model.zip": {
        "COMMON7.vpit": "2611bf3c7079ef97851f4103f7d88b9d900a1cbf7e4cffe61ae8d904c2273751",
        "VOCALOID7.vhnv": "d8de8bfbf6ef4c9829431e09a29e4b105524d22faaada9001552cd89cb61d570",
        "VOCALOID7.vtmg": "3384cc85fa5ccea1c3203b4ae7aee687a059ec67a7d3a9ebecb8c57170d2b36d",
    },
}
SALT = bytes.fromhex("692333652e635a5425356b396a32642c325a72532f2b2f32")
CONFIG_KEYS = {
    "input_or_dimension_418": "3fecf2d78547692b4157889e04a498f0",
    "hidden_or_dimension_40": "2aff5930df33d03ad1f33c3091886f13",
    "layer_config_3c77": "3c77d931295c40d4d75d971398fee8a1",
    "layer_config_d31a": "d31a34cf5e8888d62df2d9b4e32b80f1",
}

# Four S5API option/tensor keys derived from the exact 32-bit words emitted by
# FUN_1800e6c20, FUN_1800dfb50, FUN_1800e9580 and FUN_1800e9740.
# The hash formatter writes each uint32 as eight lowercase hex digits.
RENDER_OPTION_KEYS = {
    "terminal_spectrum_log_magnitude_phase": "d22b2750ccebc1459f7a9d9ea7186bbf",
    # FUN_1800e6de0 -> render-root byte +0x59: zero selects standard
    # Banked; nonzero selects the Alternate 2x temporal-expansion operator.
    "alternate_transition_enabled": "119558af50f9c8919539abd8e064b287",
    # FUN_1800e3030 -> render-root byte +0x3405 (optional; default false).
    "nearest_part_mask_enabled": "7a5a0ba0ba2f00ab8a0a0d538add0e6d",
    # FUN_1800e2e70 -> required identifier when nearest-part masking is enabled.
    "part_modulation_identifier": "184ef4ded83d0b35d809a7f1b09b1066",
    # FUN_1800e2770 -> render-root byte +0x3411 (optional; default false).
    "multipart_threshold_mode": "ef12c1f702abd89b2e1f07a126cc5411",
    "carrier_fusion_enabled": "66bfcc4a31ba796aca88c5eae36870fc",
    "carrier_dense_weights": "61ef9b49f8fe353fe0d586220366ce92",
    "carrier_dense_bias": "0128c19d610695f96c546cb8e04839fd",
    "fallback_normalization_scale": "d55956a69b51b6f6dd43bd0cc2467bcf",
    "fallback_normalization_bias": "bf96a6e83ab8bb7d33509f2d3a1a348f",
    "fallback_dense_weights": "051fe4a0e8e7e92c3c8791bd28f24da8",
    "fallback_dense_bias": "3a9561654ca2e128021a59d14813a88f",
}


def key_variants(name: str) -> set[str]:
    digest = hashlib.md5(name.encode("ascii") + SALT).digest()
    return {
        digest.hex(),
        "".join(f"{int.from_bytes(digest[i:i+4], 'little'):08x}" for i in range(0, 16, 4)),
        "".join(f"{int.from_bytes(digest[i:i+4], 'big'):08x}" for i in range(0, 16, 4)),
    }


def walk(value, keys: set[str], counters: dict[str, int]) -> None:
    if isinstance(value, dict):
        counters["objects"] += 1
        for k, child in value.items():
            keys.add(k)
            walk(child, keys, counters)
    elif isinstance(value, list):
        counters["arrays"] += 1
        for child in value:
            walk(child, keys, counters)
    elif isinstance(value, str):
        counters["strings"] += 1
        if value.startswith(("*F32", "*F16", "*S32")):
            counters["typed"] += 1

def value_summary(value) -> str:
    if isinstance(value, bool):
        return f"boolean={str(value).lower()}"
    if isinstance(value, (int, float)):
        return f"number={value!r}"
    if isinstance(value, str) and value.startswith(("*F32", "*F16", "*S32")):
        marker, body = value[:4], value[4:]
        try:
            raw = base64.b64decode(body, validate=True)
            scalar_bytes = {"*F32": 4, "*S32": 4, "*F16": 2}[marker]
            return f"{marker} tensor, {len(raw) // scalar_bytes} elements, {len(raw)} bytes"
        except (ValueError, KeyError):
            return f"{marker} tensor string (payload decode failed)"
    if isinstance(value, str):
        return f"string={value[:120]!r}"
    if isinstance(value, dict):
        return f"object with {len(value)} keys"
    if isinstance(value, list):
        return f"array with {len(value)} elements"
    return type(value).__name__


def locate_option_values(value, path: str = "$") -> dict[str, list[tuple[str, object]]]:
    found = {name: [] for name in RENDER_OPTION_KEYS}
    wanted = {key: name for name, key in RENDER_OPTION_KEYS.items()}

    def walk_options(node, node_path: str) -> None:
        if isinstance(node, dict):
            for key, child in node.items():
                child_path = node_path + "." + key
                option_name = wanted.get(key)
                if option_name is not None:
                    found[option_name].append((child_path, child))
                walk_options(child, child_path)
        elif isinstance(node, list):
            for index, child in enumerate(node):
                walk_options(child, f"{node_path}[{index}]")

    walk_options(value, path)
    return found


def inspect_member(
    archive_name: str,
    member_name: str,
    expected_sha: str,
    decoded_output_dir: Path,
) -> list[str]:
    archive_path = Path(archive_name)
    if not archive_path.is_file():
        raise SystemExit(f"missing required archive: {archive_name}")

    with zipfile.ZipFile(archive_path) as archive:
        matches = [n for n in archive.namelist() if n.rsplit("/", 1)[-1] == member_name]
        if len(matches) != 1:
            raise SystemExit(f"{member_name}: expected one member in {archive_name}, found {len(matches)}")
        raw = archive.read(matches[0])

    actual_sha = hashlib.sha256(raw).hexdigest()
    if actual_sha != expected_sha:
        raise SystemExit(f"{member_name}: SHA-256 mismatch: {actual_sha}")

    decoded, metadata = decode_payload(raw)
    decoded_output_dir.mkdir(parents=True, exist_ok=True)
    (decoded_output_dir / f"{member_name}.json").write_bytes(decoded)
    lines = [
        f"## {member_name}",
        f"- archive: {archive_name}",
        f"- magic: {metadata['magic']}",
        f"- file bytes: {metadata['file_size']}",
        f"- decoded bytes: {metadata['decoded_size']}",
        f"- trailing bytes: {metadata['untouched_trailing_bytes']}",
        f"- SHA-256: {actual_sha}",
        f"- decoded prefix: {decoded[:32].hex()}",
    ]

    try:
        text = decoded.decode("utf-8")
        parsed = json.loads(text.lstrip("\ufeff\x00 \t\r\n").rstrip("\x00 \t\r\n"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        lines.append(f"- JSON: no ({type(exc).__name__})")
        lines.append(f"- UTF-8 printable ratio (first 64 KiB): {sum(32 <= b < 127 or b in (9,10,13) for b in decoded[:65536]) / max(1, min(len(decoded),65536)):.4f}")
        return lines

    keys: set[str] = set()
    counters = {"objects": 0, "arrays": 0, "strings": 0, "typed": 0}
    walk(parsed, keys, counters)
    lines.append("- JSON: yes")
    lines.append("- root type: " + type(parsed).__name__)
    lines.append("- objects / arrays / strings / typed tensor strings: " +
                 " / ".join(str(counters[k]) for k in ("objects", "arrays", "strings", "typed")))

    config_matches = [name for name, digest in CONFIG_KEYS.items() if digest in keys]
    lines.append("- S5API layer-config property keys: " +
                 (", ".join(config_matches) if config_matches else "none"))

    render_options = locate_option_values(parsed)
    lines.append("- S5API post-residual/carrier option and tensor keys:")
    for name, lookup_key in RENDER_OPTION_KEYS.items():
        matches = render_options[name]
        if not matches:
            lines.append(f"  - {name}: absent (key {lookup_key})")
            continue
        for object_path, value in matches:
            lines.append(f"  - {name}: {object_path} -> {value_summary(value)}")
        if len(matches) > 1:
            lines.append(f"    - WARNING: {len(matches)} occurrences; the selected render subtree must be disambiguated")

    recurrent_names = []
    for layer in range(32):
        recurrent_names.extend([
            f"rnn.weight_ih_l{layer}", f"rnn.weight_hh_l{layer}",
            f"rnn.bias_ih_l{layer}", f"rnn.bias_hh_l{layer}",
            f"rnn.weight_ih_l{layer}_reverse", f"rnn.weight_hh_l{layer}_reverse",
            f"rnn.bias_ih_l{layer}_reverse", f"rnn.bias_hh_l{layer}_reverse",
        ])
    direct = []
    suffixed = []
    for name in recurrent_names:
        for candidate in key_variants(name):
            if candidate in keys:
                direct.append((name, candidate))
        for suffix in (".weight", ".bias"):
            for candidate in key_variants(name + suffix):
                if candidate in keys:
                    suffixed.append((name + suffix, candidate))
    def describe_objects(value, path="$"):
        if isinstance(value, dict):
            direct_types = []
            for key, child in value.items():
                if isinstance(child, dict):
                    kind = f"object({len(child)})"
                elif isinstance(child, list):
                    kind = f"array({len(child)})"
                elif isinstance(child, str) and child.startswith(("*F32", "*F16", "*S32")):
                    kind = f"tensor-string({child[:4]}, chars={len(child)})"
                elif isinstance(child, str):
                    kind = f"string(chars={len(child)})"
                else:
                    kind = type(child).__name__ + (f"({child})" if isinstance(child, (int, float, bool)) else "")
                direct_types.append(f"{key}={kind}")
            lines.append(f"- object path `{path}` ({len(value)} keys)")
            for item in direct_types[:24]:
                lines.append(f"  - {item}")
            if len(direct_types) > 24:
                lines.append(f"  - ... {len(direct_types) - 24} more keys")
            for key, child in value.items():
                describe_objects(child, path + "." + key)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                describe_objects(child, f"{path}[{index}]")

    describe_objects(parsed)

    lines.append(f"- direct recurrent-name digest matches (layers 0-31): {len(direct)}")
    for name, digest in direct[:32]:
        lines.append(f"  - {name} -> {digest}")
    lines.append(f"- appended weight/bias digest matches (layers 0-31): {len(suffixed)}")
    for name, digest in suffixed[:32]:
        lines.append(f"  - {name} -> {digest}")
    return lines


def main() -> None:
    report = [
        "# All bundled S5 model-resource inspection",
        "",
        "> Offline decoding and structure inspection only; no model inference is run.",
        "",
    ]
    report.append("## Archive inventory")
    for archive_name in ARCHIVES:
        with zipfile.ZipFile(archive_name) as archive:
            infos = archive.infolist()
            report.append(f"### {archive_name} ({len(infos)} members, {sum(x.file_size for x in infos)} uncompressed bytes)")
            for info in infos:
                report.append(f"- `{info.filename}` — {info.file_size} bytes")
            report.append("")

    decoded_output_dir = Path("build/s5-container-hash/decoded")
    for archive_name, members in ARCHIVES.items():
        for member_name, expected_sha in members.items():
            report.extend(inspect_member(
                archive_name, member_name, expected_sha, decoded_output_dir
            ))
            report.append("")

    output = Path("build/s5-container-hash/ALL_MODEL_RESOURCES_INSPECTION.md")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(report), encoding="utf-8")
    print("\n".join(report))
    print(f"report written to {output}")


if __name__ == "__main__":
    main()
