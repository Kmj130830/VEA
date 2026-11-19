# Engine and native compatibility boundary

## Target

VEA targets offline Android editing and, eventually, local VOCALOID AI rendering. The first integration target is VOCALOID7 AI Megpoid; VOCALOID6 compatibility remains a later design target. The project model retains note timing, lyric, phoneme text, and per-note pitch points.

**Product requirement:** synthesis must run on the Android device. A PC connection, Wine/Box64 runtime, remote render server, or cloud synthesis service is not an acceptable final workflow.

## PE-to-ELF boundary

Android native libraries are ELF objects compiled for an Android ABI such as arm64-v8a. The supplied DLLs are Windows AMD64 PE images and depend on Windows loader/runtime conventions. Renaming a PE DLL to a .so does not change the file format, instruction ABI, imported APIs, C++ runtime, or object ABI.

The chosen route is therefore static reverse engineering followed by a native ARM64 reimplementation/port:
- use Ghidra to inspect entry points, control flow, call sites, inferred prototypes, and data structures;
- validate recovered behavior with the authorized desktop reference;
- implement the confirmed contract in Android-compatible C/C++;
- expose a narrow stable C ABI and call it from Kotlin using JNI;
- build only for Android ABIs that are actually supported and tested, initially arm64-v8a.

An inferred decompiler signature is not an authoritative header. Do not write JNI wrappers based only on guessed pointer types or calling conventions.

Android NDK references:
- https://developer.android.com/ndk/guides/abis
- https://developer.android.com/ndk/guides/jni-tips

## First target: G2P

The first static-analysis batch covers:
- G2PAManager.dll
- G2PAManager_ENG.dll
- G2PAManager_JPN.dll
- G2PAManager_KOR.dll
- G2PAManager_CHS.dll
- G2PA_ESP.dll

The G2PA-related named exports provide entry-point seeds. The Ghidra post-script records PE memory blocks, symbols, the function index, inferred prototypes, and decompiler output for the entry points and their direct call closure (bounded to avoid expanding into unrelated runtime code).

Analysis workflow:
- Workflow: .github/workflows/ghidra-g2p-analysis.yml
- Extractor: tools/extract_g2p_targets.py
- Ghidra script: tools/ghidra/ExportG2pAnalysis.java
- Ghidra release: 12.2, pinned by release archive SHA-256
- Artifact: VEA-G2P-decompilation-reports

After decompilation, the smallest native implementation should be based on the recovered public boundary and checked by differential tests:
1. capture known input/output pairs from the authorized reference for Japanese, English, Chinese, and Korean;
2. determine exact string encoding, buffer ownership, object lifetime, error behavior, and thread-safety requirements;
3. implement only the confirmed G2P interface in C++;
4. expose UTF-8-based JNI calls with explicit ownership and error handling;
5. compare phoneme sequences, syllable boundaries, normalization, and edge cases against the reference.

The existing Kotlin X-SAMPA mapper remains a fallback until those checks pass. A native G2P .so is not yet built.

## Renderer boundary

The G2P DLLs do not constitute the VOCALOID singing engine.

The initial archive scan observed:
- WaveOfflineRenderer.dll exports normalization, resampling, time-stretch, and observer functions. These are post-processing features, not proof of singing synthesis.
- VOCALOID7.dll is a Windows AMD64 PE image with no named exports recovered by the initial scan. This does not prove it has no synthesis code; the entry point may be internal, host-driven, or in another component.
- The model assets use proprietary VPIT, VHNV, VTMG, and VTBR formats rather than ONNX. Matching manifest hashes establish package consistency, not a public inference contract.

The next renderer investigation must establish the actual host-to-engine call path, all required DLLs, model loading sequence, tensor/runtime dependencies, audio callback or offline render boundary, and parameter mapping for notes and pitch/expression. The native renderer must not be approximated by a piano tone, generic neural vocoder, or WaveOfflineRenderer.

## Evidence status

Known:
- The supplied DLL archive members are Windows AMD64 PE files.
- G2P-related DLLs expose named entry points suitable for static analysis.
- WaveOfflineRenderer exports audio utility functions.
- VOCALOID7.dll had no named exports in the initial export scan.
- The AI Megpoid manifest references the supplied model files by matching hashes.

Unknown until the Ghidra reports and additional authorized evidence are examined:
- exact G2P function prototypes and calling conventions;
- G2P object layout, ownership, and initialization/cleanup;
- the G2P language resources and runtime dependency closure;
- the VOCALOID7 rendering entry point and its host contract;
- whether the required V7 model runtime can be implemented efficiently on Android ARM64;
- whether the current supplied files are the complete runtime set required for a real offline render.

## Non-goals and verification

- No PC tethering or remote render service in the final workflow.
- No Wine/Box64 runtime as the implementation route.
- No PE-to-.so renaming.
- No bypass of activation, encryption, licensing, or access controls.
- No WAV fabricated or presented as VOCALOID singing.
- No synthesis-available status until a real Megpoid V7 WAV render has been produced and verified on the Android device.
