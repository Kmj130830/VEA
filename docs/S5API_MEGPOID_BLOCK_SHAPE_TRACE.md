# AI Megpoid V7 model-block shape trace

Date: 2026-11-20

## Scope and evidence

This note records a static Ghidra trace of the supplied S5API.dll and correlates
it with the existing Android scalar residual depthwise/MLP operator. The vendor
PE was not executed. The trace establishes constructor call relationships and
operator shape formulas; it does not establish which model tensors are the
weights for each runtime pointer until the shared typed-tensor loader is
reconciled.

Primary functions:

- `FUN_1800589d0` at RVA `0x589d0`: model-block array construction.
- `FUN_1800537e0` at RVA `0x537e0`: per-entry model-block construction.
- `FUN_180052f90` at RVA `0x52f90`: repeated nested block configuration.
- `FUN_1800c5d50` at RVA `0xc5d50`: depthwise/normalization/MLP/residual runtime.
- `FUN_1800c67e0` at RVA `0xc67e0`: history/ring-buffer and depthwise preparation.
- `FUN_1800c3740` at RVA `0xc3740`: linear projection.
- `FUN_18005e9b0`: shared typed object/tensor loader; its complete call contract
  remains the next sub-trace.

## Model construction chain

### Per-entry model array

The direct caller `FUN_1800589d0` reads a configuration array and obtains its
entry count from the descriptor at `param_3 + 0x0c` (decompiler's
`param_3[3]`). It allocates one `0x3c0)-byte context record per entry. For
each array entry it copies descriptor fields into that record, computes the
first two size-like values as `param_3[0] / 2` and `param_3[1]`, and calls
`FUN_1800537e0(record + 0x128, entryObject, localDescriptor, param_4)`.

This is direct evidence that `FUN_1800537e0` initializes one model entry from
an object-tree entry plus a descriptor. The values are retained as neutral
dimension fields here; their semantic labels are not inferred from names.

### Repeated nested block records

`FUN_1800537e0` allocates a block-record array with stride `0x200`. Its count
comes from the descriptor field at byte offset `+0x14`. It then iterates the
corresponding object array and calls:

```c
FUN_180052f90(blockRecords + index * 0x200,
              blockObject,
              &localDescriptor,
              allocatorContext);
```

The same constructor reads fixed hashed object-tree keys and passes selected
subobjects to `FUN_18005e9b0` at several distinct destination offsets in the
model-entry context. The keys therefore represent separate setup properties
and nested configuration objects, not nine interchangeable weight arrays.

Inside `FUN_180052f90`, the descriptor-derived intermediate values include:

- `(dim0 - 1) * dim3`, or `(dim0 / 2) * dim3` when a descriptor flag is clear;
- `2 * dim1` for another intermediate size.

Those values are forwarded into nested typed-object construction. Until the
`FUN_18005e9b0` implementation and its caller arguments are fully mapped, these
must not be renamed channels, kernels, or expansion sizes.

## Runtime residual depthwise/MLP shape contract

The decompilation of `FUN_1800c5d50` gives the following dimension relationships.
Let:

- `C = *param_1`
- `E = param_1[1]`
- `N = *(uint32_t *)((uint8_t *)param_2 + 0x1c)`

Then:

- depthwise/statistics tensor length is `C * N`;
- expanded feature count is `C * E`;
- first projection maps `C -> C * E` over `N` positions;
- GELU is applied to each of the `C * E` feature rows, each of length `N`;
- second projection maps `C * E -> C` over `N` positions;
- the residual affine merge operates on `C * N` values.

The runtime function calls `FUN_1800c67e0` to prepare history/depthwise data,
then calls `FUN_1800c3740` once for expansion and once for projection. The
statistics reduction is over `C * N`, and the variance/epsilon calculation
matches the existing native scalar kernel. This aligns with the current
Android native function:

`vea_s5_run_residual_dw_mlp_block_f32`.

The Android function's expected buffers are:

| Buffer | Element count |
|---|---:|
| history rows | `C * 7 * N` |
| depthwise weights | `C * 49` |
| expansion weights | `(C * E) * C` |
| expansion bias A/B | `C * E` each |
| projection weights | `C * (C * E)` |
| residual input | `C * N` |
| residual scale/bias | `C` each |

This contract is derived from the observed runtime projection dimensions and
the existing Android C++ function signature. It does not yet identify the
matching object-tree paths for each buffer.

## Primary matrix and secondary layer property

The follow-up decompilation of `FUN_180063ca0` corrects an earlier
interpretation: the two fixed hashes passed through `FUN_18005e9b0` are not
two matrix tensors.

- `FUN_180052f90` constructs two fixed lookup keys and passes them with a
  selected layer object to `FUN_18005e9b0`.
- `FUN_18005e9b0` copies those key strings and calls `FUN_180063ca0`.
- `FUN_180063ca0` resolves its first key and creates a bank of
  `NVGen::BlockMatrix16` / `BlockMatrix32` objects. Their per-matrix shape
  is initialized from descriptor arguments `param_7` (rows) and
  `param_6` (columns); `param_8` is the number of matrix objects in the
  bank. The typed payload is loaded from `*F32` or `*F16` representation.
- After the primary matrix load, the function conditionally resolves its
  second key when the descriptor's `param_5` value is nonzero and passes
  the resulting object node to `FUN_180064e90`. This is a separate
  secondary-property path, not a second BlockMatrix construction in the
  observed body of `FUN_180063ca0`.

The exact behavior of `FUN_180064e90` is being traced separately. Therefore
the two Android fields intentionally retain neutral names:
`S5MegpoidModelPropertyPair.first` (primary matrix payload) and
`S5MegpoidModelPropertyPair.second` (secondary property payload). The API
pairs them only when each fixed key has exactly one typed tensor leaf under
the same immediate parent object. Ambiguous and incomplete groups are omitted.

## Descriptor relationship between the two properties

The full `FUN_180063ca0` and `FUN_180064e90` bodies establish a stronger
shape invariant for the paired properties.

For the first property, `FUN_180063ca0` creates `param_8` BlockMatrix objects.
Each matrix is initialized with `param_7` rows and `param_6` columns. The
F32/typed-double fill loops traverse those rows and columns and write one
value into each matrix object per coordinate. Therefore:

```
KEY_1 elements = param_7 * param_6 * param_8
```

For the second property, the same function calls:

```c
FUN_180064e90(secondaryNode, 1, param_7, outputDescriptor, ...);
```

The helper stores column count `param_7`, row count `1`, and padded column
stride `align_up(param_7, 8)`. It supports tagged double arrays and `*F32`
or `*F16` encoded payloads, converting the output to float32 storage.
Therefore:

```
KEY_2 elements = param_7
KEY_1 elements / KEY_2 elements = param_6 * param_8
```

The FUN_180052f90 caller supplies the descriptor fields as
`param_6 = dim1`, `param_7 = 2 * dim1`, and `param_8 = dim0). The resulting
sizes can therefore be written as:

