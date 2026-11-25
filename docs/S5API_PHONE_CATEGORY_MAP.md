# S5API phone-category map

## Evidence

Static analysis of the supplied S5API.dll identifies the following path:

- FUN_180003a90 initializes a 14-entry ordered phone-label-group vector.
- FUN_1800111c0 copies those 14 groups to the global vector at DAT_1801c4160.
- FUN_18006c520 iterates the outer group index from 0 upward and stores that
  exact index in each inserted label-tree node at node offset +0x40.
- FUN_180021c50 checks the category map and returns 13 on no match.
- FUN_180074a50 performs alias handling, fallback-string filtering, reads
  the stored context-map uint32 index, and applies four explicit overrides.

This establishes the numeric IDs directly from the native construction and
lookup path. The category vector's outer index is the returned native ID;
it is not inferred from read-only string order.

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

## Numeric category ID proof — 2026-11-25

The ordered group vector is created by FUN_180003a90 with 14 adjacent
0x18-byte group records. The first two groups are the three silence
labels and three break labels. The remaining twelve groups are the phone
arrays and smaller literal sets initialized immediately afterward. At the
end of the constructor, FUN_1800111c0 copies exactly 14 group records into
DAT_1801c4160.

FUN_18006c520 traverses those groups in ascending order. For each label it
inserts a node into the lookup tree and writes the current outer-loop
counter to the node's +0x40 uint32 field. FUN_180074a50 later reads that
field and returns it as the phone category index. Consequently, the native
group ordinals map directly to IDs 0 through 13.

The exact group sizes reconstructed from the constructor are:

| Native ID | Label count | Group |
|---:|---:|---|
| 0 | 3 | sil / Sil / SIL |
| 1 | 3 | br / Br / BR |
| 2 | 76 | vowels, diphthongs, contextual spellings and +1 forms |
| 3 | 6 | n / N / m / ny / my / ng |
| 4 | 7 | g / d / b / gy / dy / by / dh |
| 5 | 4 | z / zh / v / z` |
| 6 | 2 | j / jh |
| 7 | 19 | y / w and +0 glide-vowel aliases |
| 8 | 4 | r / ry / l / 4 |
| 9 | 8 | k / t / p and palatal/aspirated forms |
| 10 | 9 | s / sh / h / C / f and related fricatives |
| 11 | 8 | ch / ts and related affricates |
| 12 | 1 | cl |
| 13 | 1 | junk |

The Android category-map test now checks all 14 native group sizes and
resolves every member against its native group ordinal. It accounts for the
four explicit FUN_180074a50 overrides, where `Sil` and `SIL` are stored in
group 0 but return category ID 1.
