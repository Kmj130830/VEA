# Megpoid V7 render-input boundary (static trace)

This note records the verified boundary between the high-level S5API render path and the native sequence model. It deliberately does not assign meanings to opaque arguments or structure offsets without a complete data-flow proof.

## Confirmed call chain

- `Func_92864e29` at `0x1800925f0` is a high-level S5API render function (body size 21,864 bytes in the analyzed build).
- It obtains a model/context handle through `FUN_1801269f0`; that wrapper allocates a pointer slot and stores the result of `FUN_1800ccce0`.
- The high-level function invokes `FUN_180127330` and then `FUN_180126c20` on that handle.
- `FUN_180127330` validates the handle and forwards to `FUN_1800aeea0`. The latter writes a 32-bit value to offset `+0x3678) of the underlying context. The semantic name of this value is not yet proven.
- `FUN_180126c20` validates its handle and forwards to `FUN_1800aec40`.
- `FUN_1800aec40` resolves the underlying context, obtains the active render length through `FUN_1800c4730`, copies the value at context offset `+0x624), then calls `FUN_1800c7ad0`.
- `FUN_1800c7ad0` is the actual native model/render execution routine. Its second argument is retained as the destination buffer and is passed to `FUN_18012c740` near the end of the function, confirming that this argument is the final output area rather than the neural input feature tensor.
- At entry, `FUN_1800c7ad0` also calls `FUN_18012b150`, `FUN_18012b470` and `FUN_18012b450` using pointers stored in the render context. Their exact roles and the provenance of those context buffers are the next data-flow targets.

## Important distinction

The third argument passed by the high-level caller to `FUN_180126c20` points to the caller's output area at `this + 0x1a130`; the native render routine later writes the generated output through the output-copy helper. It is therefore not evidence that this address contains the neural input feature tensor.

The current Kotlin sequence-transition pipeline starts from an already-prepared feature tensor and implements the recovered Dense/normalization, Temporal, Banked and Residual stages. It is not yet connected to the native note/phoneme-to-feature preparation path or the final output/audio path.

## Next reverse-engineering targets

1. Trace the writes to the underlying render-context fields consumed by `FUN_1800c7ad0` before inference.
2. Identify the public setters that populate note, phoneme, duration, pitch and language/voice conditioning data.
3. Recover the exact tensor layout and chunk/overlap contract at the point where the native sequence model consumes those features.
4. Only then add a Kotlin feature builder and connect it to `S5MegpoidSequenceTransitionPipeline`.

## Evidence

Static decompilation of `S5API.dll`:
- `Func_92864e29`: `S5API_RENDER_HOT_PATH.md`
- `FUN_180127330`, `FUN_180126c20`: `S5API_RENDER_HOT_PATH.md`
- `FUN_1800aeea0`, `FUN_1800aec40`, `FUN_1800c7ad0`: `S5API_MODEL_RUNTIME.md`

No target native code was executed. Opaque offsets and argument roles remain provisional unless explicitly stated above.


## Newly traced upstream event-preparation path — 2026-11-21

The VSM S5API resolver loads Func_417eec3e in function-table slot 9. Its
exported body consumes a 0x28-stride source-record vector and constructs
two owned record vectors with 0x38 and 0x58 strides. In the branch where
the context mode at param_1 + 0xCE4 is zero, it calls:

```text
Func_417eec3e
  -> build 0x38-stride records
  -> FUN_18003ab70(context + 0xD0, ..., records, recordCount)
  -> FUN_18006c720(...)
  -> FUN_180076080(...) / FUN_180077520(...) for frame/history processing
```

The call sites establish this as an upstream event-to-intermediate-data
path. The names and exact tensor meaning of the intermediate arrays are
not proven yet. The alternate mode branch follows a different helper path.

Separately, FUN_1801361f0 invokes Func_92864e29 with a record pointer
advancing by 0x28 bytes and a float output destination advancing by
0x800 bytes per work item. The matching 0x28 stride does not, by itself,
prove that this render-dispatch record is the same structure as the
Func_417eec3e input record.

