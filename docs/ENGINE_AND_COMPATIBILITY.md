# Engine and native compatibility boundary

## Target

VEA targets offline Android editing and eventual local VOCALOID AI rendering. The first engine target is VOCALOID7 AI Megpoid. The final product must run without a PC connection, Wine/Box64, remote renderer, or cloud service.

## PE-to-ELF boundary

The supplied Windows AMD64 DLLs are PE images, while Android loads ELF libraries built for Android ABIs such as arm64-v8a. Renaming a PE DLL to .so does not convert the loader format, ABI, runtime imports, or C++ object model.

The implementation route is static analysis followed by a new ARM64 implementation:
- analyze the Windows modules statically;
- recover externally observable contracts and relevant algorithms;
- validate those contracts with the authorized reference implementation;
- implement portable C/C++ and expose stable interfaces via JNI;
- mark a backend ready only after on-device differential tests.

Decompiled prototypes and object layouts are hypotheses, not vendor headers. The host-facing G2P manager interface is distinct from per-language G2P manager classes.

## G2P status

Three Ghidra 12.2 static-analysis passes completed on the supplied G2P DLLs. They recover the common manager loader, language-key mapping, distinct object construction patterns, host-facing interface dispatches, and several per-language parser/converter helpers.

The repository builds and packages `libveag2p.so` for `arm64-v8a`. The initial native implementation includes:
- C ABI version `0x00010000`;
- JNI bridge using explicit standard UTF-8 conversion;
- strict UTF-8 validation and buffer ownership;
- one observed Korean preprocessing transformation: U+30FC and U+2212 to U+002D;
- host CMake tests and Android NDK build integration.

This is **not yet Yamaha G2P phoneme conversion**. Supported language mask is zero; every language readiness query returns false. No language is declared compatible until its phoneme strings, syllable boundaries, note updates and edge cases pass differential tests.

See:
- docs/G2P_REVERSE_ENGINEERING.md
- docs/G2P_STATIC_FINDINGS.md
- docs/NATIVE_G2P_BRIDGE.md

## VOCALOID7 managed layer and missing native modules

ILSpy successfully reconstructed the managed C# surface of `VOCALOID7.dll`. Its P/Invoke declarations reveal that the selected `Editor dll.zip` is not the complete native runtime directory.

The strongest renderer-facing boundary is in the missing `vsm.dll`:
- `WVSMModuleIF.CreateManager` creates a VSM sequence manager;
- `WIVSMSequenceManager` receives VDM, DSE and YVS services;
- its phoneme service accepts G2P managers and applies lyrics/phonemes to notes;
- `WIVSMSequence.StartAsyncRendering` and `CreatePartialRenderer` start native rendering;
- its renderer observer's `BlockRendered` callback receives a `WIVSMAudioBufferList` and score data;
- audio-buffer wrappers expose sample pointers and sample counts.

This is the best current lead to the actual render output, but the native buffer format and the acoustic model inference implementation have not yet been recovered. The application layer separately uses `VAE.dll` for audio device/playback/mixdown. Other managed P/Invoke modules include VDM database/voicebank support, VSStyle presets, EffectManager and DSE authorization queries.

The ILSpy inventory reports the following P/Invoke declaration counts: vsm 786, vsstyle 122, vdm 104, VAE 90, EffectManager 74, G2PAManager 25, WaveOfflineRenderer 19, dse 18 and AudioTranscoder 6. In the supplied editor bundle, G2PAManager, WaveOfflineRenderer and AudioTranscoder are present; the primary `vsm.dll` and the other listed modules are missing.

See docs/VOCALOID7_NATIVE_DEPENDENCIES.md for the complete dependency-role inventory and the exact evidence for the renderer callback.

The next necessary input is the complete native DLL/runtime directory from the authorized VOCALOID7 installation, especially `vsm.dll` and its transitive dependencies. Without this, the native renderer implementation boundary cannot be completed. The current V7 renderer is not implemented.

## Audio and model boundary

`WaveOfflineRenderer.dll` is a native AMD64 audio utility library exposing normalization, resampling and time-stretch. It is not the identified singing renderer.

The V7 model package contains proprietary VPIT, VHNV, VTMG and VTBR files. Manifest hashes establish package consistency, not the inference API, model tensor layout or runtime dependency closure.

The next renderer analysis must establish:
- VSM's internal handoff to the actual acoustic engine, if delegated;
- model loading and required runtime resources;
- how notes, phonemes, pitch and expression enter the acoustic renderer;
- audio sample type, channel layout, sample rate, buffer ownership and callback lifetime;
- required external dependencies and Android ARM64 replacements.

No V7 Android singing renderer exists yet.

## Verification

- CI-built APK includes `lib/arm64-v8a/libveag2p.so`, confirmed as ELF64 AArch64.
- Native bridge core tests and Android NDK build passed.
- Those checks validate packaging and the single Korean preprocessing primitive only.
- G2P readiness remains false for all languages and singing synthesis remains unavailable.
- No VOCALOID7 AI Megpoid singing render has been produced on Android.

No Wine/Box64 route, PE renaming, licensing bypass, or fabricated VOCALOID audio is part of the implementation.
