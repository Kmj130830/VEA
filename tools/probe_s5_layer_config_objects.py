#!/usr/bin/env python3
"""Bounded offline probe of hashed S5 layer-configuration JSON objects."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inspect_s5_model_payload import decode_payload  # noqa: E402

RESOURCES = {
    "megpoidV7.zip": {
        "AI_Megpoid.vtb2": "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a",
    },
    "AI Model.zip": {
        "VOCALOID7.vtmg": "3384cc85fa5ccea1c3203b4ae7aee687a059ec67a7d3a9ebecb8c57170d2b36d",
    },
}
CONFIG_KEYS = {
    "input_or_dimension_418": "3fecf2d78547692b4157889e04a498f0",
    "hidden_or_dimension_40": "2aff5930df33d03ad1f33c3091886f13",
    "layer_config_3c77": "3c77d931295c40d4d75d971398fee8a1",
    "layer_config_d31a": "d31a34cf5e8888d62df2d9b4e32b80f1",
}


def summarize(value, depth=0):
    if isinstance(value, dict):
        keys = list(value)
        preview = ", ".join(str(k) for k in keys[:40])
        if len(keys) > 40:
            preview += f", ... (+{len(keys) - 40})"
        if depth >= 4:
            return f"object({len(keys)} keys: {preview})"
        fields = [f"{k}={summarize(v, depth + 1)}" for k, v in list(value.items())[:40]]
        if len(keys) > 40:
            fields.append(f"... (+{len(keys) - 40})")
        return "{" + "; ".join(fields) + "}"
    if isinstance(value, list):
        parts = [summarize(v, depth + 1) for v in value[:6]]
        if len(value) > 6:
            parts.append(f"... (+{len(value) - 6})")
        return f"array({len(value)})[" + "; ".join(parts) + "]"
    if isinstance(value, str):
        if value.startswith(("*F32", "*F16", "*S32")):
            return f"tensor({value[:4]}, encoded_chars={len(value)})"
        return f"string(len={len(value)}, preview={value[:72]!r})"
    return repr(value)


def find_keys(value, path="$"):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if key in CONFIG_KEYS.values():
                yield key, child_path, value, child
            yield from find_keys(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from find_keys(child, f"{path}[{index}]")


def main():
    report = [
        "# S5 hashed layer-configuration object probe",
        "",
        "> Offline metadata inspection only. The model is not executed and tensor payload contents are not dumped.",
        "",
    ]
    summary = {name: [] for name in CONFIG_KEYS}

    for archive_name, members in RESOURCES.items():
        archive_path = Path(archive_name)
        if not archive_path.is_file():
            raise SystemExit(f"missing required archive: {archive_name}")
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            for member_name, expected_sha in members.items():
                matches = [n for n in names if n.rsplit("/", 1)[-1] == member_name]
                if len(matches) != 1:
                    raise SystemExit(f"{archive_name}:{member_name}: expected one member, got {len(matches)}")
                raw = archive.read(matches[0])
                actual = hashlib.sha256(raw).hexdigest()
                if actual != expected_sha:
                    raise SystemExit(f"{member_name}: SHA-256 mismatch: {actual}")
                decoded, meta = decode_payload(raw)
                try:
                    parsed = json.loads(decoded.decode("utf-8").lstrip("\ufeff\x00 \t\r\n").rstrip("\x00 \t\r\n"))
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    raise SystemExit(f"{member_name}: JSON decode failed: {exc}") from exc

                report.extend([
                    f"## {member_name}",
                    f"- archive: {archive_name}",
                    f"- magic: {meta['magic']}",
                    f"- original/decoded bytes: {meta['file_size']} / {meta['decoded_size']}",
                    f"- SHA-256: {actual}",
                    "",
                ])
                hits = 0
                for key, object_path, parent, child in find_keys(parsed):
                    name = next(n for n, digest in CONFIG_KEYS.items() if digest == key)
                    siblings = [str(k) for k in parent if k != key]
                    sibling_text = ", ".join(siblings[:40])
                    if len(siblings) > 40:
                        sibling_text += f", ... (+{len(siblings) - 40})"
                    report.extend([
                        f"### {name} ({key})",
                        f"- object path: `{object_path}`",
                        f"- sibling keys in parent: [{sibling_text}]",
                        f"- child schema: {summarize(child)}",
                        "",
                    ])
                    summary[name].append(f"{member_name}:{object_path}")
                    hits += 1
                if hits == 0:
                    report.extend(["- no known layer-configuration hashes found", ""])

    report.extend(["## Hash occurrence summary", ""])
    for name, paths in summary.items():
        report.append(f"- {name}: " + ("; ".join(paths) if paths else "not present"))
    report.append("")

    out = Path("build/s5-container-hash/CONFIG_OBJECT_PROBE.md")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(report), encoding="utf-8")
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
