# VOCALOID G2P static analysis and Android port

## Objective and constraints

The first compatibility target is the supplied VOCALOID G2P subsystem. The final application is standalone Android: no PC rendering handoff, Wine/Box64, remote service, or cloud synthesis.

The supplied DLLs are Windows AMD64 PE files. They cannot be loaded by Android as ELF shared objects or converted by renaming. The port is implemented as new Android ARM64 C++/JNI code from a recovered behavioral contract. No activation, encryption, or licensing checks are bypassed.

## Static-analysis inputs

The workflow extracts and SHA-256 checks these six allow-listed files from `Editor dll.zip`:

| File | Size |
|---|---:|
| G2PAManager.dll | 52,496 bytes |
| G2PAManager_ENG.dll | 9,663,768 bytes |
| G2PAManager_JPN.dll | 140,056 bytes |
| G2PAManager_KOR.dll | 150,808 bytes |
| G2PAManager_CHS.dll | 124,696 bytes |
| G2PA_ESP.dll | 190,216 bytes |

All six are PE AMD64. The extractor verifies the ZIP CRC and MZ headers, writes the exact inputs to the analysis job, and records their digests. It does not load or execute a supplied DLL.

## Ghidra pipeline

The GitHub Actions workflow uses the official Ghidra 12.2 release, pinned by SHA-256. It imports each PE in headless mode and uploads reports as `VEA-G2P-decompilation-reports`.

The report includes:
- PE blocks, image base, language/compiler metadata;
- G2PA exports and related symbols;
- inferred function prototypes and calling conventions;
- decompiled export/call-closure methods;
- language-manager vtable candidates and resolved virtual method bodies;
- optional supplemental parser/helper-function seeds for the Korean, Chinese and Japanese DLLs.

Analysis reports:
- first pass: run 44430339143;
- second pass with factory-referenced vtables: run 44431058683;
- third pass with internal parser seeds: run 44431862910. Check the Actions run for its latest completion state and artifact.

Decompiler output is evidence, not vendor source or a canonical ABI header. It must be corroborated with references, code paths and the authorized reference implementation.

## Confirmed architecture observations

### Common Windows host layer

`G2PAManager.dll` provides a common Windows host wrapper. Its language loader constructs a per-language DLL path, calls `LoadLibraryW`, resolves `G2PACreateManager` with `GetProcAddress`, and caches the module/factory by language key.

Language key mapping:

| Key | DLL |
|---:|---|
| 0 | G2PAManager_JPN.dll |
| 1 | G2PAManager_ENG.dll |
| 2 | G2PAManager_KOR.dll |
| 3 | G2PAManager_ESP.dll |
| 4 | G2PAManager_CHS.dll |

The host creates a separate `G2PAManagerIF` wrapper object and exposes `VIS_G2PAManager_WG2PAManager_*` functions. The host-facing interface vtable is distinct from each language DLL's `G2PAManager` vtable. Their offsets and object fields must not be conflated.

### Language manager layer

Factory allocation sizes observed in this build:
- ENG/JPN: 0x18 bytes;
- KOR/CHS: 0x48 bytes, with a copied wide-character path and error state.

The language objects also create internal parser/converter objects. Korean's main lyric processing path:
- receives a UTF-16 lyric and note/container interfaces;
- lazily constructs an internal module and converter;
- canonicalizes U+30FC and U+2212 to ASCII hyphen-minus;
- calls an internal parser helper to produce intermediate segments;
- iterates note objects and sends phoneme strings through the note interface;
- returns an update count and error status.

A separate Korean method traverses note entries for phoneme normalization/consistency checks. Chinese has a different internal text-processing path, including handling for U+3000. Japanese and English use different and larger language-specific code paths. ESP exposes a separate C-style interface.

See `docs/G2P_STATIC_FINDINGS.md` for evidence and method-specific details.

## Android native bridge status

The repository now builds `libveag2p.so` for `arm64-v8a` using NDK 27.2.12479018 and CMake 3.22.1.

Current implementation:
- stable C ABI version 0x00010000;
- explicit UTF-8 input/output lengths and status values;
- JNI conversion between Java UTF-16 and standard UTF-8;
- one recovered Korean input-normalization primitive: U+30FC and U+2212 become U+002D;
- host-side CTest coverage for the UTF-8 contract and normalization.

Not implemented:
- Yamaha Korean phoneme generation;
- Japanese, English, Chinese or Spanish G2P;
- complete note/syllable container semantics;
- VOCALOID6/7 singing synthesis or audio rendering.

The supported language mask is deliberately zero and every language readiness query is false. The app must not report G2P or singing synthesis as available based solely on successful loading of this bridge.

See `docs/NATIVE_G2P_BRIDGE.md` for native file paths, ABI behavior and test coverage.

## Remaining G2P port gates

1. Recover the common `G2PAManagerIF` forwarding/ownership contract separately from each language-specific manager.
2. Trace the internal parser/converter functions and their vtables, including resource-file or embedded-data dependencies.
3. Establish note container, note object, syllable segment and phoneme buffer contracts, including exact widths, encodings, ownership and error codes.
4. Reimplement each language backend in portable C++ without Windows API or MSVC object ABI dependencies.
5. Differential-test normalization, phoneme sequences, syllable boundaries, default-phoneme insertion, note updates, errors and multi-note behavior against the authorized Yamaha reference.
6. Enable one language at a time only after its differential tests pass on Android ARM64.

G2P only prepares lyrics and phonemes; it does not synthesize the singing waveform.

## Separate VOCALOID7 renderer investigation

The initial PE scan found no named exports in `VOCALOID7.dll`. `WaveOfflineRenderer.dll` exports audio normalization, resampling and time-stretch helpers; those are not proof of an AI singing renderer.

A separate analysis must identify the host-to-renderer call path, runtime/dependency closure, model loading, parameter mapping and offline or streaming audio boundary. The VPIT, VHNV, VTMG and VTBR files are proprietary binary formats; their headers and matching manifest hashes do not disclose a complete inference contract. No Android VOCALOID7 renderer is implemented.
