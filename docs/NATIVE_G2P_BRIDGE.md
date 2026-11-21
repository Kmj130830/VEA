# Android-native G2P bridge prototype

## Status

The Android app now builds a native shared library named `libveag2p.so` for `arm64-v8a`, with a JNI facade and a small C ABI.

This is **not a converted Yamaha DLL** and is **not yet a Yamaha phoneme converter**. It is the first native-port milestone: a portable bridge boundary plus a limited set of recovered Korean input-preprocessing operations. The engine status must remain unavailable.

## Recovered operation

The Korean language DLL's decompiled lyric-processing path copies UTF-16 input and changes these code points to ASCII hyphen-minus:

| Input | Output |
|---|---|
| U+30FC KATAKANA-HIRAGANA PROLONGED SOUND MARK (`ー`) | U+002D (`-`) |
| U+2212 MINUS SIGN (`−`) | U+002D (`-`) |

The same replacement appears in the Korean manager's lyric processing and phoneme-normalization paths. The Android implementation performs this transformation over validated standard UTF-8. It does not implement Hangul-to-phoneme conversion.

## Recovered ordered Korean lexical replacements

A static read-only-data extraction from the supplied Korean manager identified 32 ordered, three-syllable UTF-16 substring substitutions. The native bridge now applies the complete recovered list after the two code-point canonicalizations above. The order is preserved, and each matching source is replaced repeatedly before the next rule is considered.

Representative verified pairs include:

| Input | Output |
|---|---|
| `디귿이` | `디그시` |
| `지읒이` | `지으시` |
| `키읔을` | `키으글` |
| `의견란` | `의견난` |
| `생산량` | `생산냥` |
| `결단력` | `결딴녁` |
| `공권력` | `공꿘녁` |
| `입원료` | `이붠뇨` |

Host-side tests cover all 32 source/target pairs, repeated occurrences, and unchanged unrelated Hangul. The implementation operates on decoded Unicode code points; all recovered rules are three BMP Hangul syllables on both sides, preserving the original UTF-16 character count.

This is still only an upstream lexical-preprocessing subset. It does **not** implement the subsequent Hangul decomposition, context-sensitive phonological changes, or syllable/phoneme result generation.

## Exact modern Hangul packed-index lookup

The Korean DLL stores 11,172 records for U+AC00 through U+D7A3. Each record is six bytes: a packed 16-bit index value and the corresponding UTF-16 syllable. The packed value can be reproduced from Unicode's modern Hangul L/V/T decomposition without embedding the 67 KB lookup table.

For syllable index `s = codepoint - 0xAC00`:

```text
L = s / 588
V = (s % 588) / 28
T = s % 28

onsetIndex = L + 2
vowelIndex = [3,4,5,6,7,10,11,12,13,14,15,18,19,20,21,22,23,26,27,28,29][V]
codaIndex  = [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,19,20,21,22,23,24,25,26,27,28,29][T]

packed = 0x8000 | (onsetIndex << 10) | (vowelIndex << 5) | codaIndex
```

The sparse coda index deliberately skips slot 18. This is why using the ordinary Unicode trailing-consonant index directly would be incorrect.

The new `vea_g2p_korean_hangul_packed_indices` native helper implements this intermediate lookup. Its host test checks representative values (`가=0x8861`, `각=0x8862`, `간=0x8865`, `갑=0x8873`, `힣=0xD3BD`) and hashes the complete 11,172-value output against the packed values extracted from the supplied DLL's `.rdata` table (`FNV-1a 0x7AE6C4656323F3C6`).

This recovers the packed index table only. The DLL also has three parallel 32-entry pointer tables that turn those indices into base onset, vowel and coda labels. The native bridge now exposes this as `VeaKoreanPhonemeComponents` through `vea_g2p_korean_syllable_components`.

Representative base table outputs are:

| Syllable | Onset | Vowel | Coda |
|---|---|---|---|
| `가` | `g` | `a` | *(empty)* |
| `각` | `g` | `a` | `gp` |
| `갑` | `g` | `a` | `bp` |
| `힣` | `h` | `i` | `dp` |