### Android status

VEA currently implements the app-owned note timeline and the observed
ASCII-space lyric tokenization. It does not yet build the 0x28 VSM/S5API
source record, call Func_417eec3e, or construct the intermediate feature
arrays. Pointer ownership and the mapping between source lyric tokens,
phoneme commands, render-context fields and the four model feature slots
remain unresolved.

### Next trace targets

1. Recover FUN_18006c720's feature construction inputs, output layout and
   position/frame geometry.
2. Recover FUN_180076080 and FUN_180077520's workspace/history contracts,
   including state reset and buffer lifetime.
3. Connect the resulting feature tensors to the existing Dense/Global
   Normalization -> Temporal -> Residual pipeline only after their
   dimensions and ordering are confirmed.

## Corrected 0x38-byte event-row layout and phoneme lookup

The latest Ghidra decompilation corrects the earlier seven-double reading.
The row stride is 0x38 bytes, but the row is a structured record, not an
array of seven doubles:

| Offset | Size | Confirmed field |
|---:|---:|---|
| `+0x00` | 8 bytes | interval start, consumed as float64 |
| `+0x08` | 8 bytes | interval end, consumed as float64 |
| `+0x10` | 0x20 bytes | owned MSVC x64 `std::string` object |
| `+0x30` | 4 bytes | copied source integer; its semantic role is not yet named |

`Func_417eec3e` builds this row by copying the two leading 8-byte fields,
deep-copying the NUL-terminated source string from the 0x28-byte input
record's `+0x16` field into the string object at `+0x10`, and copying the
source uint32 at `+0x20` to row `+0x30`. The string object occupies 32 bytes;
its inline buffer/pointer, length and capacity are not five numeric feature
values.

### Confirmed preprocessing of the string-bearing rows

Before `FUN_18003ab70`, three helpers transform the 0x38-byte row list:

- `FUN_180007740` handles the special token `br`: an initial `br` row is
  rewritten to `sil`; a later `br` can extend a preceding `sil` interval.
- `FUN_180007a90` removes rows whose token string is exactly `-` and
  preserves the final interval endpoint during compaction.
- `FUN_180007ec0` discards rows shorter than 0.001 seconds.

`FUN_18003ab70` then reads the first two fields as the interval. It calls
`FUN_180074a50(row + 0x10, context, nullptr)` on the string object to obtain
a phoneme/category index. That index affects the per-frame auxiliary value;
it is not a five-double key. `FUN_18006c720` separately constructs the
feature matrix using the interval and render-context dimensions.

### Phoneme label classification

`FUN_180074a50` is a string-to-category lookup. It checks special aliases
and canonical labels, applies a limited character filter for fallback
matching, and searches a global string/index map. The S5API read-only data
contains labels including `sil`, `br`, `a`, `i`, `u`, `e`, `o`, `wo`,
`ey`, `aw`, `ay`, `ow`, `oy`, `aa`, `uh`, `iy`, `ih`, `ah`, `uw`, `eh`,
`ae`, `er`, `ao`, `i_n`, `uei`, `iE_n`, `yE_r`, `@N`, `u@N`, `@U`,
`ia`, `i@U`, `aI`, `uaI`, `iAN`, `iUN`, `n`, `N`, `m`, `ng`, `g`, `d`,
`z`, `zh`, `v`, `r`, `l`, `k`, `t`, `p`, `s`, `sh`, `h`, `f`, `ch`,
`ts`, `cl` and `junk`, plus positional variants.

The table establishes the vocabulary family, but the current report does
not yet establish every runtime integer index or the initialization path
for the global lookup tree. Do not assume that the read-only string order
equals the classifier's numeric ID order.

### Android consequence

