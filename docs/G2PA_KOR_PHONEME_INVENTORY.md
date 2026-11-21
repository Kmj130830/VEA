# Yamaha Korean G2PA static analysis: phoneme inventory and pipeline

Date: 2026-11-21

## Evidence scope

Source: the Ghidra 12.2 static decompilation artifact for `G2PAManager_KOR.dll`. The DLL was not executed. The function labels below are analyst-assigned Ghidra labels, not vendor symbols or a verified ABI.

The Korean module exposes `G2PACreateManager` at RVA `0x5170`. Its manager vtable candidate at `0x18000e918` points to the internal methods listed in the Ghidra report. The manager method `FUN_1800058c0` consumes a list-like phoneme object and iterates its string entries; exact public method semantics and ownership still require call-site confirmation.

## Korean text-to-phoneme pipeline

The recovered internal preprocessing function `FUN_180007210`:

1. Copies the input UTF-16 string.
2. Applies 32 ordered string replacement pairs from pointer tables `PTR_DAT_18001f9c0` and `PTR_DAT_18001f9c8`.
3. Calls `FUN_180007390` for the first Hangul/jamo transformation pass.
4. Calls `FUN_180008730` for a second context-sensitive transformation pass.
5. Calls `FUN_180007f80` to map the transformed Hangul syllables into the phoneme-token sequence.

The final mapper builds a list of per-syllable UTF-16 strings at object offsets `+0x30` and `+0x2C`. It does not simply concatenate the input syllable strings into one output word. Spaces and hyphens have dedicated handling in the preprocessing and final mapping paths.

## Recovered ordered pre-normalization replacements

The 32 source/replacement pairs referenced by `FUN_180007210` were recovered from the two pointer tables at `PTR_DAT_18001f9c0` and `PTR_DAT_18001f9c8`. They are applied in this exact order, replacing all occurrences of each source before moving to the next pair.

| # | Source | Replacement |
|---:|---|---|
| 1 | 디귿이 | 디그시 |
| 2 | 디귿을 | 디그슬 |
| 3 | 디귿에 | 디그세 |
| 4 | 지읒이 | 지으시 |
| 5 | 지읒을 | 지으슬 |
| 6 | 지읒에 | 지으세 |
| 7 | 치읓이 | 치으시 |
| 8 | 치읓을 | 치으슬 |
| 9 | 치읓에 | 치으세 |
| 10 | 키읔이 | 키으기 |
| 11 | 키읔을 | 키으글 |
| 12 | 키읔에 | 키으게 |
| 13 | 티읕이 | 티으시 |
| 14 | 티읕을 | 티으슬 |
| 15 | 티읕에 | 티으세 |
| 16 | 피읖이 | 피으비 |
| 17 | 피읖을 | 피으블 |
| 18 | 피읖에 | 피으베 |
| 19 | 히읗이 | 히으시 |
| 20 | 히읗을 | 히으슬 |
| 21 | 히읗에 | 히으세 |
| 22 | 의견란 | 의견난 |
| 23 | 임진란 | 임진난 |
| 24 | 생산량 | 생산냥 |
| 25 | 결단력 | 결딴녁 |
| 26 | 공권력 | 공꿘녁 |
| 27 | 동원령 | 동원녕 |
| 28 | 상견례 | 상견녜 |
| 29 | 횡단로 | 횡단노 |
| 30 | 이원론 | 이원논 |
| 31 | 입원료 | 이붠뇨 |
| 32 | 구근류 | 구근뉴 |

VEA now implements this exact ordered substitution stage in `S5KoreanG2pPreprocessor`, with a unit test covering all 32 pairs. This is not yet the complete Korean G2P port: the two contextual transformation passes and the manager output contract remain separate work.

## Hangul syllable table

The KOR module contains a 11,172-entry Hangul syllable table (0x2BA4 entries), beginning at RVA `0xF1E2`, with a six-byte record stride.

Observed record fields:

| Record offset | Size | Evidence |
|---:|---:|---|
| +0x00 | uint16 | Packed onset/vowel/coda indices |
| +0x02 | uint16 | Hangul syllable code unit used for lookup |
| +0x04 | uint16 | Not assigned a semantic name in this trace |

The packed 16-bit field is decoded as:

- onset index: bits 10..14, `(packed >> 10) & 0x1F`
- vowel index: bits 5..9, `(packed >> 5) & 0x1F`
- coda index: bits 0..4, `packed & 0x1F`

The three indices select the onset, vowel and coda tables. The G2P code linearly searches this 11,172-entry table by Hangul code unit; a future Android implementation should use a generated direct-index table rather than reproduce the linear scan.

