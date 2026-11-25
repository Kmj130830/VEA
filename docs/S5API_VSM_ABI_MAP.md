# VSM to S5API renderer ABI map

Date: 2026-11-19

## VSM to S5API dynamic import table

The VSM resolver at FUN_180086750 performs GetProcAddress lookups in this exact sequence. This is the order names are written into the VSM module function-pointer table. The VSM struct stores the loaded module handle at offset +0x150 and the 28 resolved API pointers at offsets +0x158 through +0x230 (8-byte stride).

| Slot | S5API export | S5API RVA | Recovered signature |
|---:|---|---:|---|
| 0 | Func_4cd84bb7 | 0x8d470 | uint32* (void*, int) |
| 1 | Func_7b0b2f07 | 0x92500 | void (void*) |
| 2 | Func_ab21a6d2 | 0x97fa0 | uint64 (int64, uint32*) |
| 3 | Func_9cbce37f | 0x97c20 | uint64 (int64, int64) |
| 4 | Func_cfc85a30 | 0x99fe0 | uint64 (int64, int64, int64*) |
| 5 | Func_5fd62874 | 0x8e350 | int64 (void*, int) |
| 6 | Func_68617beb | 0x914f0 | void (void*) |
| 7 | Func_95d13855 | 0x97ba0 | uint64 (int64, int32*) |
| 8 | Func_efe389ac | 0x9aa60 | int (int64, int64*) |
| 9 | Func_417eec3e | 0x85d10 | uint64 (int64, int64*, int64*) |
| 10 | Func_236a1ecf | 0x84f00 | uint32* (int64, void**) |
| 11 | Func_95031259 | 0x97b60 | void (void*) |
| 12 | Func_52f85f15 | 0x8e340 | uint32 (int64) |
| 13 | Func_2db29822 | 0x85540 | uint64 (int64, int64*, float*) |
| 14 | Func_47a12a73 | 0x873a0 | int64 (uint64, int) |
| 15 | Func_34c88d60 | 0x85bf0 | uint64 (int64, int32*) |
| 16 | Func_b8d5dd29 | 0x989a0 | uint32 (int64, int64*) |
| 17 | Func_e7f8fafc | 0x9a270 | uint64 (int64, int64*, void**) |
| 18 | Func_2c6cd280 | 0x854e0 | void (void*) |
| 19 | Func_b2d96da8 | 0x97fb0 | int (int64, int64*) |
| 20 | Func_133d38ab | 0x84040 | uint64 (int64, int64*, int64*) |
| 21 | Func_6b47c126 | 0x91610 | int64 (int64, void**, int64*) |
| 22 | Func_0425ac3f | 0x83ec0 | void (void*) |
| 23 | Func_a5e8ddbb | 0x97e00 | int (int64) |
| 24 | Func_92864e29 | 0x925f0 | uint64 (int64, int64*, float*) |
| 25 | Func_0ecc5890 | 0x83ff0 | void** (uint64, uint32) |
| 26 | Func_2c8b9800 | 0x85510 | void (int64*) |
| 27 | Func_2b8131bb | 0x85460 | uint32 (void) |

RVA values are relative to PE image base 0x180000000, not file offsets. Prototypes are Ghidra-recovered and should be treated as provisional until call sites confirm argument and ownership semantics.

## Render-path observations

### Main sample-block processor
Func_92864e29 is the strongest candidate for the S5API render hot path:
- Recovered signature: uint64 Func_92864e29(int64 context, int64* event, float* block).
- It reads a block length from the render context at context + 0x10 + 0x58.
- It accesses two float planes at block[i] and block[i + 0x100].
- It updates output planes in fixed 256-sample addressing patterns (0x100 floats per plane).
- It performs pitch/power trace paths and invokes FUN_1801361f0.
- The two planes' channel semantics, interleaving, and final VSM audio-buffer format are not yet proven. Do not label them left/right until callback ABI is verified.

### Input/event preparation
Func_417eec3e transforms records from a 0x28-byte input stride into internal 0x38- and 0x58-byte records, copies lyric strings, and invokes ordering/normalization helpers. It allocates a 16 MiB aligned scratch region. This is upstream feature/event preparation, not the final audio buffer writer.

### Render-context setup
Func_6b47c126 allocates and initializes a large render context, copies pitch/event/control arrays, configures timing/power fields, and sets up biquad coefficients. It also contains optional high-pass and silence-gate configuration. This is context creation/setup, not standalone synthesis.

### Model/configuration initialization
Func_47a12a73 creates an approximately 0x21d8-byte object and parses a hierarchical configuration structure with many string-key lookups. Exact schema and association with .vtb2/.vpit/.vhnv/.vtmg remain open.

## Porting boundary status
- Original VSM and S5API modules are Windows PE32+ AMD64.
- VSM dynamic function order and S5API RVA/signature inventory are recovered at Ghidra decompiler level.
- A portable Android implementation still needs verified field layouts, function semantics, model tensor contracts, and audio callback data lifetime.
- No binary PE-to-ELF conversion is attempted. A working Android arm64-v8a renderer must be rebuilt from portable source or reimplemented and validated against reference renders.


