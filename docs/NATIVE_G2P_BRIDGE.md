# Android-native G2P bridge prototype

## Status

The Android app now builds a native shared library named `libveag2p.so` for `arm64-v8a`, with a JNI facade and a small C ABI.

This is **not a converted Yamaha DLL** and is **not yet a Yamaha phoneme converter**. It is the first native-port milestone: a portable bridge boundary plus one narrowly recovered input canonicalization operation. The engine status must remain unavailable.

## Recovered operation

The Korean language DLL's decompiled lyric-processing path copies UTF-16 input and changes these code points to ASCII hyphen-minus:

| Input | Output |
|---|---|
| U+30FC KATAKANA-HIRAGANA PROLONGED SOUND MARK (`ー`) | U+002D (`-`) |
| U+2212 MINUS SIGN (`−`) | U+002D (`-`) |

The same replacement appears in the Korean manager's lyric processing and phoneme-normalization paths. The Android implementation performs this transformation over validated standard UTF-8. It does not implement Hangul-to-phoneme conversion.

## Files

- `app/src/main/cpp/include/veag2p.h`: stable C ABI, status values, ABI version and language readiness mask.
- `app/src/main/cpp/veag2p_core.cpp`: UTF-8 validation and the recovered code-point replacement.
- `app/src/main/cpp/veag2p_jni.cpp`: JNI boundary; converts Java UTF-16 strings without relying on JNI modified UTF-8.
- `app/src/main/cpp/CMakeLists.txt`: Android shared-library and host-test targets.
- `app/src/main/java/com/kmj/vea/engine/NativeG2pBridge.kt`: Kotlin facade.
- `app/src/main/cpp/tests/veag2p_core_test.cpp`: host-side tests for UTF-8 validation, exact replacement, and output-buffer sizing.

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

## Korean pre-parser delimiter port

The next Korean-only helper is `KoreanG2pInputTokenizer`, a direct port of
`G2PAManager_KOR.dll` function `FUN_180003310` (`0x3310`). The recovered
loop splits on exactly four UTF-16 code units: ASCII space U+0020, ideographic
space U+3000, horizontal tab U+0009 and line feed U+000A. It collapses
delimiter runs and omits empty tokens. Carriage return U+000D, NBSP U+00A0,
and other Unicode whitespace are not delimiters in this function.

`normalizeAndTokenize()` first applies the Korean manager's separately
recovered U+30FC/U+2212 to ASCII-hyphen replacement, then applies this
delimiter set. `NativeG2pBridge.normalizeKoreanInput()` now has an identical
Kotlin fallback when the ARM64 library is unavailable, while retaining the
native implementation when it is loaded.

The immediately following `FUN_1800034e0` record builder stores each token in
two UTF-16 fields: the first field contains up to 30 code units, and the
second field contains every remaining code unit. This is a fixed code-unit
boundary, not a Unicode code-point boundary; a surrogate pair at position 30
can therefore be split between the two fields. The Android
`KoreanG2pTokenFields` / `splitTokenForNativeModule()` port preserves that behavior
and does not truncate long tokens.

This is only a verified text-preparation primitive. It does not assign
phonemes, syllable boundaries, phone durations or model category IDs. The
vendor's subsequent token-to-internal-module conversion and note-container
update path remain to be ported and differentially tested.

## Newly recovered Korean phoneme pipeline call order

A focused Ghidra report now includes the internal Korean parser entry points
and their direct callers. The recovered sequence inside
`G2PAManager_KOR.dll` is:

`FUN_180007210` → `FUN_180007390` → `FUN_180008730` →
`FUN_180007f80`

`FUN_180007210` first copies the UTF-16 lyric, applies the 32 literal
rewrite pairs through its fixed lookup tables, then calls those three
functions in that order. A nonzero return from any stage is raised through
the manager's error path. This establishes that the previously ported
lexical normalizer and syllable mapper are separated by two substantial
preprocessing/conversion stages; calling the syllable mapper directly on
the original input is not equivalent to the manager pipeline.

The newly inspected stages have these confirmed boundaries:

