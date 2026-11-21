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