## Exported input-record preparation: Func_417eec3e

The VSM resolver loads Func_417eec3e as S5API function-table slot 9 (the
module handle occupies the preceding slot). The Ghidra body for
Func_417eec3e accepts an input vector and builds two owned internal
representations. The observations below are byte-layout facts only; the
field semantics and ownership of the copied qwords are not assigned.

### Observed 0x28-byte source record

| Source offset | Observed operation |
|---:|---|
| +0x00 | 8 bytes copied unchanged to both internal record forms |
| +0x08 | 8 bytes copied unchanged to both internal record forms |
| +0x10 | float32 read and converted to float64 in the 0x58-byte form |
| +0x14 | byte copied to destination +0x48 in the 0x58-byte form |
| +0x15 | byte copied to destination +0x49 in the 0x58-byte form |
| +0x16 | NUL-terminated byte string, deep-copied into owned string storage |
| +0x20 | 32-bit value copied into the 0x38-byte form at +0x30 and into an inner field of the 0x58-byte form |

The source vector advances in 0x28-byte steps. One derived internal vector
uses a 0x38-byte stride; a second uses a 0x58-byte stride. The 0x58-byte
form stores the float from +0x10 as a double at +0x50 and preserves the
bytes from +0x14/+0x15 at +0x48/+0x49. The function also consults render
configuration and builds additional per-record state; this table is not a
complete description of its work.

### Separate render-dispatch observation

FUN_1801361f0 invokes Func_92864e29 with a pointer advancing by 0x28 bytes
for each work item, while its float output destination advances by 0x800
bytes. This proves a 0x28-byte stride at that render-dispatch call site.
It does NOT yet prove that this is the same logical record layout consumed
by Func_417eec3e, despite the matching stride.

### Android port boundary

VEA must not serialize these records yet. The qwords at +0x00/+0x08 may be
process-local references, and the receiver-side contract, object lifetime,
and relation between the two 0x28-stride uses remain unresolved. The current
Android implementation therefore keeps VsmScheduledNote and lyricTokens as
owned app-side values; it does not manufacture native pointers or claim that
these records are already a portable S5API input.

## Corrected 0x38-byte note event rows and phoneme classifier

The 0x38-byte intermediate row is not seven float64 values. Its confirmed
layout is interval start at `+0x00`, interval end at `+0x08`, a 32-byte
MSVC x64 `std::string` at `+0x10`, and a uint32 at `+0x30`.
`Func_417eec3e` constructs the owned string from the source record's
NUL-terminated bytes at `+0x16`; it copies the source uint32 from `+0x20`.

The three preprocessing helpers have these observed behaviors:

- `FUN_180007740`: rewrites initial `br` to `sil`; subsequent `br` can
  extend a preceding `sil` interval.
- `FUN_180007a90`: removes rows with the exact string `-`.
- `FUN_180007ec0`: keeps only intervals at least 0.001 seconds long.

`FUN_18003ab70` iterates the rows at a 0x38-byte stride. It calculates
frame coverage from the start/end pair, then calls
`FUN_180074a50(row + 0x10, context, nullptr)` with the string object.
`FUN_180074a50` maps the phone label to an internal category index using
special aliases, canonical string comparisons and a global string/index
lookup tree. The index is subsequently used in frame-feature construction.

The analyzed S5API `.rdata` contains label strings such as `sil`, `br`,
`a`, `i`, `u`, `e`, `o`, `wo`, `ey`, `aw`, `ay`, `ow`, `oy`, `aa`, `uh`,
`iy`, `ih`, `ah`, `uw`, `eh`, `ae`, `er`, `ao`, `i_n`, `uei`, `iE_n`,
`yE_r`, `@N`, `u@N`, `@U`, `ia`, `i@U`, `aI`, `uaI`, `iAN`, `iUN`,
`n`, `N`, `m`, `ng`, `g`, `d`, `z`, `zh`, `v`, `r`, `l`, `k`, `t`, `p`,
`s`, `sh`, `h`, `f`, `ch`, `ts`, `cl`, and `junk`, plus variants.

The numeric map construction is now confirmed: FUN_180003a90 builds
14 ordered groups, FUN_1800111c0 copies them to DAT_1801c4160, and
FUN_18006c520 stores the outer group ordinal in each label-tree node at
+0x40. FUN_180074a50 reads and returns that stored uint32. These numeric
IDs are therefore distinct from the separate alias-to-canonical string
table.

This supersedes the earlier seven-double/five-key interpretation. Android
must represent the upstream event as an interval plus an owned phone-label
string and separately retain the unexplained uint32/auxiliary source fields.

### Exact endpoint propagation in the `-` removal pass

