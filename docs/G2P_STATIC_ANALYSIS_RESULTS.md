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

A second Ghidra pass has now scanned read-only data addresses referenced directly by the JPN and KOR `G2PACreateManager` factories, emitted candidate language-manager vtables, and decompiled their virtual method targets.

- Second-pass workflow: https://github.com/Kmj130830/VEA/actions/runs/45037284998
- Artifact: `VEA-G2P-decompilation-reports`
- Artifact digest: `sha256:44ce3723830905ad88c2912cdc3e2819801bc4b50837785457a1d7836c674a3f`
- The KOR and JPN reports include the factory-referenced virtual targets. No language DLL was executed.

The next native interface work can use the method map below as a candidate contract, but the note/container interfaces and ownership rules still need independent recovery.

## 6A. Second-pass JPN/KOR manager-vtable findings

The second pass recovered language-specific vtable candidate tables from the factory return objects. This is separate from the host-facing `G2PAManagerIF` object and its wrapper thunk offsets.

| Language DLL | Factory entry | Allocation | Candidate vtable address |
|---|---:|---:|---:|
| JPN | `G2PACreateManager` at `0x180003300` | `0x18` | `0x180019bc0` |
| KOR | `G2PACreateManager` at `0x180005170` | `0x48` | `0x18000e918` |

Both vtables contain targets at the same early virtual byte offsets as the host wrapper's dispatch contract, but the language-specific function bodies and object fields are not the same as the host object.

### Korean candidate methods

| Language-vtable byte offset | Target | Size | Directly observed behavior |
|---:|---:|---:|---|
| `0x00` | destructor | — | Releases the internal-module object and copied path before freeing the manager |
| `0x08` | status/default helper | — | Returns a manager status/error field |
| `0x10` | default lyric | 15 B | Returns a static wide-character lyric and clears status |
| `0x18` | inputting-lyrics helper | 10 B | Small state helper; full semantics not established |
| `0x20` | `FUN_1800052a0` | 1,210 B | Parses UTF-16 lyric text into intermediate segments and writes generated phoneme strings to note objects |
| `0x28` | `FUN_180005760` | 22 B | Candidate `canConvert` method; current body returns false rather than demonstrating conversion |
| `0x30` | `FUN_180005780` | 98 B | Sets a phoneme string on a note through the note object's `+0x40` virtual method |
| `0x38` | `FUN_1800057f0` | 15 B | Clears an output count; no syllable-array construction is established by this body alone |
| `0x40` | `FUN_180005800` | 186 B | Candidate note-delete method; checks note language and calls a container operation |
| `0x48` | `FUN_1800058c0` | 2,164 B | Traverses note entries, builds an internal sequence from lyric text, compares phoneme data, and conditionally writes updated note phonemes |
| `0x50` | `FUN_180006140` | 97 B | Forwards a note/container operation to the helper at its own vtable `+0x48`; likely the ranged-normalization wrapper path, but exact semantics are not proved |
| `0x58` | `FUN_1800061b0` | 25 B | Clears status and returns success for non-null input; it does not show a full all-lyrics normalization pass |

`FUN_1800052a0` accepts a note/container interface, a UTF-16 lyric string, a mode byte, an additional argument and an output-count pointer. It clears the manager error field at object offset `+0x40`; invalid arguments set error `1`. It lazily constructs the `G2PAInternalModule` and converter, canonicalizes U+30FC (`ー`) and U+2212 (`−`) to U+002D (`-`), then calls `FUN_180001ad0` to prepare intermediate segments. It walks the note sequence, retrieves the generated phoneme text, and updates the note through a virtual setter. A failed note update sets error `4`. The exact structures behind the note and segment vtables remain unresolved.

`FUN_1800058c0` also performs the two character substitutions. It enumerates note entries, builds a sequence from selected lyrics, invokes the internal conversion path, compares generated text with the stored phoneme string, and writes changed phoneme values through the note interface. This is stronger evidence of a note-normalization/reconciliation pass than a simple string replacement, but the exact correspondence to `normalizeAllPhonemes` versus `normalizeAllLyrics` still requires the host wrapper's call path to be followed.

### Japanese candidate methods

| Language-vtable byte offset | Target | Size | Evidence
|---:|---:|---:|
| `0x00` | `FUN_180003340` | 10 B | destructor/helper slot
| `0x08` | `FUN_180003350` | 4 B | status helper
| `0x10` | `FUN_180003b80` | 15 B | default-lyric helper
| `0x18` | `FUN_180003b90` | 5,359 B | large input-state/processing helper
| `0x20` | `FUN_180005090` | 3,166 B | principal Japanese lyric-processing candidate
| `0x28` | `FUN_180005cf0` | 1,343 B | language-specific helper; full contract not yet mapped
| `0x30` | `FUN_180006240` | 135 B | language-specific phoneme/update helper candidate
| `0x38` | `FUN_1800062d0` | 581 B | syllable/note helper candidate
| `0x40` | `FUN_180006520` | 266 B | note-operation candidate
| `0x48` | `FUN_180006630` | 516 B | normalization candidate
| `0x50` | `FUN_180006840` | 457 B | ranged operation candidate
| `0x58` | `FUN_180006f70` | 25 B | small validation/state helper

`FUN_180005090` normalizes ideographic space U+3000 to U+0020 and lowercases its intermediate text before invoking parser/converter helpers. This is direct Japanese-path behavior in this build; it is not evidence that the same normalization applies to Korean.

### ABI and implementation boundary

The vtable entries are recovered from factory-referenced read-only data and their bodies are decompiled static evidence. They are not a public Yamaha C++ header. Do not copy the `0x18` or `0x48` MSVC object layouts into Android, and do not call Windows x64 function addresses from the Android process. The Android port still requires a portable note-container model, explicit UTF-8/UTF-16 conversion, owned output buffers and per-language differential tests.

## 7. Android port decision

The intended Android output is a newly compiled ELF shared library for arm64-v8a, built with Android NDK C/C++ and called through JNI. It is not a binary conversion of the Windows PE files.

The first native target remains the G2P interface layer. The implementation must wait for the language-method vtable report, then define a stable C ABI that owns its own buffers and exposes explicit manager lifecycle, lyric-to-phoneme conversion, normalization, and syllable results.

No Android G2P .so has been built yet. No V7 AI singing renderer has been identified or implemented. G2P conversion alone does not synthesize singing audio.
