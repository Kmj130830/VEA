# VOCALOID7 engine DLL static-analysis plan

## Objective

Find the real native boundary that accepts a VOCALOID7 project or note/phoneme events, loads the supplied AI Megpoid V7 model data, and produces audio. The result must be an Android-native ARM64 implementation; Wine, Box64, remote rendering, and PC handoff are out of scope.

## First static targets

The current workflow analyzes:

- `VOCALOID7.dll`: primary engine-side Windows PE/AMD64 DLL.
- `WaveOfflineRenderer.dll`: supporting audio post-processing module. Its known exports include normalization, resampling, and time-stretch helpers; it is not assumed to be the singing renderer.

Both files are extracted from `Editor dll.zip`, PE AMD64 headers are validated, and SHA-256 hashes are retained. Ghidra 12.2 analyzes the files statically; neither DLL is executed.

## Analysis outputs

The Ghidra script records:

- PE memory blocks and executable/read-only sections;
- imported and semantically named symbols;
- printable strings, especially model, phoneme, voice, render, pitch, and audio-related strings;
- functions referenced by interesting string data or imports;
- bounded direct-call closure and decompiler output for candidate functions.

No named export in `VOCALOID7.dll` was found in the earlier PE export scan. The new pass therefore uses import/string cross-references and initialization clues instead of assuming an export name.

## Porting boundary

PE/AMD64 machine code cannot be renamed or mechanically transformed into Android ELF/ARM64. The deliverable must be a newly compiled Android shared library that reimplements the recovered behavior behind a stable C/JNI boundary.

G2P and singing synthesis remain separate subsystems. The current `libveag2p.so` contains only the documented Korean text canonicalization primitive; it does not provide phoneme conversion or singing audio. Likewise, `WaveOfflineRenderer.dll` is not proof of a V7 AI renderer.

## Verification requirements

Do not mark the native renderer available until all of the following are demonstrated on Android ARM64:

1. AI Megpoid model package loads from the supplied V7 assets.
2. A note/phoneme/pitch sequence is accepted by the native renderer.
3. The renderer returns actual synthesized PCM/audio.
4. Pitch, timing, and phoneme transitions match the authorized Yamaha reference sufficiently for the intended quality target.
5. Repeated renders are stable under Android memory, thermal, and cancellation constraints.

This document describes the investigation plan; it is not a claim that an Android V7 renderer exists.
