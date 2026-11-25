# S5API phone-category map

## Evidence

Static analysis of the supplied S5API.dll identifies the following path:

- FUN_180003a90 initializes the ordered phone-label groups.
- FUN_18006c520 builds the label-to-index lookup map.
- FUN_180021c50 checks categories 0 through 12 and returns 13 on no match.
- FUN_180074a50 performs alias handling, fallback-string filtering, and four explicit overrides.

This documents the Windows PE's statically recovered behavior. It is not a runtime trace and does not establish that every language package uses an identical active mapping.

## Category order

| Index | Recovered labels / representative members |
|---:|---|
| 0 | sil, Sil, SIL |
| 1 | br, Br, BR |
| 2 | Vowels, diphthongs, vowel-context spellings, and +1 forms (76 exact entries in code) |
| 3 | n, N, m, ny, my, ng |
| 4 | g, d, b, gy, dy, by, dh |
| 5 | z, zh, v, z` |
| 6 | j, jh |
| 7 | y, w, and +0 glide-vowel aliases (19 exact entries in code) |
| 8 | r, ry, l, 4 |
| 9 | k, t, p, ky, ty, py, p_h, k_h |
| 10 | s, sh, h, C, f, hy, hh, th, s-backslash |
| 11 | ch, ts, ts-backslash, ts variants, aspirated ts/t forms |
| 12 | cl |
| 13 | junk / unmatched fallback |

The full literal tables, including punctuation-sensitive spellings, are stored in S5ApiPhoneCategoryMap.kt.

## Exact overrides and normalization

FUN_180074a50 returns category 1 directly for exact normalized spellings SIL, Sil, Br, and BR. Lowercase sil remains category 0. Lowercase br belongs to category 1 through the static group table.

For non-alias labels, the native fallback path retains only ASCII A-Z, a-z, and the character 4 before performing the category lookup. The Android port applies the recovered alias map first, preserves exact punctuation-bearing labels present in the static groups, then applies this filtering and the four overrides.

## Numeric category indices: constructor order is confirmed

The numeric indices are not inferred from the read-only string order.
`FUN_180003a90` constructs the ordered vector of 14 phone groups and
publishes it through `FUN_1800111c0`. `FUN_18006c520` traverses that vector
from its first group to its last, maintains a group counter starting at zero,
and writes that counter into each inserted phone-map node at offset
`+0x40`. Therefore the runtime lookup IDs are exactly the group ordinals
0 through 13, matching the Android table and its tests.

The feature descriptor's category stride at `+0x10` is a separate value:
the category map proves that at least 14 slots are required, but the stride
must not be assumed to equal 14 without reading the active descriptor.

## Integration boundary

VsmAlignedPhoneFeaturePreparer now returns a concrete categoryIndex in the range 0..13 for each already aligned VsmPhoneEvent. Unknown labels receive the native fallback category 13.

This index does not by itself complete the acoustic feature row. The category vector width, context-window counts, optional feature flags, and their active render-state descriptor values still have to be read from the actual model/render context. The number of categories must not be mistaken for the category vector width.

G2P and per-phone alignment remain separate. The editor's note-level phoneme display string is not split into fabricated phone intervals.