## Android decomposition implementation

VEA now implements the equivalent arithmetic decomposition for modern precomposed Hangul syllables:

```text
offset = codePoint - 0xAC00
initial = offset / (21 * 28)
medial = (offset % (21 * 28)) / 28
final = offset % 28

nativeOnsetIndex = initial + 2
nativeVowelIndex = [3,4,5,6,7,10,11,12,13,14,15,18,19,20,21,22,23,26,27,28,29][medial]
nativeCodaIndex = final + 1
```

The reverse lookup uses the same 19×21×28 Hangul composition grid. Native intermediate coda indices 0, 30 and 31 are normalized to the no-coda form, matching the mask behavior in `FUN_180008660`.

The Kotlin tests round-trip all 11,172 modern Hangul syllables through the recovered native indices. This verifies the decomposition table independently of the still-unported contextual sound-change passes.

## Exact raw phoneme aliases

The three UTF-16 pointer tables used by `FUN_180007f80` are:

- onset aliases: `PTR_DAT_18000ece0`
- coda aliases: `PTR_DAT_18000ede0`
- vowel aliases: `PTR_DAT_18000eee0`

The raw strings below are copied from the referenced read-only data. They are Yamaha KOR G2PA aliases, not asserted to be X-SAMPA or IPA.

### Onset table

| Index | Alias | Index | Alias |
|---:|---|---:|---|
| 2 | `g` | 3 | `g'` |
| 4 | `n` | 5 | `d` |
| 6 | `d'` | 7 | `l` |
| 8 | `m` | 9 | `b` |
| 10 | `b'` | 11 | `s` |
| 12 | `s'` | 13 | `N` |
| 14 | `c` | 15 | `c'` |
| 16 | `ch` | 17 | `k` |
| 18 | `t` | 19 | `p` |
| 20 | `h` | 26 | `sh` |
| 27 | `N` | 28 | `r` |
| 29 | `sh'` | | |

### Vowel table

| Index | Alias | Index | Alias |
|---:|---|---:|---|
| 3 | `a` | 4 | `e` |
| 5 | `ja` | 6 | `je` |
| 7 | `7` | 10 | `e` |
| 11 | `j7` | 12 | `je` |
| 13 | `o` | 14 | `oa` |
| 15 | `ue` | 18 | `ue` |
| 19 | `jo` | 20 | `u` |
| 21 | `u7` | 22 | `ue` |
| 23 | `ui` | 26 | `ju` |
| 27 | `M` | 28 | `Mi` |
| 29 | `i` | | |

### Coda table

| Index | Alias | Index | Alias |
|---:|---|---:|---|
| 2 | `gp` | 3 | `gp` |
| 4 | `gp` | 5 | `np` |
| 6 | `np` | 7 | `np` |
| 8 | `dp` | 9 | `rp` |
| 10 | `gp` | 11 | `mp` |
| 12 | `mp` | 13 | `dp` |
| 14 | `dp` | 15 | `mp` |
| 16 | `rp` | 17 | `mp` |
| 19 | `bp` | 20 | `bp` |
| 21 | `dp` | 22 | `dp` |
| 23 | `Np` | 24 | `dp` |
| 25 | `dp` | 26 | `gp` |
| 27 | `dp` | 28 | `bp` |
| 29 | `dp` | | |

Empty indices are null entries in the native pointer tables. Some indices are changed by the contextual transformation pass before the final mapper selects aliases; therefore these tables alone are not a complete G2P implementation.

## Porting implications

- Preserve these aliases exactly when constructing a VEA Korean phoneme inventory. Do not silently translate them to X-SAMPA.
- Keep per-syllable output boundaries until the VSM phoneme-service contract is verified.
- The 11,172-entry decomposition table can be represented as a generated compact lookup table in Android.
- Porting only the final mapper is insufficient: the ordered 32 replacements and both contextual transformation passes affect the result.
- Do not expose the G2PA manager through JNI until the manager's virtual-method input/output object layout, error codes, and lifetime are confirmed.

## Remaining work

1. Recover the 32 ordered replacement pairs.
2. Translate `FUN_180007390` and `FUN_180008730` into explicit, tested Korean phonological rules.
3. Trace the manager's vtable calls from VSM's phoneme service and confirm the exact output object format.
4. Compare representative Korean words against an authorized native reference output before claiming parity.

This report records static evidence only. It does not claim that the Android app can currently perform Korean G2P or Megpoid vocal synthesis.
