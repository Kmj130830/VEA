# G2P static findings — Ghidra analysis

## Scope and evidence

These observations come from Ghidra 12.2 headless reports generated for the six allow-listed G2P DLLs in `Editor dll.zip`. The analysis is static: the PE modules were imported into Ghidra; no target DLL was executed.

The reports are attached to:
- First pass: GitHub Actions run 44430339143.
- Second pass with virtual-table candidate recovery: run 44431058683.
- Third pass adds supplemental seeds for language parser/converter helpers: run 44431862910 (artifact status must be checked after completion).

The decompiler's prototypes and recovered class fields are analysis hypotheses. The observations below distinguish direct code behavior from inferred semantics.

## 1. Two separate manager layers

The initial slot table needs an important distinction: the common DLL and the per-language DLL do not expose one shared C++ object.

### Host-facing common layer

`G2PAManager.dll` is a Windows host-side adapter/loader.

Its exports include:
- `VIS_G2PAManager_WG2PAManagerIF_create`
- `VIS_G2PAManager_WG2PAManagerIF_destroy`
- `VIS_G2PAManager_WG2PAManagerIF_createManager`
- `VIS_G2PAManager_WG2PAManagerIF_createSyllableSegArray`
- `VIS_G2PAManager_WG2PAManager_defaultLyric`
- `VIS_G2PAManager_WG2PAManager_inputtingLyrics`
- `VIS_G2PAManager_WG2PAManager_setLyricsAndPhonemes`
- `VIS_G2PAManager_WG2PAManager_canConvert`
- `VIS_G2PAManager_WG2PAManager_setPhonemes`
- `VIS_G2PAManager_WG2PAManager_setSyllableSegArray`
- `VIS_G2PAManager_WG2PAManager_deleteNote`
- `VIS_G2PAManager_WG2PAManager_normalizeAllPhonemes`
- `VIS_G2PAManager_WG2PAManager_normalizeRangedPhonemes`
- `VIS_G2PAManager_WG2PAManager_normalizeAllLyrics`

The common `WG2PAManagerIF_create` routine allocates a 0x68-byte interface object, assigns the `G2PA5::G2PAManagerIF` vtable and initializes list/state fields. Exported wrapper functions dispatch through this host-facing interface object's vtable.

The byte offsets below are therefore the **host-facing interface dispatch offsets**, not offsets that may be copied blindly into the per-language manager class:

| Interface vtable offset | Host-facing method |
|---:|---|
| `0x00` | Destructor |
| `0x10` | Default lyric |
| `0x18` | Inputting-lyrics state |
| `0x20` | Set lyrics and phonemes |
| `0x28` | Can convert |
| `0x30` | Set phonemes |
| `0x38` | Set syllable-segment array |
| `0x40` | Delete note |
| `0x48` | Normalize all phonemes |
| `0x50` | Normalize ranged phonemes |
| `0x58` | Normalize all lyrics |

Some exported wrappers read additional arguments from stack locations that Ghidra cannot fully type from the short thunk. The exact source-level C++ prototypes, references, object layouts, ownership rules, and error semantics remain to be confirmed.

### Per-language manager layer

The common loader chooses one of the language DLLs, constructs a DLL path under the supplied wide-character directory, calls `LoadLibraryW`, resolves `G2PACreateManager` through `GetProcAddress`, caches the module/factory by language key, and invokes the factory.

Recovered language key to file mapping:

| Key | DLL |
|---:|---|
| 0 | `G2PAManager_JPN.dll` |
| 1 | `G2PAManager_ENG.dll` |
| 2 | `G2PAManager_KOR.dll` |
| 3 | `G2PAManager_ESP.dll` |
| 4 | `G2PAManager_CHS.dll` |

These language-specific manager objects have their own vtables and internal converter objects. They are not interchangeable with the common `G2PAManagerIF` object.

## 2. Per-language factory observations

| DLL | Allocation size | Directly observed initialization |
|---|---:|---|
| ENG | `0x18` | Assigns a manager vtable, stores a 32-bit language argument, and performs one-time synchronized initialization of static language data |
| JPN | `0x18` | Assigns a manager vtable and stores a 32-bit argument |
| KOR | `0x48` | Assigns a manager vtable, stores a 32-bit argument, initializes string state, copies the supplied wide-character path, and clears an error field |
| CHS | `0x48` | Similar 0x48-byte object with copied wide-character path and an error field |

