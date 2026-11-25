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

## Reverse-engineering notes for the two missing stages

The successful static report shows that `FUN_180007390` is not a simple
normalizer:

- It allocates a mutable UTF-16 working string and two byte arrays with
  capacity tied to the source UTF-16 length.
- Its first scan treats hyphen and slash as control characters. It can
  replace source characters with Hangul filler `아` and writes flag values
  including 1 and 2 into the parallel arrays.
- It looks up the recovered internal syllable code for each character and
  applies context-sensitive changes using the initial/medial/final code
  fields.
- Later passes validate code points, remove or rewrite spaces, turn period
  markers into word spacing, mark hyphen positions in a separate flag array,
  and compact duplicate/edge spaces.

The next stage, `FUN_180008730`, copies the transformed string and uses the
same internal Hangul lookup tables to inspect adjacent syllable codes. It
rewrites consonant/vowel combinations by calling `FUN_180008660`, including
special rules gated by the source flag array. The function is a sizeable
ordered rule set; its numeric internal IDs should not be renamed to phoneme
labels until the referenced tables and each branch's data flow are mapped.

The existing preview intentionally does not emulate these behaviors. In
particular, its zero-flag output must not be mistaken for a neutral/default
Yamaha G2P result. A full port needs the mutable string plus both flag arrays
as one state object, then separate, tested implementations of
`FUN_180007390` and `FUN_180008730` before the existing final mapper can
be called in production mode.

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