VEA should model each event as `(startSeconds, endSeconds, phoneLabel,
sourceFlags)` rather than seven doubles. The existing `VsmEventInterval`
already covers the first two values. The next implementation step is to
connect a verified phone-label string to the classifier's numeric map and
recover the meaning of the source uint32 and auxiliary 0x28-record fields.
Until then, do not serialize a private 0x38/0x58 record or claim a
model-ready phoneme index.

## Android phone-event preprocessor

VEA now has an owned data model `VsmPhoneEvent(startSeconds, endSeconds,
phoneLabel, opaqueSourceU32)` and `VsmPhoneEventPreprocessor`. This ports
the confirmed native helper order without attempting to build phone events
from note-level display text:

1. Break normalization: exact `br` becomes `sil`; when a `br` follows an
   existing `sil`, the previous silence row is extended through the break.
2. Rest-marker removal: exact `-` rows are removed. Each remaining row's
   end is updated to the next remaining row's **start**; the last retained
   row inherits the final pre-filter endpoint.
3. Short-event filter: intervals shorter than native float32 `0.001f`
   seconds are discarded.

The uint32 copied to row `+0x30` is retained bit-for-bit as
`opaqueSourceU32`; its semantics have not been assigned. The unit tests
cover leading/non-leading `br`, silence merging, exact `-` removal,
endpoint adjustment, case sensitivity and the 1 ms threshold.

This preprocessor starts from already timed phone events. It does not
perform G2P, split `VeaNote.phoneme`, recover phone alignment, or assign
the internal category ID returned by `FUN_180074a50`.

## Confirmed phone-event feature builder — FUN_18006c720

The decompilation of FUN_18006c720 establishes the direct consumer of the
0x38-byte event-row array. Its `param_3` is traversed with a seven-qword
stride (`row = param_3 + index * 7`), which is exactly 0x38 bytes. The
earlier `double*` decompiler type is only a pointer type: not all seven
qwords are doubles.

For each row, the function reads:

- qword 0 (`+0x00`) as interval start;
- qword 1 (`+0x08`) as interval end;
- the MSVC string object at qword 2 (`+0x10`) and passes it to
  `FUN_180074a50` to obtain a render-context phone-category index;
- the remaining row storage is retained in the seven-qword stride, but
  its complete semantic field names are not assigned here.

The function allocates a zero-initialized float32 output matrix with
`positions * featureWidth` values. For each phone row it builds at least
these verified feature groups:

1. A continuous duration-derived feature. It computes a logarithmic
   duration value, normalizes it between logarithms of two caller-supplied
   limits, and clamps the result to [-1, +1].
2. A categorical one-hot feature at an offset determined by the render
   configuration and the category index returned by `FUN_180074a50`.
3. Optional neighboring-phone categorical features. It clamps the
   neighboring event index to the available event range, calls
   `FUN_180074a50` for that event's string, and sets the corresponding
   category slot in the neighboring feature region.
4. An optional two-value flag pair whose orientation depends on the
   third output of `FUN_180074a50` and a render-mode flag.

The function has two layout branches selected by its mode argument. The
exact source names for all mode flags, feature widths and auxiliary
category regions remain unresolved; the numeric feature positions must
come from the live render-context descriptor, not from the read-only label
string order.

### Consequence for the Android implementation

This confirms that `VsmPhoneEventPreprocessor` is an upstream preparation
stage, not the feature builder itself. A correct Android feature builder
needs, in order:

1. An ordered, already aligned list of individual phone labels and their
   start/end intervals.
2. The exact per-render-context `phoneLabel -> categoryIndex` map used by
   `FUN_180074a50` (not yet recovered).
3. The render-context feature-width, context-window and mode descriptors
   that parameterize `FUN_18006c720` (not yet completely mapped).
4. A portable float32 implementation of the duration normalization,
   categorical placement and optional neighbor windows.

VEA does not yet have the phone alignment/G2P output needed for item 1,
nor the numeric category map needed for items 2-3. Therefore this stage
has not been connected to `S5MegpoidSequenceTransitionPipeline`; doing so
with guessed category IDs or equal note-duration splits would create
invalid model inputs.