```
KEY_1 elements = 2 * dim1 * dim1 * dim0
KEY_2 elements = 2 * dim1
KEY_1 / KEY_2 = dim1 * dim0
```

The Kotlin index exposes this integer quotient as
`primaryToSecondaryElementRatio`. It is null when either tensor is empty or
the primary count is not divisible by the secondary count. The quotient
validates the combined `dim1 * dim0` factor, but does not separate those two
dimensions or identify the semantic role of either property.

This relationship is specific to the recovered `FUN_180063ca0` constructor
path. It must not be generalized to the separate runtime residual block
unless a call/data-flow trace explicitly links the constructed matrices to
that block's pointers.

## Repeated-block descriptor shape recovered at the call site

The raw instruction listing of `FUN_180052f90` resolves the repeated-block
call at `0x180053421`. At that site RSI points to the per-block descriptor.
Immediately before the call, the code loads:

- `[RSI + 0x04]` as the first matrix dimension;
- twice that value as the second matrix dimension;
- `[RSI]` as the matrix-bank count.

These are passed to `FUN_18005e9b0` as dimensions `param_5`,
`param_6`, and `param_7`. The wrapper forwards them as
`FUN_180063ca0` parameters `param_6` (columns), `param_7` (rows), and
`param_8` (matrix count). Consequently, for this call path:

```
columns = descriptor[+0x04]
rows = 2 * descriptor[+0x04]
matrixCount = descriptor[+0x00]

KEY_1 elements = rows * columns * matrixCount
KEY_2 elements = rows
KEY_1 / KEY_2 = columns * matrixCount
```

The corresponding Kotlin shape candidate is:

```
rows = KEY_2 elements
columns = rows / 2
matrixCount = (KEY_1 elements / KEY_2 elements) / columns
```

The index exposes this through `repeatedBlockMatrixShapeOrNull()`. A shape
is returned only when KEY_2 is even, the element-count quotient is integral,
and the reconstructed `rows * columns * matrixCount` equals the KEY_1
element count.

The real model audit reports that **69 of the 88** paired objects match this
repeated-block shape. Their inferred matrix-count distribution is:

| Matrix count | Paired objects |
|---:|---:|
| 1 | 18 |
| 2 | 14 |
| 3 | 15 |
| 5 | 16 |
| 6 | 6 |

