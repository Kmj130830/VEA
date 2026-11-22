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

## 7. Second-pass factory-referenced JPN/KOR vtables

The second-pass artifact is attached to [G2P static decompilation run 45037284998](https://github.com/Kmj130830/VEA/actions/runs/45037284998). Artifact digest: `sha256:44ce3723830905ad88c2912cdc3e2819801bc4b50837785457a1d7836c674a3f`.

The pass found factory-referenced candidate vtables at:

| Language | Factory | Object size | Candidate vtable |
|---|---|---:|---:|
| JPN | `G2PACreateManager` at `0x180003300` | `0x18` | `0x180019bc0` |
| KOR | `G2PACreateManager` at `0x180005170` | `0x48` | `0x18000e918` |

These are per-language manager vtables, not the common host `G2PAManagerIF` vtable.

### Korean function-body observations

- Vtable byte offset `0x20` resolves to `FUN_1800052a0` (1,210 bytes). It accepts a note/container, a UTF-16 lyric string, a mode byte, another argument and an output-count pointer. It lazily creates the internal converter, canonicalizes U+30FC and U+2212 to ASCII hyphen-minus, calls `FUN_180001ad0` to build intermediate segments, and applies the resulting phoneme strings through note virtual methods. A failed note update sets manager error field `+0x40` to 4; invalid input sets it to 1.
- Vtable byte offset `0x30` resolves to `FUN_180005780` (98 bytes), which forwards a phoneme string to the note object's virtual setter at `+0x40`.
- Vtable byte offset `0x48` resolves to `FUN_1800058c0` (2,164 bytes). It enumerates notes, normalizes lyric text, builds an internal segment sequence, compares generated phoneme data with stored text and writes changed values. Exact host-facing normalize method identity is not yet proved.
- Vtable byte offset `0x50` resolves to `FUN_180006140` (97 bytes), which obtains the first note from a supplied container and forwards to the object's own vtable `+0x48`. This looks like a ranged wrapper, but the full range structure and semantics are not yet recovered.
- Vtable byte offset `0x58` resolves to `FUN_1800061b0` (25 bytes), which only clears error state and returns success for a non-null argument. It does not implement a visible all-lyrics traversal in this body.

### Japanese function-body observations

The Japanese factory creates a `0x18`-byte object with a candidate vtable at `0x180019bc0`. Its `+0x20` target is `FUN_180005090` (3,166 bytes), a substantial lyric-processing method. Its visible pre-processing changes ideographic space U+3000 to ordinary space and applies `towlower` to the intermediate text before continuing to parser helpers. This should not be applied to Korean or other languages without their own evidence.

The JPN table also contains large helper targets at `+0x18` (5,359 bytes) and `+0x28` (1,343 bytes). Their names and complete contracts have not yet been recovered.

### Porting boundary

The recovered virtual offsets align with the host wrapper's early method sequence, but this does not make the language object binary-compatible with the host object. The Android implementation must expose a new C ABI and portable note/segment structures. Do not copy the Windows MSVC object size, vtable pointers or ownership rules into the ARM64 library.
