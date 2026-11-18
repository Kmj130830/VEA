# Supplied VOCALOID7 assets — static compatibility report

This report is generated from the three ZIP archives committed at the repository root. The inspection workflow checks ZIP integrity, names, sizes, SHA-256 digests, binary headers, and readable manifest/style metadata. It does not execute or patch any supplied binary.

## Archive inventory

| Archive | ZIP size | Expanded contents | Result |
|---|---:|---:|---|
| `Editor dll.zip` | 3,504,077 bytes | 10 DLLs / 16,190,672 bytes | All DLLs identify as PE AMD64 Windows binaries |
| `AI Model.zip` | 5,640,422 bytes | 3 files / 5,638,049 bytes | ZIP integrity OK; proprietary binary members |
| `megpoidV7.zip` | 24,054,054 bytes | 20 files / 24,330,418 bytes | ZIP integrity OK; V7 voice-bank package metadata present |

### Editor dll.zip

The archive contains:

- `VOCALOID7.dll`
- `WaveOfflineRenderer.dll`
- `AudioTranscoder.dll`
- `G2PAManager.dll`
- `G2PAManager_CHS.dll`
- `G2PAManager_ENG.dll`
- `G2PAManager_ESP.dll`
- `G2PAManager_JPN.dll`
- `G2PAManager_KOR.dll`
- `G2PA_ESP.dll`

The inspected PE machine type for all ten files is AMD64 (Windows x64). No Android ELF `.so` library was present in this archive. These DLLs cannot be loaded directly by an Android process.

### AI Model.zip

Contains:

- `COMMON7.vpit` — header begins `VPIT`
- `VOCALOID7.vhnv` — header begins `VHNV`
- `VOCALOID7.vtmg` — header begins `VTMG`

These are proprietary binary formats in the supplied archive, not ONNX files. Their payloads were not decoded or modified. The `VOCALOID7.vtmg` SHA-256 matches the `TModelHash` recorded by the Megpoid V7 manifest.

### megpoidV7.zip

The package contains 20 files, including:

- `AI_Megpoid.vtb2` — header begins `VTBR`
- `AI_Megpoid.vpit` — header begins `VPIT`
- `BEAF64G3DYXLRECA.vmanifest`
- 13 `.vsstyle` JSON style preset files (some describe non-voice-type presets/effects)
- singer icons and installer artwork

Readable manifest fields identify:

- Format: `vocaloid/7/voice-bank-manifest.v1`
- BankName: `AI_Megpoid`
- SingerName in the style catalog: `AI Megpoid`
- Component ID: `BEAF64G3DYXLRECA`
- Version: `7.0.0`
- Raw manifest/catalog count: `19` (not a verified voicebank language count)
- Default style: `181b8942-ba8d-41a2-bbbb-4a30bd64e470` (`MegpoidV7 Native`)

The manifest's `NModelHash`, `PModelHash`, and `TModelHash` match the SHA-256 digests of `AI_Megpoid.vtb2`, `AI_Megpoid.vpit`, and the shared `VOCALOID7.vtmg` respectively. This establishes that the singer package refers to common VOCALOID7 model data from `AI Model.zip`; it does not establish that these data can be interpreted without the VOCALOID7 runtime.

The voicebank offers exactly 8 official Voice Types: Native, Whisper, Cute, Downer, Power, Soft, Vivid, and Ballade. The additional `.vsstyle` JSON files in the package must not be presented as additional voice types. The official AI Megpoid product information describes Japanese, English, and Chinese; VEA's UI therefore displays those 3 languages, not the raw count 19.

## Android build conclusion

The supplied archives are useful evidence of the VOCALOID7 package layout and provide a verified AI Megpoid bank/style catalog. They do **not** provide an Android-native renderer:

- the supplied renderer/G2P components are Windows x64 PE DLLs;
- the model and voicebank members are proprietary VOCALOID7 binary formats;
- no Android ELF runtime or public Android embedding API is present in these ZIPs.

Consequently, the Android app must not package these Windows DLLs as if they were usable native libraries, and copying the model files into the APK would not make synthesis work. Real V7 AI rendering still requires an authorized Android-compatible runtime/SDK or a separately authorized native implementation. No activation, encryption, or licensing checks have been bypassed.

## What is included in the Android app at this milestone

The small `megpoid_v7_catalog.json` asset contains the readable bank/style metadata and the verified model-reference hashes only. It does not include the 23.5 MB model payload or the Windows DLLs. The editor can use this catalog for future bank/style selection UI, while direct Android synthesis remains unavailable. VEA now provides a Standard MIDI hand-off to the licensed VOCALOID7 desktop editor; see `docs/VOCALOID7_HANDOFF.md`.


## UI and playback refinements

The arrangement uses note-block miniatures inside each clip. The piano-roll viewport begins at the actual usable display width, keeps its timeline ruler and pitch lane aligned, and extends only as the musical content extends. The left piano header reserves a small voice portrait slot.

The piano-roll offers DRAW, SELECT, and PITCH tools with bounded zoom (65–230%) and right-edge duration resizing. Each newly drawn note receives a visible five-point pitch preview independent of voicebank selection; the line is smoothly interpolated and drawn in `#E76B22`. The pitch path uses cubic interpolation and the requested `#E76B22` display color.

PLAY and STOP audition the clip through a generated piano timbre. This is explicitly only a note/timing preview; it is not synthesized VOCALOID singing. Voice selection currently loads AI Megpoid identity/style metadata and the supplied artwork, while a licensed Android-compatible VOCALOID engine is still unavailable.

## G2P DLL compatibility

The archive inventory step also reads the PE import DLL table and named export table from the supplied Windows DLLs. This is static inspection only. The language-specific `G2PAManager_*.dll` components remain Windows AMD64 libraries and are not loaded or invoked from Android. Their presence is useful for identifying the desktop G2P boundary, but does not provide an Android-callable G2P library.


## G2P PE export/import observations

The static PE export/import scan found the following named exports:

- `G2PAManager.dll` exports the manager wrapper surface, including `VIS_G2PAManager_WG2PAManager_canConvert`, `defaultLyric`, `setLyricsAndPhonemes`, `setPhonemes`, `normalizeAllLyrics`, `normalizeAllPhonemes`, `createManager`, and syllable-segment array helpers.
- `G2PAManager_ENG.dll`, `G2PAManager_JPN.dll`, `G2PAManager_KOR.dll`, and `G2PAManager_CHS.dll` each expose a named `G2PACreateManager` entry point.
- `G2PA_ESP.dll` exports `g2paCreate`, `g2paDelete`, `g2paGet`, `g2paGetDefaultLyric`, `g2paGetDefaultPhoneme`, `g2paGetSyllable`, and `g2paPut`.

The English G2P manager imports Windows system/runtime DLLs (including KERNEL32, MSVCP140, and VCRUNTIME140 variants). The scan did not recover function signatures, object layouts, calling conventions beyond the PE platform, or any Android ABI. Therefore these symbols identify a likely desktop G2P manager interface but are not enough to call it safely or port it. The current app does not invoke the DLLs. Their named exports are not a usable Android API, and the inspection did not recover the signatures/ABI needed for a safe integration. The Android app uses its own fallback G2P; for genuine VOCALOID7 G2P and singing, use the new MIDI hand-off to the official editor.