The remaining **19 pairs do not match this specific call-site formula**.
They remain structural property pairs but are not assigned this matrix shape.
The model constructor contains other `FUN_18005e9b0` call sites with
different dimension tuples, so those pairs require a separate call-site map.

The same audit grouped those 88 pairs by the first-level object-tree
section containing each pair. This is a structural grouping only; the opaque
hashes are kept as labels rather than assigned semantic component names.

| Top-level model key | Pairs | Repeated-block matches | Single-bank arithmetic candidates |
|---|---:|---:|---:|
| `3b4c84865651c9d431c9ee873790add3` | 8 | 6 | 7 |
| `5be3f5d6495f68286523e230e2d0d9a4` | 68 | 53 | 68 |
| `6dbe9f75292d82b3cc6c57bd65089b20` | 12 | 10 | 12 |

The section-specific results reinforce why the single-bank arithmetic must not
be treated as a callsite identity: 87 pairs pass its even-row test, including
all 69 that also match the repeated-block constructor. The audit exposes these
as overlapping candidates instead of selecting one constructor from element
counts alone.

The shape candidate API deliberately preserves this distinction:
`repeatedBlockMatrixShapeOrNull()` represents the row/column/bank tuple
recovered from the `FUN_180052f90` callsite, while
`singleBankEntryShapeCandidateOrNull()` represents only the alternate
single-bank arithmetic. Neither field assigns a semantic role to KEY_1 or
KEY_2.

## Native matrix-bank input traversal for the repeated-block wrapper

The generic `FUN_180063ca0` loader supports two traversal modes, selected by
its `param_11` argument:

- `param_11 == 0`: rows are the outer loop and columns are the inner loop;
  source coordinates are consumed in row-major order.
- `param_11 != 0`: columns are the outer loop and rows are the inner loop;
  source coordinates are consumed in column-major order.

In both modes, values for the matrix bank are interleaved per coordinate, and
the loader visits the allocated matrix-object pointer array from its final
entry back to its first entry.

For the repeated-block constructor path, the raw call context in
`FUN_18005e9b0` at `0x18005eb04` writes zero to `[RSP + 0x50]` immediately
before calling `FUN_180063ca0`. Under the Win64 calling convention this is
the eleventh argument, `param_11`; therefore this wrapper selects the
row-major branch. The Android model-pair decoder now exposes
`decodeNativeMatrixBankPayloadOrNull()`, which fixes this call path to
row-major traversal while retaining the lower-level explicit-orientation
decoder for other loader call sites.

The native-order unit test checks all three aspects independently:
interleaved per-coordinate values, reverse matrix-object bank order, and
equivalence between the wrapper-specific decoder and the explicit row-major
decoder. This establishes tensor layout conversion only; it does not identify
the tensor's semantic role or run the model's forward pass.

A distinct entry-level call inside `FUN_1800537e0` at `0x180053e13`
passes `EAX`, `R10D`, and `1` as wrapper dimensions. The
`FUN_18005e9b0` stack argument forwarding maps these to
`FUN_180063ca0`'s `param_6` (columns), `param_7` (rows), and
`param_8` (matrix count), respectively. The caller computes:

```
columns = EAX = [RSI + 0x10]
rows = R10D = 2 * [RSI + 0x14] * [RSI + 0x08]
matrixCount = 1
KEY_1 elements = rows * columns
KEY_2 elements = rows
KEY_1 / KEY_2 = columns
```

The Android index exposes this as
`singleBankEntryShapeCandidateOrNull()`. The candidate is returned only for
a positive, even KEY_2 length and an integral positive KEY_1/KEY_2 ratio.
This is a shape-compatibility test, not a callsite association: the exact
descriptor object and callsite ancestry have not yet been linked to each
paired tensor object. Consequently it must not be used to override the
69 path-specific repeated-block classifications above.

The real payload audit found **87 of 88** pairs are arithmetically compatible
with this single-bank shape, because their KEY_2 element counts are even.
Of these, **69 also match** the repeated-block callsite formula, and **18**
are single-bank-only compatibility candidates under the currently audited
shape formula. One pair has KEY_2 length 1 and cannot satisfy the
`R10D = 2 * a * b` even-row condition. These counts do not prove that the
87 objects actually use the entry-level call; they quantify the ambiguity
that remains until descriptor paths are mapped.

## Actual AI Megpoid VTB2 pair audit

The updated payload-validation workflow inspected the real
`AI_Megpoid.vtb2` JSON object tree. The recursive inventory contains 100 KEY_1
occurrences and 88 KEY_2 occurrences. The same immediate parent object
contains both keys in **88 objects**. All 88 paired objects had exactly one
typed `*F32` leaf under each key; none were ambiguous or malformed. The
remaining 12 KEY_1 occurrences were not paired under an immediate common
parent and are excluded from this descriptor-pair check.

