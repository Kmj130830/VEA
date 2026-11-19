# Engine and native compatibility boundary

## Target

VEA targets offline Android editing and eventual local VOCALOID AI rendering. The first engine target is VOCALOID7 AI Megpoid. The final product must run without a PC connection, Wine/Box64, remote renderer, or cloud service.

## PE-to-ELF boundary

The supplied Windows AMD64 DLLs are PE images, while Android loads ELF libraries built for Android ABIs such as arm64-v8a. Renaming a PE DLL to .so does not convert the loader format, ABI, runtime imports, or C++ object model.

The current strategy is static reverse engineering followed by a new ARM64 implementation:
- analyze the Windows modules statically;
- recover their externally observable contracts and relevant algorithms;
- validate those contracts with the authorized reference implementation;
- implement portable C/C++ and expose stable interfaces via JNI;
- mark a backend ready only after on-device differential tests.

Decompiled prototypes and object layouts are hypotheses, not vendor headers. The host-facing G2P manager interface is distinct from the per-language G2P manager classes.

## G2P status

The G2P investigation covers the common host wrapper and language modules:
- G2PAManager.dll
- G2PAManager_ENG.dll
- G2PAManager_JPN.dll
- G2PAManager_KOR.dll
- G2PAManager_CHS.dll
- G2PA_ESP.dll

Three Ghidra 12.2 static-analysis passes completed. They recover the common manager loader, language-key mapping, distinct object construction patterns, host-facing interface dispatches, and several per-language parser/converter helpers. The evidence and limitations are documented in:
- docs/G2P_REVERSE_ENGINEERING.md
- docs/G2P_STATIC_FINDINGS.md

The repository now builds and packages `libveag2p.so` for `arm64-v8a`. The initial native implementation includes:
- C ABI version `0x00010000`;
- a JNI bridge using explicit standard UTF-8 conversion;
- strict UTF-8 validation and buffer ownership;
- one observed Korean preprocessing transformation: U+30FC and U+2212 to U+002D;
- host CMake tests and Android NDK build integration.

This is **not yet Yamaha G2P phoneme conversion**. The supported language mask is zero; every language readiness query returns false. No language is declared compatible until its phoneme strings, syllable boundaries, note updates and edge cases pass differential tests against the authorized reference.

See docs/NATIVE_G2P_BRIDGE.md for the implemented bridge and test scope.

## VOCALOID7 renderer status

The initial archive scan found no named exports in VOCALOID7.dll. Ghidra's first report recovered many semantic labels (including phoneme-service and renderer-observer names) but marked several function bodies as ".NET CLR Managed Code" and produced invalid native-looking decompilation for them. Those fragments must not be interpreted as real x64 renderer code.

A second static-analysis path has been added to inspect the PE CLR descriptor and run ILSpy against VOCALOID7.dll and WaveOfflineRenderer.dll. Its goal is to determine whether the file contains managed IL, mixed-mode code, or native-only regions and then recover managed method metadata where supported.

WaveOfflineRenderer.dll exposes normalization, resampling, and time-stretch helpers. It is an audio utility module, not proof of a VOCALOID AI singing renderer.

The V7 model assets use proprietary VPIT, VHNV, VTMG and VTBR formats. Manifest hashes prove file consistency, not a documented inference contract. The renderer work must identify:
- the host-to-engine call path;
- model loading and required runtime resources;
- how note, phoneme, pitch and expression parameters enter the renderer;
- the audio-block/offline-render output boundary;
- required external dependencies and their Android ARM64 replacements.

No V7 Android singing renderer exists yet.

## Verification rules

- The latest APK is built in CI and contains `lib/arm64-v8a/libveag2p.so`, confirmed as ELF64 AArch64.
- The native G2P core tests and Android NDK build passed in CI.
- These checks validate bridge packaging and the implemented preprocessing primitive only.
- The app must keep synthesis unavailable until a complete language G2P backend and actual V7 singing renderer produce verified output on Android.

No Wine/Box64 route, PE renaming, licensing bypass, or fabricated VOCALOID audio is part of the implementation.