- `FUN_180007390` clones the normalized working string and creates two
  byte-per-UTF-16-code-unit side arrays. It rewrites special separators,
  marks synthetic/inserted positions and adjusts both arrays whenever the
  working string is compacted or expanded. The outputs are consumed by the
  later converter and syllable-to-phone stage.
- `FUN_180008730` makes a second working-string copy and performs
  context-dependent Hangul syllable transformations. Its rules inspect
  neighboring syllable fields (onset, vowel and coda), rewrite syllable
  keys through the vendor lookup helper, and apply multiple ordered
  assimilation tables.
- `FUN_180007f80` consumes the transformed string plus the two aligned
  per-code-unit flag arrays and converts each syllable to the vendor phone
  strings. It also applies its own cross-syllable rules and the null-onset/
  hyphen flag behavior.

`FUN_180008660` is the shared Hangul lookup primitive used by both
preprocessing stages. With a zero key it finds a character's 16-bit vendor
syllable code; with a nonzero key it resolves the code back to the character.
The Android mapper now implements both directions from the recovered
19×21×28 Unicode decomposition and the vendor's sparse onset/medial/coda
IDs. A full 11,172-syllable round-trip test is included. This reproduces
the code/character lookup only; it does not claim that the two preceding
phonological passes are ported.

The note-update caller is also clearer: `FUN_1800063c0` constructs the
per-note working representation and invokes `FUN_180007210`. The
manager-facing `FUN_180001350` tokenizes the incoming UTF-16 lyric through
`FUN_180003310`, creates the two-field token record with
`FUN_1800034e0`, invokes a language-manager virtual method for each
record, and then copies returned syllable/phoneme strings into its owned
result list. The exact host note-interface ownership and update semantics
still need a separate ABI port.

The third-pass report is static Ghidra evidence only. No Windows DLL was
executed. The Android language readiness mask therefore remains zero until
both upstream transformations, manager-facing note updates and reference
output comparisons are implemented.

## Table-driven Hangul syllable phone mapping

Static analysis of G2PAManager_KOR.dll FUN_180007f80 and its read-only data
recovers six 32-entry UTF-16 lookup tables: initial consonant validity and
phone labels, vowel validity and phone labels, and final consonant validity
and phone labels. The 11,172-entry syllable map at RVA 0xF1E2 was checked
against every precomposed Hangul code point U+AC00..U+D7A3. Every record
matches the arithmetic decomposition into 19 initial, 21 medial and 28
final positions, with the DLL's exact internal table-index mappings.

`KoreanHangulPhoneMapper` now ports these static lookup tables and the
recoverable contextual rules in FUN_180007f80, including sibilant-vowel
substitution, the open-syllable liquid rule, the U+0020 boundary rule, and
the two explicitly consumed per-code-unit flags for hyphen / null-onset
cases. Tests include all 11,172 Hangul syllables and focused context cases.

This is an isolated syllable-to-phone component, not a complete Korean G2P
manager port. The actual manager calls it after `FUN_180007390` and
`FUN_180008730`, which prepare a transformed lyric and two per-code-unit
flag arrays. Those upstream functions and their lexical rewrite tables are
not yet fully ported. The production bridge still reports Korean G2P as
not ready until that pipeline, syllable/note association and reference
outputs are verified.

## Korean lexical pronunciation rewrite table

`FUN_180007210` in the Korean manager applies a fixed 32-entry literal
replacement table to its UTF-16 working string before calling the later
syllable/token preprocessing functions. The table includes consonant-name
inflections (for example, `디귿이` -> `디그시`) and listed lexical
assimilation exceptions (for example, `의견란` -> `의견난`).

`KoreanG2pLexicalNormalizer` ports these 32 exact source/replacement pairs
in table order using literal, case-sensitive replacement. It leaves all
unlisted text, whitespace and punctuation unchanged. The function is kept
separate from `KoreanG2pInputTokenizer` and
`KoreanHangulPhoneMapper`: the former handles input token boundaries, while
the latter two model later string and syllable stages.