Most common observed element-count pairs (primary / secondary):

| Primary elements | Secondary elements | Count |
|---:|---:|---:|
| 92,160 | 192 | 16 |
| 9,216 | 96 | 12 |
| 18,432 | 192 | 12 |
| 24,576 | 128 | 9 |
| 12,288 | 64 | 6 |
| 2,304 | 256 | 6 |
| 32,768 | 256 | 6 |
| 98,304 | 256 | 6 |

These counts prove structural co-location and typed-payload sizes. The
descriptor relationship above adds a stronger invariant: each pair should
satisfy `primaryElements = secondaryElements * param_6 * param_8`. The
payload audit checks the equivalent condition that the primary element count
is a positive integer multiple of the secondary element count. **All 88 paired
objects pass**. The observed quotient frequencies are 96 (26), 480 (16),
192 (15), 128 (10), 9 (7), 384 (6), 16 (4), and 32, 35, 64, 418 (one each).
The quotient validates the combined `param_6 * param_8` factor for the
matrix loader. The repeated-block row/column/bank decomposition is supported
for 69 pairs as documented above; the remaining 19 are not shape-classified
by that call path. Neither payload is assigned a semantic role.

## Runtime forward dispatch and residual-block loop

The active native execution path is now traced one call layer above the residual operator.

- `FUN_180126c20` is the higher-level entry wrapper. It rejects a null first context pointer with `E1016`, rejects a null/empty second context pointer with `E1017`, and otherwise forwards to `FUN_1800aec40` at `0x180126cde`.
- `FUN_1800aec40` validates the network object pointer and raises `E100D` if it is absent. It obtains a companion object through `FUN_1800c4730`, requires the 32-bit field at byte offset `0x624` to be positive (`E1004` otherwise), copies that field into the network object at the same offset, and calls `FUN_1800c7ad0`.
- The wrapper forwards three additional caller-supplied values to `FUN_1800c7ad0`. The current Ghidra prototypes disagree on one forwarded argument's width, and do not establish whether these values are buffers, lengths, or flags; they remain opaque in this trace.

Inside `FUN_1800c7ad0`, the residual stage is an explicit nested loop:

- The outer loop runs while the current sequence index is below `param_1[5] - 1`.
- For each sequence position, the inner loop visits `param_1[6]` residual block entries.
- The block-data entry address is computed with a `0x100`-byte stride from the pointer stored at byte offset `0x2E0` of the dispatch object. In the decompiler's `int *param_1` notation this is `*(longlong *)(param_1 + 0xb8)`.
- The companion block-state entry uses a `0x88`-byte stride from the pointer stored at byte offset `0x2F8`. In the same `int *param_1` notation this is `*(longlong *)(param_1 + 0xbe)`.
- Each iteration calls `FUN_1800c5d50` with that block-data entry, the current input/output workspace descriptors, and its companion state entry. The two workspace descriptors are then swapped for the next block.
- The dispatcher also calls `FUN_1800c5260`, `FUN_1800c5440`, and either `FUN_1800c6390` or `FUN_1800c72d0` around the residual loop. These are separate preprocessing/transition stages and should not be folded into the residual block's tensor bundle without a field-level data-flow trace.

### Android residual-loop schedule representation

The Android port now represents the confirmed inner/outer loop and workspace
ping-pong as `S5MegpoidResidualBlockSchedule`:

- Sequence count is validated as at least one; the number of outer iterations
  is `sequenceCount - 1`, matching the recovered loop condition.
- Each outer iteration visits block records in their append order.
- The eight VTB2-backed records are ordered row-major from the nested loader
  loops: `(row 0,column 0)`, `(0,1)`, then `(1,0)`, through
  `(3,1)`.
- Each scheduled call reads one workspace slot and writes the other; the
  two slot identities are exchanged after each block.
- The plan can be obtained from
  `S5VoicebankModelPackage.buildResidualBlockSchedule(sequenceCount)`.

The schedule object is deliberately declarative. It does not execute the
neighboring `FUN_1800c5260`, `FUN_1800c5440`,
`FUN_1800c6390`, or `FUN_1800c72d0` stages because their buffer contracts
are not yet completely linked to note/phoneme features. It also does not claim
that the model input/output workspace memory has been reconstructed. Tests
verify sequence-major ordering, eight-block row-major order, and alternating
workspace ownership.

## Runtime record-array ownership and copy paths

A whole-image instruction scan for the non-stack offsets used by
`FUN_1800c7ad0` found two additional functions that operate on the same
record-vector fields.