The compaction in `FUN_180007a90` has a specific boundary rule: after
removing rows whose label is exactly `-`, each retained row's end field
(offset `+0x08`) is overwritten with the **next retained row's start**
(offset `+0x00`). The final retained row keeps the endpoint from the last
row in the pre-filter list, even when that final source row was removed
because its label was `-`. `FUN_180007ec0` then removes rows whose resulting
duration is below the native `0.001f` threshold.

Android's `VsmPhoneEventPreprocessor` mirrors that order and endpoint rule.

### Exact read-only alias tables used by `FUN_180074a50`

The two pointer tables are stored in `.data` and are iterated as NUL-terminated
label aliases before the global string/index tree lookup. Their contents can
be read directly from the supplied S5API PE image:

**Alias table A — 55 entries (`0x1801C3080` through `0x1801C3237`)**

```text
i_n, uei, iE_n, yE_r, @N, u@N, @U, ia, i`, @`, i\, i@U, aI, uaI,
y_n, 7, iN, iE_r, ua_n, iUN, AN, ei, AU, uAN, a_n, iAN, y{_n,
u@_n, @_n, uo, UN, iAU, ua,
ia+0, iE_r+0, yE_r+0, ua+0, uo+0, iAU+0, i@U+0, uaI+0, uei+0,
iAN+0, iUN+0, iE_n+0, y{_n+0, u@N+0, u@_n+0, ua_n+0, uAN+0,
y|CH, a|CH, i|CH, u|CH, o|CH
```

**Alias table B — 29 entries (`0x1801C3240` through `0x1801C3327`)**

```text
p_h, ts\, s\, ts`, k_h, z`, t_h, ts_h, ts`_h, ts\_h, x, s`,
ia+1, iE_r+1, yE_r+1, ua+1, uo+1, iAU+1, i@U+1, uaI+1, uei+1,
iAN+1, iUN+1, iE_n+1, y{_n+1, u@N+1, u@_n+1, ua_n+1, uAN+1
```

`FUN_180074a50` compares incoming labels against these arrays before
falling through to its normalized-string and global tree logic. The suffixes
and separators are preserved exactly; they are not stripped by this alias
scan. The arrays establish special lexical aliases but do not establish a
numeric phoneme-ID sequence. Runtime ID assignment remains a separate
global-tree initialization question.

### Exact alias-to-canonical phone map created by `FUN_180001010`

Direct disassembly of `FUN_180001010` shows 43 calls to
`FUN_1800066f0(destination, keyString, canonicalString)`, followed by
`FUN_180010990(&DAT_1801c4140, records)`. `FUN_1800066f0` constructs the
two owned string fields of each 0x40-byte pair record; `FUN_180010990`
inserts those key/value pairs into the global ordered tree at
`DAT_1801c4140`. `FUN_180074a50` consults that tree only to canonicalize
the special alias forms listed above. The exact mappings recovered from
the call-site pointers are:

| Alias key | Canonical phone label |
|---|---|
| `ts`_h` | `ts` |
| `ts\\_h` | `ts` |
| `x` | `h` |
| `s`` | `sh` |
| `ia+0` | `y` |
| `iE_r+0` | `y` |
| `yE_r+0` | `y` |
| `ua+0` | `w` |
| `uo+0` | `w` |
| `iAU+0` | `y` |
| `i@U+0` | `y` |
| `uaI+0` | `w` |
| `uei+0` | `w` |
| `iAN+0` | `y` |
| `iUN+0` | `y` |
| `iE_n+0` | `y` |
| `y{_n+0` | `y` |
| `u@N+0` | `w` |
| `u@_n+0` | `w` |
| `ua_n+0` | `w` |
| `uAN+0` | `w` |
| `ia+1` | `ah` |
| `iE_r+1` | `eh` |
| `yE_r+1` | `y` |
| `ua+1` | `ah` |
| `uo+1` | `aa` |
| `iAU+1` | `aw` |
| `i@U+1` | `ow` |
| `uaI+1` | `ay` |
| `uei+1` | `ay` |
| `iAN+1` | `aw` |
| `iUN+1` | `uw` |
| `iE_n+1` | `aw` |
| `y{_n+1` | `aw` |
| `u@N+1` | `ow` |
| `u@_n+1` | `w` |
| `ua_n+1` | `aw` |
| `uAN+1` | `aw` |
| `y|CH` | `i` |
| `a|CH` | `a` |
| `i|CH` | `i` |
| `u|CH` | `u` |
| `o|CH` | `o` |

**Important separation:** `DAT_1801c4140` is the global alias-to-canonical
string tree. It is not, by itself, the render-context phone-code table.
After an alias is rewritten, `FUN_180074a50` performs a second lookup
against the context map passed in its `param_2` argument. The returned
32-bit one-hot slot is read from the first four bytes of that context
map's value string. Thus neither the 43-row alias list nor its string
order supplies numeric category IDs. The context map's construction and
its encoded value strings remain the next reverse-engineering target.