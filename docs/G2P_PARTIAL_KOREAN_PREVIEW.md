# Partial Korean G2P preview

## Implemented stages

The Android project now exposes a clearly marked, preview-only Korean path
through `KoreanG2pPreviewEngine`:

1. The verified input normalization replaces U+30FC and U+2212 with ASCII
   hyphen-minus.
2. The Korean manager's exact token delimiters are applied: ASCII space,
   ideographic space, TAB and LF.
3. The 32 literal lexical replacements recovered from
   `G2PAManager_KOR.dll` `FUN_180007210` run in their original table order.
4. The Hangul syllable tables and inter-syllable rules recovered from
   `FUN_180007f80` map each Hangul syllable to its phone-string group.
5. The result is split into individual phone labels for inspection.

The preview can also consume an app-owned `VsmRenderSchedule` and return
note-scoped results preserving note ID, sample start, duration, sample rate,
and lyric. It does not modify the note's stored phoneme field or its timing.

## Explicit incompleteness

The native high-level Korean path calls two stages before the final syllable
mapper:

- `FUN_180007390`: creates source-character flags and performs upstream
  normalization.
- `FUN_180008730`: transforms the intermediate lyric/string representation.

Neither stage is ported yet. The preview therefore runs
`KoreanHangulPhoneMapper.phonemizeSyllables` using its zero-flag behavior.
Hyphen-related commands may consequently be omitted, and punctuation or
non-Hangul characters not supported by the final mapper are reported as
ignored. This preview is not equivalent to the complete Yamaha Korean G2P
manager.

## Model-category compatibility

The preview reports every phone label that maps to fallback category 13 in
the currently recovered static S5API phone-category table. For example, the
Korean final mapper can produce labels such as `gp`; the current static
category inventory does not list that spelling. The actual per-render
category map and its language-specific configuration still need to be
verified.

Consequently, preview output is not fed automatically into the Megpoid
feature builder. The caller must first review ignored characters and fallback
labels, and the active S5API category map must be confirmed compatible.
Note-to-phone timing remains a separate alignment step; the equal-sample
subdivision used by the explicit-phoneme aligner is only a temporary
heuristic, not Yamaha G2P timing.

## Android API

- `KoreanG2pPreviewEngine.preview(lyric)` returns a single diagnostic result.
- `KoreanG2pPreviewEngine.previewSchedule(schedule, "Korean")` returns
  note-scoped diagnostic results.
- `PreparedMegpoidRuntime.previewKoreanG2p(schedule, language)` exposes the
  same operation from a prepared model package.

Every result carries `isComplete = false` and lists the two missing native
stages. The engine status remains unavailable for production synthesis until
the full G2P path, active feature descriptor, acoustic forward graph and
audio output path are implemented and validated.
