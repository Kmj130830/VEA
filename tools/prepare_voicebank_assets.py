#!/usr/bin/env python3
"""Extract only the user-supplied voicebank artwork required by the Android UI."""
from pathlib import Path
import zipfile

archive = Path("megpoidV7.zip")
asset_dir = Path("app/src/main/assets/voicebanks")
asset_dir.mkdir(parents=True, exist_ok=True)

# Exact allow-list: no model data, installer, manifest, or executable is unpacked.
allowed = {
    "icon.png": "ai_megpoid_icon.png",
    "overlay.png": "ai_megpoid_overlay.png",
}
with zipfile.ZipFile(archive) as source:
    for member, output_name in allowed.items():
        data = source.read(member)
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > 2_000_000:
            raise SystemExit(f"Refusing unexpected voicebank image: {member}")
        (asset_dir / output_name).write_bytes(data)
        print(f"Prepared {output_name}: {len(data)} bytes")