- `FUN_1800cdbb0` copies the block-data vector from byte offset `0x2E0`.
  In its `undefined4 *` parameters, this appears as `param + 0xB8`.
  It obtains the begin pointer from `param_2 + 0xB8`, obtains the end pointer
  from `param_2 + 0xBA`, and passes `(end - begin) >> 8` as the element
  count to `FUN_1800afc30`. The right shift by eight is division by the
  `0x100)-byte block-record stride.
- `FUN_1800c47a0` copies the state-record vector at byte offset `0x2F8`.
  It obtains begin/end pointers from offsets `0x2F8` and `0x300` and
  passes `(end - begin) / 0x88` to `FUN_1800af720`.

These are separate object-copy paths, but they use the same two field offsets
and exactly the same element strides as the runtime dispatcher. This confirms
that the fields at `0x2E0` and `0x2F8` are vector-like record arrays, not
single tensor pointers. It also links the 0x100-byte and 0x88-byte record
layouts to the object-copy lifecycle.

The scan also found `FUN_180131fa0` reading/growing fields at the same
offsets through `FUN_1800b3bd0`, with a requested capacity of `0x801`.
That routine has not yet been proven to initialize the exact same object
instance passed to `FUN_1800c7ad0`; it remains a candidate initialization
path until the constructor/caller relation is established.

The subsequent constructor trace in
`docs/S5API_MEGPOID_RUNTIME_RECORD_POPULATION.md` identifies the eight
`FUN_1800ced10` property-key families, recovers their 16x16 static lookup
tables, and matches the used 4x2 cells against the supplied
`AI_Megpoid.vtb2`. The eight source-property families are now mapped to the
runtime-record builder inputs and their destination fields.

## Residual runtime record offsets

The decompilation of `FUN_1800c7ad0` and `FUN_1800c5d50` exposes a
field-level contract for the records passed to the residual operator.

`FUN_1800c7ad0` computes each block-data pointer as
`blockBase + blockIndex * 0x100` and each companion state pointer as
`stateBase + blockIndex * 0x88`. It passes them as the first and fourth
arguments to `FUN_1800c5d50`, respectively.

The first argument is an observed `0x100`-byte block-data record. Its
recovered fields are:

| Byte offset | Access in the runtime code | Established use |
|---:|---|---|
| `0x00` | `*param_1` | Channel count `C` |
| `0x04` | `param_1[1]` | Expansion multiplier `E`; expanded count is `C * E` |
| `0x10` | Passed as `param_1 + 4` to `FUN_1800c67e0` | Start of the depthwise/history descriptor |
| `0x20` | Coefficient pointer read by `FUN_1800c67e0` from the descriptor beginning at `0x10` | Depthwise filter coefficients; the optimized runtime path is selected when both filter dimensions are 7 |
| `0x90` | `param_1[0x24]` | Flag indicating whether an optional residual-bias pointer is present |
| `0x98` | Pointer consumed by the second `FUN_1800c3740` call | Projection matrix, mapping `C * E -> C` |
| `0xA8` | Optional pointer consumed by the residual affine merge | Residual bias |
| `0xB8` | Pointer consumed by the residual affine merge | Residual scale |
| `0xC8` | Pointer consumed by the first `FUN_1800c3740` call | Expansion matrix, mapping `C -> C * E` |
| `0xD8` | Expanded-feature array consumed by the normalization/GELU affine step | Expansion normalization scale (`gamma`); the block builder allocates `C * E` values |
| `0xE8` | Expanded-feature array consumed by the normalization/GELU affine step | Expansion normalization bias (`beta`); the block builder allocates `C * E` values |
| `0xF8` | `param_1[0x3e]` | Epsilon term |

The depthwise descriptor begins at byte offset `0x10`. `FUN_1800c67e0`
receives the block-data pointer advanced by four 32-bit integers, reads the
descriptor's three integer fields at record offsets `0x10`, `0x14`, and
`0x18`, and reads the coefficient pointer at record offset `0x20`.
The observed 7-by-7 path therefore consumes `C * 49` depthwise
coefficients, matching the size contract of the Android scalar residual
kernel.

The companion `0x88`-byte state record is a workspace/history structure,
not another matrix bank. `FUN_1800c5d50` stores the first scratch workspace
pointer at `+0x00`, its `C` and `N` dimensions at `+0x18` and
`+0x1c`, the expanded workspace pointer at `+0x20`, and its
`C * E` and `N` dimensions at `+0x38` and `+0x3c`. The substructure
starting at `+0x40` is passed into `FUN_1800c67e0` for history/ring-buffer
preparation.

The normalization pointers at record offsets `0xD8` and `0xE8`
are consumed together by the native affine expression
`gamma - inverse_std * mean * beta`: the `0xD8` array supplies gamma
and the `0xE8` array supplies beta. This matches the Android kernel's
formula, so its `expanded_bias_a` input maps to the `0xD8` gamma array
and `expanded_bias_b` maps to the `0xE8` beta array.

The offset roles align with the Android kernel's argument shapes:

- depthwise coefficients: `C * 49`;
- expansion matrix: `(C * E) * C`;
- projection matrix: `C * (C * E)`;
- normalization arrays: `C * E` each;
- residual scale and optional bias: `C) each;
- sequence workspaces: `C * N) and `C * E * N).

