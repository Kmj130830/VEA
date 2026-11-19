# Engine and compatibility boundary

## Target

VEA's target is offline Android editing and, eventually, local VOCALOID AI rendering. The first integration target is VOCALOID7 AI Megpoid; VOCALOID6 AI compatibility remains a later design target. The data model retains note timing, lyric, phoneme text, and per-note pitch points.

**Product requirement:** synthesis must run on the Android device. A PC connection, remote render server, or cloud synthesis service is not an acceptable final workflow.

## Android native binary boundary

Android native libraries use ELF and a device ABI such as `arm64-v8a`; Windows DLLs use PE and a Windows ABI. A PE AMD64 DLL cannot be made into an Android `.so` by renaming its extension. A genuine Android native port must be built for the Android ABI and link against Android-compatible system libraries.

Official Android NDK references:
- https://developer.android.com/ndk/guides/abis
- https://developer.android.com/ndk/guides/jni-tips

## Two possible local execution paths

### A. Native Android port

Port/rebuild the required renderer for `arm64-v8a`, expose a stable C ABI, and call it from Kotlin through JNI. This is the cleanest long-term architecture, but it requires renderer source or an authorized Android-compatible SDK/API. The supplied archives do not contain source code or an Android ELF renderer.

### B. Run selected Windows components locally

A compatibility environment can run some Windows x64 software on Android ARM64. Wine supplies Windows API compatibility and Box64 translates x86-64 user-mode instructions. Winlator demonstrates this class of approach on Android.

References:
- https://github.com/winebox64/winlator
- https://github.com/deepin-community/box64/blob/master/docs/WINE.md
- https://github.com/AndreRH/hangover

This is not equivalent to loading a DLL with `System.loadLibrary`. VEA would need to integrate and maintain an isolated local runtime, its filesystem layout, Windows runtime dependencies, a helper process, IPC, audio-file handoff, cancellation, and lifecycle management. Compatibility and performance must be proven on the actual Android device. The first experiment should be a small headless G2P DLL, not the full singing renderer.

## Evidence from the supplied archives

### Editor dll.zip

All 10 DLLs inspected so far are PE AMD64 Windows binaries.

- `G2PAManager_ENG.dll`, `G2PAManager_JPN.dll`, `G2PAManager_KOR.dll`, and `G2PAManager_CHS.dll` expose `G2PACreateManager`.
- `G2PAManager.dll` exposes wrapper methods for lyric/phoneme normalization and syllable segmentation.
- The language G2P DLLs import Windows runtime components such as KERNEL32, MSVCP140, and VCRUNTIME140. The English component also imports SHELL32.
- The export names are useful discovery evidence, but the archive does not include function signatures, object layouts, or a supported ABI contract. They must not be called by guessing signatures.
- `WaveOfflineRenderer.dll` imports MSVC runtime and KERNEL32 APIs. Its visible exports are audio normalization, resampling, time-stretch, and observer functions. The current export list does not identify it as the VOCALOID singing renderer.
- `VOCALOID7.dll` is a PE AMD64 DLL. The current static scan found no named exports. This does not prove it has no internal renderer code, but it does mean the archive does not currently expose a known callable singing-render API.

### AI Model.zip and megpoidV7.zip

The archives contain:
- `COMMON7.vpit`
- `VOCALOID7.vhnv`
- `VOCALOID7.vtmg`
- `AI_Megpoid.vtb2`
- `AI_Megpoid.vpit`
- `BEAF64G3DYXLRECA.vmanifest`

The Megpoid manifest hashes match the supplied NModel/PModel/TModel payloads. This confirms package consistency, not that the model format can be loaded by an independent renderer. The files are proprietary VOCALOID7 formats, not ONNX models.

## What is known and unknown

Known:
- The archives are structurally valid ZIPs.
- The DLLs are Windows AMD64 PE binaries, not Android ARM64 ELF objects.
- The G2P DLLs have named factory exports.
- The offline audio utility DLL has normalization/resample/time-stretch exports.
- The Megpoid package metadata identifies 8 Voice Types and Japanese, English, Chinese.

Unknown:
- Whether the G2P factory can be called successfully under Wine/Box64.
- The exact ABI/signatures and initialization sequence for the G2P objects.
- Whether `VOCALOID7.dll` is a complete synthesis core, a dependent module, or a host-loaded component.
- Which additional DLLs, runtime assets, license state, or host process are required for V7 AI rendering.
- Whether the required V7 renderer can run stably and fast enough on the user's Android device.
- Whether the vendor permits the relevant local compatibility-layer deployment under its license.

## Development sequence

1. Build an isolated Android ARM64 local-runtime proof of concept using a maintained Wine/Box64-based stack; do not add the full runtime to the main APK yet.
2. Run a harmless Windows x64 test executable in that environment and verify process startup, filesystem access, and stdout/stderr capture.
3. Test the supplied language G2P DLL only after its supported calling contract is established. Verify one lyric-to-phoneme round trip per language.
4. Identify the actual V7 singing-render entry point and its complete dependency closure from authorized installation/runtime materials. Do not mistake `WaveOfflineRenderer.dll` for a vocal synthesizer.
5. Verify model loading, licensing, offline rendering, pitch/expression transfer, cancellation, and WAV output.
6. Only after a repeatable local render succeeds, integrate the runtime as an optional on-device backend in VEA and report its real availability.

## Non-goals

- No PC connection or remote render service in the final product.
- No renaming PE files to `.so`.
- No bypass of activation, encryption, licensing, or access controls.
- No fabricated WAV or piano-tone output presented as VOCALOID singing.
- No claim of Android synthesis until a real Megpoid V7 render has been verified on-device.
