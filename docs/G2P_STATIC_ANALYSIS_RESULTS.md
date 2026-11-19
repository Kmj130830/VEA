# G2P static analysis findings

## Status

A static Ghidra 12.2 pass completed successfully for the six allow-listed G2P DLLs extracted from the supplied Editor dll.zip. The workflow produced the artifact VEA-G2P-decompilation-reports.

- G2P workflow: https://github.com/Kmj130830/VEA/actions/runs/44430339143
- Artifact digest: sha256:74b8faa6f6bf4c5c1997c557514414b76b84a2e520010345f747524fe59a0e38
- Analysis mode: PE parsing and static decompilation only. None of the DLLs was executed.
- The decompiler output is evidence for the next analysis pass, not an official Yamaha header or guaranteed ABI.

## 1. Desktop wrapper dynamically selects a language manager

The G2PAManager.dll report shows that its manager factory constructs a language DLL name and uses the Windows APIs LoadLibraryW and GetProcAddress to resolve G2PACreateManager.

The recovered language selector in FUN_1800015e0 maps these integer values to DLL names:

| Selector | DLL name |
|---:|---|
| 0 | G2PAManager_JPN.dll |
| 1 | G2PAManager_ENG.dll |
| 2 | G2PAManager_KOR.dll |
| 3 | G2PAManager_ESP.dll |
| 4 | G2PAManager_CHS.dll |

After resolving G2PACreateManager, the wrapper invokes the function pointer with the selector and a wide-character path argument. It stores the loaded module and function pointer in a per-selector cache tree, so repeated manager creation does not repeat the same load/lookup sequence.

The wrapper appends the language DLL filename to the supplied wide-character base path. This establishes that the path passed to the language factory is a manager/resource location, not a lyric string.

The Android port must replace this Windows dynamic-loading mechanism with statically linked language implementations selected by an explicit enum. It must not attempt to load the PE DLLs from Android.

## 2. Language factory layouts are not identical

The factory entry points were decompiled separately. The current report supports these observations:

| DLL | Inferred factory inputs | Allocated object size | Factory-specific initialization |
|---|---|---:|---|
| G2PAManager_JPN.dll | uint32 selector | 0x18 bytes | Two manager vptr fields are initialized; selector is written at offset 0x14 |
| G2PAManager_ENG.dll | uint32 selector | 0x18 bytes | Similar small object; also performs a synchronized one-time initialization routine |
| G2PAManager_KOR.dll | uint32 selector, wchar_t* path | 0x48 bytes | Initializes additional fields and copies the wide path into an object field |
| G2PAManager_CHS.dll | uint32 selector, wchar_t* path | 0x48 bytes | Similar larger object and wide-path initialization |

For the JPN factory, Ghidra shows allocation of 0x18 bytes, two writes of the same G2PA5::G2PAManager vftable address, a zeroed 32-bit field, and the selector at offset 0x14.

The ENG factory follows the same object-size pattern, but also uses a mutex and a guarded one-time initialization path before returning the new object.

The KOR and CHS factories allocate 0x48 bytes. Both receive a wide-character path, call wcslen, and pass that string into an initialization helper. The decompiler shows three consecutive pointer-sized fields being initialized as the string destination. This is consistent with an embedded wide-string-like field, but its precise MSVC string layout and lifetime have not yet been validated.

Important: these are per-DLL factory observations. They do not prove that the complete manager object or its virtual method contract has been recovered.

## 3. Recovered wrapper virtual-dispatch offsets

G2PAManager.dll's exported wrapper functions validate null arguments and dispatch through the manager object's vtable. The byte offsets below are visible in the decompiled wrappers:

| Vtable byte offset | Wrapper operation |
|---:|---|
| 0x00 | destroy |
| 0x10 | defaultLyric |
| 0x18 | inputtingLyrics |
| 0x20 | setLyricsAndPhonemes |
| 0x28 | canConvert |
| 0x30 | setPhonemes |
| 0x38 | setSyllableSegArray |
| 0x40 | deleteNote |
| 0x48 | normalizeAllPhonemes |
| 0x50 | normalizeRangedPhonemes |
| 0x58 | normalizeAllLyrics |

These offsets describe the wrapper's dispatch contract. The signatures and implementations behind the language-specific vtables are still under investigation. In particular, the wrapper stubs do not establish argument ownership or the exact structures used for note and syllable arrays.

## 4. Syllable array wrapper

The wrapper DLL exposes a separate syllable-array surface:

- create/destroy syllable arrays;
- append syllable entries;
- query array length;
- obtain an individual syllable string and phoneme string;
- wrap arrays of syllable arrays returned from a manager.

The report shows runtime type checks before some of these operations and vtable dispatch for size/access methods. This is another reason not to treat the manager return value as an arbitrary C string or a plain buffer.

## 5. G2PA_ESP.dll is a separate API

G2PA_ESP.dll exports a C-like handle-oriented interface (g2paCreate, g2paDelete, g2paPut, g2paGet, g2paGetSyllable, g2paGetDefaultLyric, and g2paGetDefaultPhoneme). Its current decompilation report shows an internal fixed-slot state table and integer handles.

This is not the same module as G2PAManager_ESP.dll selected by the wrapper's language enum. The supplied archive contains G2PA_ESP.dll but does not contain G2PAManager_ESP.dll. Therefore the standalone Spanish API is not being used as a substitute for the JPN/ENG/KOR/CHS manager ABI.

## 6. What is not established yet

The first pass does not yet establish:

- the actual language-manager vtable function targets and their complete signatures;
- the manager's note/phoneme data structures and their ownership rules;
- exact string encodings and normalization semantics for each language;
- initialization and resource-loading requirements beyond the factory observations;
- thread-safety for all per-note operations;
- parity against authorized reference outputs.

A follow-up Ghidra pass now scans read-only data addresses referenced directly by each G2PACreateManager factory, emits candidate vtable function-pointer runs, and adds those methods to the decompilation set. Its report must be reviewed before implementing the native interface.

## 7. Android port decision

The intended Android output is a newly compiled ELF shared library for arm64-v8a, built with Android NDK C/C++ and called through JNI. It is not a binary conversion of the Windows PE files.

The first native target remains the G2P interface layer. The implementation must wait for the language-method vtable report, then define a stable C ABI that owns its own buffers and exposes explicit manager lifecycle, lyric-to-phoneme conversion, normalization, and syllable results.

No Android G2P .so has been built yet. No V7 AI singing renderer has been identified or implemented. G2P conversion alone does not synthesize singing audio.