The follow-up constructor analysis links these destination fields to the
actual used factory keys and typed tensors in `AI_Megpoid.vtb2`. For each of
the eight residual blocks, the audit confirms the 7x7 filter, two 32-element
conditioning vectors, 32x64 expansion source matrix, 64-element expansion
bias, 64x32 projection matrix, 32-element projection bias, and 32-element
residual scale. The corresponding Android assembler reconstructs the
conditioned expansion matrix and its two 64-element affine arrays.

See `docs/S5API_MEGPOID_RUNTIME_RECORD_POPULATION.md` for the complete
key-to-field and tensor-layout evidence. The Android port now has a separate
history state per block and a declarative sequence-major scheduler. These
are bounded implementation milestones: the full feature-generation graph,
neighboring preprocessing/transition operations, and the PCM synthesis path
are still unfinished. No end-to-end vocal output is claimed.

## Named neural layer weights in VOCALOID7.vtmg

The named-layer path was traced separately from the fixed-key block path.

- `FUN_180068190` selects the named-layer configuration and calls
  `FUN_180064670` for the nine literal layer names:
  `pre1`, `pre2`, `conv1`, `cond1`, `conv2`, `cond2`,
  `conv3`, `cond3`, and `linear`.
- `FUN_180064670` appends `.weight` and `.bias` to the supplied
  layer name, hashes each complete property name, then resolves each
  property from the supplied model object.
- The corresponding tensor properties are present in the shared
  `VOCALOID7.vtmg` member. Both weight and bias are single typed `*F32`
  payloads for all nine layers in the supplied archive.
- The recovered matrix-bank counts are 1 for `pre1`, `pre2`,
  `cond1`, `cond2`, `cond3`, and `linear`; 3 for `conv1`,
  `conv2`, and `conv3`.

The payload-count audit gives the following values:

| Layer | Matrix bank count | Weight elements | Bias elements | Weight elements per matrix | Quotient vs. bias |
|---|---:|---:|---:|---:|---:|
| `pre1` | 1 | 2,624 | 32 | 2,624 | 82 |
| `pre2` | 1 | 1,024 | 32 | 1,024 | 32 |
| `conv1` | 3 | 6,144 | 64 | 2,048 | 32 |
| `cond1` | 1 | 1,664 | 64 | 1,664 | 26 |
| `conv2` | 3 | 6,144 | 64 | 2,048 | 32 |
| `cond2` | 1 | 1,664 | 64 | 1,664 | 26 |
| `conv3` | 3 | 6,144 | 64 | 2,048 | 32 |
| `cond3` | 1 | 1,664 | 64 | 1,664 | 26 |
| `linear` | 1 | 96 | 3 | 96 | 32 |

The full `FUN_180064670` body establishes the descriptor axes directly.
It writes `param_5` to descriptor `axis0`, `param_4` to `axis1`,
and `align_up(param_5, 8)` to `padded_axis0`; `param_6` is the
number of matrix objects in the bank. The separate bias descriptor is a
one-row vector whose length is `param_5`. Therefore the verified named-layer
descriptor values are:

| Layer | axis0 | axis1 | padded axis0 | Matrix count |
|---|---:|---:|---:|---:|
| `pre1` | 32 | 82 | 32 | 1 |
| `pre2` | 32 | 32 | 32 | 1 |
| `conv1` | 64 | 32 | 64 | 3 |
| `cond1` | 64 | 26 | 64 | 1 |
| `conv2` | 64 | 32 | 64 | 3 |
| `cond2` | 64 | 26 | 64 | 1 |
| `conv3` | 64 | 32 | 64 | 3 |
| `cond3` | 64 | 26 | 64 | 1 |
| `linear` | 3 | 32 | 8 | 1 |

The exported factory wrapper `Func_4cd84bb7` allocates a 0x200-byte
object and calls `FUN_180068190` with the model-object base advanced by
0x10 bytes. The nine `param_7` destinations passed to
`FUN_180064670` therefore occupy these 16-byte descriptor-pair slots
relative to the returned factory-object base:

| Layer | Factory-object byte offset | Slot contents |
|---|---:|---|
| `pre1` | `0x20` | weight-bank pointer, bias-vector pointer |
| `pre2` | `0x30` | weight-bank pointer, bias-vector pointer |
| `conv1` | `0x40` | weight-bank pointer, bias-vector pointer |
| `cond1` | `0x50` | weight-bank pointer, bias-vector pointer |
| `conv2` | `0x60` | weight-bank pointer, bias-vector pointer |
| `cond2` | `0x70` | weight-bank pointer, bias-vector pointer |
| `conv3` | `0x80` | weight-bank pointer, bias-vector pointer |
| `cond3` | `0x90` | weight-bank pointer, bias-vector pointer |
| `linear` | `0xA0` | weight-bank pointer, bias-vector pointer |

This comes from the direct call arguments: the wrapper passes
`puVar2 + 4` as `FUN_180068190`'s first argument, and the function
sets its per-layer output pointer to `param_1 + 4`, `+8`, `+0xC`,
... `+0x24` (32-bit units). Inside `FUN_180064670`, the first pointer
receives the banked weight descriptors and the second pointer receives the
one-row bias descriptor. The Android enum now records these returned-object
offsets as `descriptorPairOffsetBytes`; tests pin all nine offsets. This is
a memory-layout mapping only: the external factory object is not yet being
constructed or passed to a recovered inference method by the Android app.

The same function's fill loops establish source order as
`[axis0][axis1][matrix]`. The Android
`S5MegpoidNamedLayerTensorIndex` now resolves all nine named weight/bias
pairs, exposes these exact descriptor axes, and offers
`packWeightMatrixBank()` and `packBiasVector()`. The weight method decodes
the tensor and passes it to native `packMatrixBank()` with the recovered
axis counts and bank count. The bias method uses native `packRowMajor()`
with the bias length as axis0 and a single axis1 row. Both paths validate the
8-element axis0 padding. This prepares both descriptor value buffers from
the real VTMG tensors, but does not run the neural network.

### Parameter-key byte order

The dynamic S5 parameter hash is an MD5 digest of the UTF-8 parameter name
followed by the recovered 24-byte suffix. The model JSON stores the resulting
16 digest bytes in canonical lowercase hexadecimal byte order. The initial
native implementation formatted each 32-bit MD5 state word as an integer,
which reversed the byte order inside each word. The native formatter has been
corrected to emit each state word's four bytes in little-endian digest order.
Tests now include the real model keys for `pre1.weight` and `conv1.bias`,
in addition to the generic MD5 test vectors.

This makes named-property lookup compatible with the actual `VOCALOID7.vtmg`
JSON keys. It does not by itself identify how these named matrices feed the
runtime residual operator or establish an end-to-end inference schedule.

### Neural forward-dispatch entry chain

The current report traces the runtime entry point used from the higher-level
render routine:

`Func_92864e29` → `FUN_180126c20` → `FUN_1800aec40` →
`FUN_1800c7ad0`.

- `Func_92864e29` prepares per-frame feature and state buffers, invokes
  `FUN_180127330`, then calls `FUN_180126c20` with the selected model
  context, a non-null runtime-state pointer, and the per-frame state buffer.
- `FUN_180126c20` is a guard/wrapper. It rejects a null model context
  (`E1016`) and a null runtime-state pointer (`E1017`), then forwards
  the argument bundle to `FUN_1800aec40`.
- `FUN_1800aec40` resolves the runtime engine pointer from that bundle,
  obtains the required model-state count through `FUN_1800c4730`, checks
  that it is positive, copies it into the runtime state, and dispatches
  `FUN_1800c7ad0`.
- `FUN_1800c7ad0` is the central operator-graph execution routine. Its
  block loop calls `FUN_1800c5d50` at `0x1800cae94` with the current
  0x100-byte block-data record, state/history records, and per-block
  workspace. The caller then swaps the associated workspace/state records
  before continuing the loop.

The render routine subsequently calls `FUN_180126e30` and
`FUN_180126d40` with per-frame ring-buffer slices. Those two functions
have now been added to the Ghidra export targets so their buffer direction,
length, and relationship to the synthesis output can be confirmed.

This establishes a verified call chain into the neural operator graph. It
does not yet prove the exact semantics of the top-level frame buffer or show
a PCM/audio-sample output from this invocation.

### Optional GRU parameter-loading path

A separate generic recurrent loader, `FUN_18005ebe0`, is called with a
base name of `rnn`. Its recovered arguments define a GRU-style parameter
layout:

- `param_4`: input feature count for layer zero;
- `param_5`: hidden feature count;
- `param_6`: recurrent layer count;
- `param_7`: whether bias vectors are enabled;
- `param_8`: bidirectional flag; direction count is
  `1 + (param_8 != 0)`;
- gate feature count is `3 * hidden_size`, so this is a three-gate
  GRU-style layout, not an LSTM's four-gate layout.

For layer `l`, input feature count is `input_size` when `l == 0`,
otherwise `hidden_size * direction_count`. Each direction loads the
following hashed properties (with `_reverse` appended for reverse
direction):

| Property suffix | Element count |
|---|---:|
| `weight_ih_l{l}` | `3 * hidden_size * input_features` |
| `weight_hh_l{l}` | `3 * hidden_size * hidden_size` |
| `bias_ih_l{l}` | `3 * hidden_size` when bias enabled |
| `bias_hh_l{l}` | `3 * hidden_size` when bias enabled |

The existing Android `S5RnnDirectionLayout` / native
`vea_s5_rnn_direction_layout` helpers represent these element counts.
The loader also uses `FUN_18005df50` to transpose one of the matrix
buffers into an axis0-padded descriptor representation. This is a
separate parameter path from the nine named `pre/conv/cond/linear`
layers indexed above.

The expanded model-payload CI scan checked 128 possible GRU keys
(16 layer indices × 4 parameter members × 2 direction variants) in each of
the five supplied model members: `AI_Megpoid.vpit`,
`AI_Megpoid.vtb2`, `COMMON7.vpit`, `VOCALOID7.vhnv`, and
`VOCALOID7.vtmg`. The result was **0/128 matches in every member**.
Therefore the generic GRU loader is present in S5API, but its expected
parameter properties are not present in the supplied Megpoid model object
trees; this path is not currently a viable parameter source for this bank.
The Android GRU dimension utility remains generic infrastructure only and
is not connected to the Megpoid named-layer parameter set.

### Named-layer descriptor-pair storage

The decompiled factory `Func_4cd84bb7` allocates a 0x200-byte model object
and calls `FUN_180068190(puVar2 + 4, ...)`. Since the first argument is a
32-bit pointer, `FUN_180068190`'s `param_1` begins 0x10 bytes into that
allocation. Its nine calls to `FUN_180064670` pass destination addresses
`param_1 + 4`, `+8`, `+0xc`, `+0x10`, `+0x14`, `+0x18`,
`+0x1c`, `+0x20`, and `+0x24`. These are byte offsets
`0x20` through `0xa0` from the original allocation base, spaced by
`0x10` bytes.

Each destination is a pair of pointers: the weight matrix-descriptor bank
and the one-row bias descriptor. The mapping is:

| Layer | Object-base pair offset |
|---|---:|
| pre1 | 0x20 |
| pre2 | 0x30 |
| conv1 | 0x40 |
| cond1 | 0x50 |
| conv2 | 0x60 |
| cond2 | 0x70 |
| conv3 | 0x80 |
| cond3 | 0x90 |
| linear | 0xA0 |

The Android implementation now has a native owner for this exact descriptor
region. `vea_s5_named_layer_parameter_set_create()` allocates one native
arena, packs each weight source as a matrix bank, packs each bias as a
one-row descriptor, then writes the two descriptor pointers at the recovered
slot offsets in a 0xb0-byte table mirror. It validates all nine slot offsets,
source element counts, matrix-bank counts, and descriptor dimensions. JNI
returns an opaque handle, and `S5MegpoidNativeParameterSet.close()` releases
the arena. This mirrors the descriptor-pair region only; it is not a complete
copy of the vendor's 0x200-byte object or its virtual methods.

This is persistent native parameter preparation, not inference. The neural
forward schedule, temporal feature inputs, operator-specific connections,
and synthesis-to-PCM path still require separate tracing and implementation.

## What is still not connected

- The nine fixed lookup keys in `AI_Megpoid.vtb2` have been found and indexed.
- The model constructor's array and repeated-block hierarchy are now traced.
- The runtime block's channel/position/expansion relationships are established.
- The exact mapping from the nested `FUN_18005e9b0` inputs to depthwise,
  expansion, projection, bias, residual, and epsilon fields is not yet proven.
- Android now ports the per-block seven-slot history ring state and exposes
  sequential chunk execution at the isolated residual-block boundary.
- Exact equivalence of the native history helper, its workspace pointer
  lifecycle, outer per-position/block scheduling, and VSM state transitions
  remains unverified.

No Megpoid PCM output is produced by this trace. The next required check is the
shared typed-tensor loader and the source/destination field offsets it fills.
Only then can a real voicebank tensor be passed to the native residual block.