The component strings are copied from the three static pointer tables, not synthesized from a modern romanization chart. Tests cover the complete base-label output for every modern Hangul syllable in Unicode order; the reference FNV-1a hash is `0xF00557BADDC04EFE`.

These are **base table components**, not final spoken phonemes. The proprietary context rules change some syllables and markers before the segment strings are assembled. Therefore the language-ready mask remains zero and the renderer remains unavailable.

## Android Kotlin access to base components

The JNI bridge now exposes `vea_g2p_korean_syllable_components` to `NativeG2pBridge.inspectKoreanBaseComponents`. For each precomposed modern Hangul character in the lexically normalized input, the Kotlin method returns:

- the normalized lyric's UTF-16 character index;
- the original Hangul syllable character;
- its raw onset, vowel and coda base-table labels.

Non-Hangul characters are omitted from this diagnostic result; their positions remain recoverable through the retained index values. The method name and return type deliberately say *base components*, not *phonemize*: adjacent-syllable rules, marker semantics and final syllable segmentation still have to run before this data could be used as a singing-engine phoneme stream.

## Files

- `app/src/main/cpp/include/veag2p.h`: stable C ABI, status values, ABI version, language readiness mask, Hangul index lookup and base-component output contracts.
- `app/src/main/cpp/veag2p_core.cpp`: UTF-8 validation, two code-point canonicalizations, 32 ordered Korean lexical replacements, packed Hangul-index lookup, and base onset/vowel/coda label tables.
- `app/src/main/cpp/veag2p_jni.cpp`: JNI boundary; converts Java UTF-16 strings without relying on JNI modified UTF-8 and exposes base-component arrays.
- `app/src/main/cpp/CMakeLists.txt`: Android shared-library and host-test targets.
- `app/src/main/java/com/kmj/vea/engine/NativeG2pBridge.kt`: Kotlin facade, including diagnostic `inspectKoreanBaseComponents`.
- `app/src/main/cpp/tests/veag2p_core_test.cpp`: host-side tests for UTF-8 validation, all 32 Korean replacements, repeated matches, the full 11,172-syllable packed-index and base-label hashes, and output-buffer sizing.

## ABI

- Bridge ABI version: `0x00010000`.
- Android ABI target: `arm64-v8a`.
- Complete language phonemizer bitmask: `0`.
- `nativeLanguageReady(...)`: always false until a language engine passes differential tests.
- The Kotlin rendering adapter must continue to return unavailable.

The C ABI accepts explicit input lengths and output capacities. Output buffers are caller-owned; the native function does not retain pointers between calls. Invalid UTF-8 and insufficient buffers have explicit status codes.

## Build and tests

The Android Gradle build uses Android NDK `27.2.12479018` and CMake `3.22.1`. GitHub Actions also builds the portable core on the host and runs its CTest suite before assembling the APK.

The APK packages the shared library only for `arm64-v8a`. The app can load the bridge on supported devices, but it must not label this as a ready G2P or VOCALOID engine.

## Remaining work before a real G2P port

1. Recover the common `G2PAManagerIF` host wrapper and its forwarding contract separately from the per-language `G2PAManager` objects.
2. Resolve the manager's note/container interfaces, UTF-16 string ownership, return/error conventions, and per-language resource loading.
3. Decompile the internal converter/parser helpers and relevant vtables for Japanese, English, Korean, Spanish, and Chinese.
4. Reimplement the confirmed language behavior in portable C++ without copying Windows PE code or MSVC object layouts.
5. Compare phoneme strings, syllable segmentation, default-phoneme insertion and normalization against the authorized Yamaha reference.
6. Mark a language ready only after those differential tests pass on Android ARM64.

G2P is only a lyric/phoneme preparation stage. It does not render an AI singing voice. The VOCALOID7 model runtime and audio-render boundary remain separate, unimplemented work.

