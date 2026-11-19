# G2P DLL reverse-engineering workstream

## Objective

The first compatibility target is the supplied VOCALOID G2P subsystem, not the neural singing renderer. The goal is to recover a documented and testable behavioral contract, then implement that contract as a native Android ARM64 shared library.

The required application remains standalone on Android. The implementation path does not use a PC render handoff, Wine/Box64, a remote service, or a cloud service.

## Input scope

The analysis extracts only these members from Editor dll.zip:

| File | Size | SHA-256 |
|---|---:|---|
| G2PAManager.dll | 52,496 | 1bdd00fdf5b572373ac6b7db17c35dd316a05aa540c71c6c13dbd624127d2655 |
| G2PAManager_ENG.dll | 9,663,768 | c936da91a7c540100cfe98db5a54d4311c75dfd9729f6be3d201878b0f26fcff |
| G2PAManager_JPN.dll | 140,056 | 5f5471169d56eab97528cfb3c3a4d38b4b02ebf67bc07a61949a21a92ff1df23 |
| G2PAManager_KOR.dll | 150,808 | 5590a8c5fd24afc48a3ad7531bef62d4a9506471fc2b84388ab68fcf34b58e7e |
| G2PAManager_CHS.dll | 124,696 | 8e817cf65243cd2e45c9d3bf10a6ab1f264df3839b1350c3732ed6ded79b81e8 |
| G2PA_ESP.dll | 190,216 | 7bfd4d1bc2a17c111302eed02fc2e7468707e5811af349cee2bc56fc4fbb71a3 |

The extractor checks ZIP integrity, requires each allow-listed file to start with an MZ header, and records the exact DLL hashes. It does not run or load any DLL.

## Static analysis pipeline

The GitHub Actions workflow uses the official Ghidra 12.2 public release. The release archive is pinned by SHA-256 before extraction. Ghidra runs in headless mode and imports the six PE files for static analysis.

For each file, the Ghidra script records:
- language/compiler metadata, image base, and PE memory blocks;
- symbols whose names contain G2PA or VIS_G2PAManager;
- the analyzed function index, inferred calling convention, return types, and parameter types;
- C-like decompiler output for matching entry points and a bounded call closure;
- disassembly when decompilation is unavailable.

The output is uploaded as the GitHub Actions artifact named VEA-G2P-decompilation-reports.

## What the first pass must establish

Export names alone are not an ABI. Before implementing an Android bridge, the decompiler output and call sites must establish:

1. Whether G2PACreateManager is a factory, its real parameter list, return type, and calling convention.
2. Whether the returned pointer is a C++ object, opaque handle, or manager interface, including its vtable layout if applicable.
3. Which manager functions accept lyric strings and phoneme buffers, and whether buffers are caller-owned or manager-owned.
4. Required initialization, language-resource loading, destruction, and error-reporting sequence.
5. String encoding and normalization semantics, including Unicode and non-ASCII behavior.
6. Thread-safety, state lifetime, and whether a manager instance can process multiple notes sequentially.

A prototype displayed by the decompiler is an inference. It must be corroborated by call sites, referenced data, runtime type information where present, and behavior from the authorized reference implementation.

## Native conversion strategy

After the interface is understood, implement the behavior in a new C++ library built by the Android NDK for arm64-v8a. Do not copy PE machine code into an ELF container and do not rename DLL files.

The JNI boundary should be narrow and explicit:
- accept UTF-8 lyric input and language identifiers;
- return normalized lyric text, phoneme sequences, and syllable boundaries using owned result objects;
- expose explicit create/destroy only if the recovered contract requires persistent instances;
- convert native errors to structured Kotlin exceptions/results;
- avoid leaking vendor-specific pointer layouts into Kotlin.

Differential tests should compare the Android implementation with reference results for Japanese, English, Korean, and Chinese. Test coverage should include kana/romaji, English multi-pronunciation words, Hangul composition and final consonants, Chinese characters, punctuation, whitespace, empty strings, malformed Unicode, and multi-note sequences.

A native G2P library only provides lyric-to-phoneme processing. It does not render singing audio.

## Separate renderer investigation

Once G2P is understood, investigate the V7 host/renderer separately. The initial scan found no named exports in VOCALOID7.dll. WaveOfflineRenderer.dll exports audio normalization, resampling, and time-stretch operations, not an identified singing renderer.

The renderer work must identify a real render boundary and its model/runtime dependencies before an ARM64 port can be evaluated. The model files have proprietary VPIT, VHNV, VTMG, and VTBR headers; no inference ABI has yet been identified.

## Verification status

At the time of writing:
- archive extraction and six DLL SHA-256 calculations succeeded in CI;
- the first Ghidra attempt verified the official Ghidra archive checksum but failed in the distribution discovery step;
- no function decompilation report has been produced yet;
- no Android G2P .so has been built;
- no VOCALOID V7 singing render has been produced on Android.

The next validation gate is a successful Ghidra report, followed by a reviewed ABI map and only then native implementation.