## Upstream VSM/G2PA phoneme-service boundary

The VSM export inventory exposes three relevant phoneme-service entry
points:

- `VIS_VSM_PhonemeService_setG2PAManager` at `0x180038ac0`;
- `VIS_VSM_PhonemeService_setLyricsAndPhonemesByLangID` at `0x180038b50`;
- `VIS_VSM_PhonemeService_setPhonemesByLangID` at `0x180038ba0`.

The focused VSM decompilation shows these are thin interface wrappers.
`setG2PAManager` dispatches through the phoneme-service object's vtable
at `+0x08`; `setLyricsAndPhonemesByLangID` dispatches at `+0x50`; and
`setPhonemesByLangID` dispatches at `+0x60`. The export wrappers validate
their receiver and selected arguments, but do not contain the phoneme
generation algorithm.

The supplied `G2PAManager.dll` export inventory separately exposes
`WG2PAManager_setLyricsAndPhonemes`, `WG2PAManager_setPhonemes`,
`WG2PAManager_setSyllableSegArray`, `normalizeAllPhonemes`, and
`normalizeRangedPhonemes`. Their wrapper bodies dispatch to manager
vtable offsets `+0x20`, `+0x30`, `+0x38`, `+0x48`, and `+0x50`
respectively. It also exposes syllable-segment-array operations for
creation, append, size, syllable-string retrieval and phoneme-string
retrieval.

These API names establish a layered pronunciation interface with
separate lyric-to-phoneme, explicit-phoneme, normalization and syllable
segmentation operations. They do not, on their own, establish the
resulting phone ordering, per-phone time alignment, phone-label encoding,
or the exact meaning of the opaque arguments passed through the
forwarding wrappers.

### Android consequence

VEA's next pronunciation dependency is not a new heuristic splitter for
`VeaNote.phoneme`. It is a portable implementation of the recovered
language-specific G2PA contract, including the manager's syllable/phone
result representation, followed by the VSM-equivalent conversion of that
ordered output to timed `VsmPhoneEvent` rows. Until these contracts are
recovered, the app must retain editor phoneme text separately and must not
pretend that an X-SAMPA display string is already a renderer-ready phone
sequence.

## Confirmed phone-label classifier lookup behavior — FUN_180074a50

The 2026-11-21 focused Ghidra report now contains the complete body of
`FUN_180074a50` (1,308 bytes). The three arguments are:

- `param_1`: an MSVC x64 string object holding the phone label;
- `param_2`: a pointer to the active render-context phone-category tree;
- `param_3`: optional one-byte output. When present, it is set according
  to whether the original label begins with `!`.

The lookup flow is now established:

1. Compare the original string against the two exact read-only alias
   pointer-table ranges at `0x1801C3080..0x1801C3237` and
   `0x1801C3240..0x1801C3327`.
2. If no direct alias-table match is taken, build a filtered fallback
   string using the character classes visible in the function body.
3. Search the active render-context tree using `FUN_18000b100` and
   verify an exact string match with `FUN_180026f60`.
4. If the key is not present in the context tree, consult the separate
   global alias-to-canonical tree rooted at `DAT_1801c4140`. When an
   alias is found, copy its canonical string and retry the context-tree
   lookup.
5. On an exact context-tree match, return the 32-bit value stored in the
   matched tree node's value field. Four additional short special-label
   comparisons return the fixed value `1`; their literal byte strings
   are not assigned semantic names in this note.

The tree search is a lower-bound lookup followed by an exact comparison.
The global 43-entry alias map and the active context's label-to-uint32
map are therefore two distinct structures with different responsibilities.

### What remains unknown

The classifier's lookup and return behavior are now confirmed, but the
constructor/populator for the active context tree has not yet been
recovered. The current trace shows `FUN_1800591d0` calling
`FUN_180050aa0` while preparing the render context; the next focused
analysis explicitly seeds that initializer and its adjacent subobject
constructors. Until that population path is recovered, no numeric phone
IDs are hardcoded in Android.