# Supplied VOCALOID7 assets — static compatibility report

This report is generated from the three ZIP archives committed at the repository root. The inventory workflow checks ZIP integrity, names, sizes, SHA-256 digests, binary headers, PE import/export tables, and readable manifest/style metadata. It does not execute any supplied binary.

## Archive inventory

| Archive | ZIP size | Expanded contents | Result |
|---|---:|---:|---|
| Editor dll.zip | 3,504,077 bytes | 10 DLLs / 16,190,672 bytes | All DLLs identify as PE AMD64 Windows binaries |
| AI Model.zip | 5,640,422 bytes | 3 files / 5,638,049 bytes | ZIP integrity OK; proprietary binary members |
| megpoidV7.zip | 24,054,054 bytes | 20 files / 24,330,418 bytes | ZIP integrity OK; V7 voice-bank package metadata present |

### Editor dll.zip

The archive contains:

- VOCALOID7.dll
- WaveOfflineRenderer.dll
- AudioTranscoder.dll
- G2PAManager.dll
- G2PAManager_CHS.dll
- G2PAManager_ENG.dll
- G2PAManager_ESP.dll
- G2PAManager_JPN.dll
- G2PAManager_KOR.dll
- G2PA_ESP.dll

The inspected PE machine type for all ten files is AMD64 (Windows x64). No Android ELF .so library was present in this archive. These DLLs cannot be loaded directly by an Android process.

### AI Model.zip

Contains:
- COMMON7.vpit — header begins VPIT
- VOCALOID7.vhnv — header begins VHNV
- VOCALOID7.vtmg — header begins VTMG

These are proprietary binary formats in the supplied archive, not ONNX files. Their payloads have not been decoded or modified. The VOCALOID7.vtmg SHA-256 matches the TModelHash recorded by the Megpoid V7 manifest.

### megpoidV7.zip

The package contains 20 files, including:
- AI_Megpoid.vtb2 — header begins VTBR
- AI_Megpoid.vpit — header begins VPIT
- BEAF64G3DYXLRECA.vmanifest
- 13 .vsstyle JSON style preset files (some describe non-voice-type presets/effects)
- singer icons and installer artwork

Readable manifest fields identify:
- Format: vocaloid/7/voice-bank-manifest.v1
- BankName: AI_Megpoid
- SingerName in the style catalog: AI Megpoid
- Component ID: BEAF64G3DYXLRECA
- Version: 7.0.0
- Raw manifest/catalog count: 19 (not a verified voicebank language count)
- Default style: 181b8942-ba8d-41a2-bbbb-4a30bd64e470 (MegpoidV7 Native)

The manifest's NModelHash, PModelHash, and TModelHash match the SHA-256 digests of AI_Megpoid.vtb2, AI_Megpoid.vpit, and the shared VOCALOID7.vtmg respectively. This establishes that the singer package refers to common VOCALOID7 model data from AI Model.zip; it does not establish that these data can be interpreted without the VOCALOID7 runtime.

The voicebank offers exactly 8 official Voice Types: Native, Whisper, Cute, Downer, Power, Soft, Vivid, and Ballade. The additional .vsstyle JSON files must not be presented as additional voice types. The official AI Megpoid product information describes Japanese, English, and Chinese; VEA's UI therefore displays those 3 languages, not the raw count 19.

## Android build conclusion

The supplied archives document the VOCALOID7 package layout and provide a verified AI Megpoid bank/style catalog. They do not provide an Android-native renderer:
- the supplied renderer/G2P components are Windows x64 PE DLLs;
- the model and voicebank members are proprietary VOCALOID7 binary formats;
- no Android ELF runtime or public Android embedding API is present in these ZIPs.

Consequently, the Android app must not package these Windows DLLs as if they were usable native libraries, and copying the model files into the APK would not make synthesis work. The current implementation path is authorized static analysis followed by an Android ARM64 native port/reimplementation of the recovered interfaces. No activation, encryption, or licensing checks are bypassed.

## What is included in the Android app at this milestone

The small megpoid_v7_catalog.json asset contains readable bank/style metadata and verified model-reference hashes only. It does not include the 23.5 MB model payload or the Windows DLLs. The editor can use this catalog for bank/style selection UI, while direct Android synthesis remains unavailable.

VEA also supports optional Standard MIDI export for interoperability with the licensed desktop editor. This is not the target workflow and is not required for the planned standalone Android renderer.

## UI and playback refinements

The arrangement uses note-block miniatures inside each clip. The piano-roll viewport begins at the actual usable display width, keeps its timeline ruler and pitch lane aligned, and extends only as the musical content extends. The left piano header reserves a small voice portrait slot.

The piano-roll offers DRAW, SELECT, and PITCH tools with bounded zoom (65–230%) and right-edge duration resizing. Each newly drawn note receives a visible five-point pitch preview independent of voicebank selection; the line is smoothly interpolated and drawn in #E76B22. The pitch path uses cubic interpolation.

PLAY and STOP audition the clip through a generated piano timbre. This is explicitly only a note/timing preview; it is not synthesized VOCALOID singing. Voice selection currently loads AI Megpoid identity/style metadata and the supplied artwork, while a native Android VOCALOID engine is still unavailable.

## G2P DLL export/import observations

The initial PE export/import scan found:
- G2PAManager.dll exports the manager wrapper surface, including VIS_G2PAManager_WG2PAManager_canConvert, defaultLyric, setLyricsAndPhonemes, setPhonemes, normalizeAllLyrics, normalizeAllPhonemes, createManager, and syllable-segment array helpers.
- G2PAManager_ENG.dll, G2PAManager_JPN.dll, G2PAManager_KOR.dll, and G2PAManager_CHS.dll each expose G2PACreateManager.
- G2PA_ESP.dll exports g2paCreate, g2paDelete, g2paGet, g2paGetDefaultLyric, g2paGetDefaultPhoneme, g2paGetSyllable, and g2paPut.

The language DLLs import Windows system/runtime libraries, including KERNEL32, MSVCP140, and VCRUNTIME140 variants. Export names alone do not recover parameter types, object layouts, calling conventions, buffer ownership, or initialization/cleanup rules. The app does not call the DLLs.

A new Ghidra 12.2 headless workflow now extracts and statically decompiles the six G2P targets. It records PE memory blocks, G2P-related symbols, function indices, inferred prototypes, and C-like decompiler output for entry points and a bounded call closure. See docs/G2P_REVERSE_ENGINEERING.md. Ghidra output is analysis evidence, not vendor source or a guaranteed ABI; it must be reviewed before native JNI code is written.

## Renderer boundary

WaveOfflineRenderer.dll exports:
- VIS_WOR_Normalize_*
- VIS_WOR_Resample_*
- VIS_WOR_TimeStretch_*
- observer/progress helpers

These exports indicate post-processing utilities (normalization, resampling, and time-stretch). They are not evidence of the AI singing synthesis engine.

The initial scan of VOCALOID7.dll identifies a PE AMD64 image but no named exports. The file may still contain internal code or be loaded through a host/plugin mechanism. Its actual rendering interface and dependency closure remain unknown and require a separate static analysis pass. The G2P decompilation is the first scoped target because it exposes named factory and manager methods.

## Current verification status

- ZIP CRC checks, PE metadata, and the manifest/model hash comparisons passed in the existing static inventory workflow.
- The six G2P DLLs were extracted in CI and their SHA-256 hashes recorded.
- The first Ghidra execution exposed workflow and script issues; the latest corrected analysis run is still being validated.
- No Android G2P .so has been built.
- No actual VOCALOID V7 singing render has been produced on Android.