## Additional Korean-module findings (static only)

The November 2026 Ghidra analysis of the supplied `G2PAManager_KOR.dll` establishes a more detailed internal path than the narrow replacement above. The DLL was not executed; names below are analysis labels for recovered functions, not vendor symbols.

### Per-lyric processing sequence

The Korean helper `FUN_180007210`:

1. Copies the UTF-16 lyric.
2. Applies 32 ordered substring-replacement passes. Their exact source/destination pairs were recovered from the DLL's read-only data and are now ported in `veag2p_core.cpp` with the original ordering and repeated-match behavior.
3. Calls `FUN_180007390`, `FUN_180008730` and `FUN_180007f80` in that order.
4. Propagates a nonzero helper result through the manager's error path.

The three stages have distinct observable responsibilities:

- `FUN_180007390` creates a working lyric plus per-character marker arrays. It handles separators and punctuation, applies default-character substitutions for some unsupported input, and performs validation/cleanup passes. It also treats `/`, `-`, spaces and `.` specially. The exact user-facing meaning of every marker value is not yet resolved.
- `FUN_180008730` maps Hangul syllables through an 11,172-entry lookup table and applies context-sensitive, adjacent-syllable transformations. The table packs three five-bit fields; the code extracts them at bit positions 10–14, 5–9 and 0–4, then consults separate lookup arrays. Its rules alter the working text and marker arrays, so a direct per-code-point substitution would not reproduce this stage.
- `FUN_180007f80` allocates a result array of wide-string pointers and builds output strings for eligible syllables. It uses separate onset, vowel and coda lookup tables and has additional branches for adjacent-syllable and coda-context cases. The function's result is a sequence of string records, not one flat output string.

### Recovered base output inventory

The DLL's read-only data contains three pointer tables used by `FUN_180007f80`. The following are observed table values, indexed by the packed Hangul fields; they are not asserted to be standard X-SAMPA symbols:

| Component table | Observed non-empty labels |
|---|---|
| Onset | `g`, `g'`, `n`, `d`, `d'`, `l`, `m`, `b`, `b'`, `s`, `s'`, `N`, `c`, `c'`, `ch`, `k`, `t`, `p`, `h`, `sh`, `r`, `sh'` |
| Vowel | `a`, `e`, `ja`, `je`, `7`, `j7`, `o`, `oa`, `ue`, `jo`, `u`, `u7`, `ui`, `ju`, `M`, `Mi`, `i` |
| Coda | `gp`, `np`, `dp`, `rp`, `mp`, `bp`, `Np` |

Some packed indices intentionally point to empty strings; repeated labels also occur at different indices. Do not replace these with a modern romanization table or assume a one-to-one mapping from a familiar IPA/X-SAMPA inventory.

### Syllable boundary is part of the result contract

The common G2PA interface exposes a `SyllableSegArray` object with separate operations for:

- appending an item using two string arguments;
- retrieving the `syllable` string by index;
- retrieving the `phonemes` string by index;
- querying the array size.

The corresponding wrapper entry points are named `SyllableSegArray_append`, `SyllableSegArray_syllable` and `SyllableSegArray_phonemes`. This is strong evidence that the app-side representation must preserve syllable/phoneme pairs rather than flattening a whole Korean lyric into one phoneme string. It also gives a concrete target shape for a future portable Android result type.

### Porting boundary

This evidence is enough to identify the Korean preprocessing stages and the base output inventory, but not enough to mark Korean G2P ready:

- the context-sensitive Hangul transformation rules and all per-character marker meanings still need to be recovered;
- the relationship between the per-syllable strings and the host's syllable/phoneme pair is not yet fully mapped;
- dictionary fallback/default phoneme behavior and failure codes need confirmation;
- differential test vectors from an authorized reference are still required.

Until those conditions are met, keep `nativeLanguageReady(KOR)` false and keep the synthesis renderer unavailable. The static trace does not execute the proprietary code and does not establish a portable ABI.