The sizes and offsets are decompiler observations for these builds, not a public C++ ABI. The difference between ENG/JPN and KOR/CHS is evidence that factories require language-specific handling.

## 3. Korean language-manager method evidence

The second-pass report recovered the Korean manager vtable referenced by `G2PACreateManager`. Its principal function bodies show the following behavior:

- The destructor checks and destroys an owned internal-module object, releases the copied wide-character path, then frees the manager.
- A small method returns a 32-bit status/error field at object offset `+0x40`.
- The default-lyric method clears the status field and returns a pointer to a static wide-character value.
- The lyric-processing method is a 1,210-byte function that accepts a note/container interface, a UTF-16 lyric string, flags, and an output count pointer. It creates an internal module and converter object on demand, normalizes two Unicode code points, calls a parser helper (`FUN_180001ad0` in this build), iterates the generated segments, and writes phoneme strings back through a note object's virtual method. The output count is incremented as note entries are processed.
- A separate 2,164-byte method iterates note entries and performs phoneme normalization / consistency checks through additional internal interfaces.
- Other vtable methods delegate to note-container and internal-module operations. Their complete method signatures and ownership contracts still need call-site review.

The directly observed Korean input substitutions in the lyric-processing and normalization paths are:

| Input code point | Replacement |
|---|---|
| U+30FC `ー` | U+002D `-` |
| U+2212 `−` | U+002D `-` |

This exact preprocessing behavior is the only Korean G2P-related function ported into the initial Android native bridge. It is not Hangul-to-phoneme conversion.

## 4. Japanese, Chinese and English observations

- CHS has the same broad 0x48-byte manager shape as KOR but its main lyric-processing and normalization methods differ. Its decompiled code contains handling for ideographic space U+3000 and case-folding operations in an internal text path. The complete behavior must be traced before porting.
- JPN uses a 0x18-byte manager. Its vtable contains large language-processing methods (including a 5,359-byte function in this build), so the Japanese backend is not merely a small lookup wrapper.
- ENG uses a 0x18-byte manager and a much larger 9.6 MB DLL. Its factory performs synchronized initialization of static data. The report includes a much larger set of language-specific methods and helper calls.
- The ESP interface is in the separate `G2PA_ESP.dll`, which exports `g2paCreate/Delete/Get/GetDefaultLyric/GetDefaultPhoneme/GetSyllable/Put`. It needs its own ABI mapping.

These observations show why one language's object layout or G2P implementation cannot be used as a drop-in replacement for the other languages.

## 5. Android ARM64 bridge prototype

The repository now has an Android NDK/JNI build path and the `libveag2p.so` target for `arm64-v8a`. The C ABI accepts length-delimited standard UTF-8 buffers and returns explicit status values. The first implementation ports only the Korean U+30FC/U+2212 replacement described above.

The bridge reports:
- ABI version `0x00010000`;
- complete language phonemizer mask `0`;
- all language engines not ready.

The Android build is not a converted Windows DLL. It is a new ARM64 library containing a small independently implemented, evidence-backed primitive. The app's synthesis engine remains unavailable.

See `docs/NATIVE_G2P_BRIDGE.md` for the C ABI, CMake/JNI files and test coverage.

## 6. Native port requirements and next gates

Before any language is marked ready:
1. Recover the host-facing common `G2PAManagerIF` forwarding path separately from the per-language class.
2. Decompile internal parser/converter helpers and all relevant language-specific vtables.
3. Establish the note collection, note object, and syllable-segment interfaces, including exact parameter widths and ownership.
4. Establish resource-file loading, initialization sequence, Unicode semantics, default-phoneme insertion, and error codes.
5. Implement the confirmed behavior in portable C++ with explicit UTF-8 buffers and caller-owned results; do not copy MSVC C++ object layout into JNI.
6. Compare normalized lyrics, phoneme strings, syllable boundaries, note updates and error cases against the authorized Yamaha reference.
7. Validate performance and memory use on Android ARM64.

G2P is only lyric/phoneme preparation. It does not synthesize audio. The VOCALOID7 AI renderer, model loading/runtime and audio-render interface remain separate unresolved work.
