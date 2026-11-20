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
- The block-data entry address is computed with a `0x100`-byte stride from the pointer stored at `param_1 + 0xb8`.
- The companion block-state entry uses a `0x88`-byte stride from the pointer stored at `param_1 + 0xbe`.
- Each iteration calls `FUN_1800c5d50` with that block-data entry, the current input/output workspace descriptors, and its companion state entry. The two workspace descriptors are then swapped for the next block.
- The dispatcher also calls `FUN_1800c5260`, `FUN_1800c5440`, and either `FUN_1800c6390` or `FUN_1800c72d0` around the residual loop. These are separate preprocessing/transition stages and should not be folded into the residual block's tensor bundle without a field-level data-flow trace.

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
| `0x20` | Pointer read by `FUN_1800c67e0` from descriptor offset `+0x10) | Depthwise filter coefficients; the runtime uses a 7-by-7 path when both descriptor dimensions are 7 |
| `0x90` | `param_1[0x24]` | Flag indicating whether an optional residual-bias pointer is present |
| `0x98` | Pointer consumed by the second `FUN_1800c3740` call | Projection matrix, mapping `C * E -> C` |
| `0xA8` | Optional pointer consumed by the residual affine merge | Residual bias |
| `0xB8` | Pointer consumed by the residual affine merge | Residual scale |
| `0xC8` | Pointer consumed by the first `FUN_1800c3740` call | Expansion matrix, mapping `C -> C * E` |
| `0xD8` | Per-channel array consumed by the normalization/GELU affine step | Expansion normalization scale (`gamma`) |
| `0xE8` | Per-channel array consumed by the normalization/GELU affine step | Expansion normalization bias (`beta`) |
| `0xF8` | `param_1[0x3e]` | Epsilon term |

The depthwise descriptor begins at `0x10`: `FUN_1800c67e0` reads its
first three integer fields at `0x10`, `0x14`, and `0x18), and reads
the coefficient pointer at `0x20`. The observed 7-by-7 path therefore
consumes `C * 49` depthwise coefficients, matching the size contract of
the Android scalar residual kernel.

The companion `0x88`-byte state record is a workspace/history structure,
not another matrix bank. `FUN_1800c5d50` stores the first scratch workspace
pointer at `+0x00`, its `C` and `N` dimensions at `+0x18` and
`+0x1c`, the expanded workspace pointer at `+0x20), and its
`C * E` and `N` dimensions at `+0x38` and `+0x3c`. The substructure
starting at `+0x40` is passed into `FUN_1800c67e0` for history/ring-buffer
preparation.

The offset roles align with the Android kernel's argument shapes:

- depthwise coefficients: `C * 49`;
- expansion matrix: `(C * E) * C`;
- projection matrix: `C * (C * E)`;
- normalization arrays: `C) each;
- residual scale and optional bias: `C) each;
- sequence workspaces: `C * N) and `C * E * N).

This is the first confirmed connection between native runtime record fields
and the existing Android kernel's input contract. It still does not identify
which hashed `AI_Megpoid.vtb2` tensor path is stored at each pointer offset.
That requires tracing the constructor writes into these record offsets.

This establishes where the residual operator is scheduled and how its per-position/per-block records are addressed. It does **not** yet show which `AI_Megpoid.vtb2` tensor paths populate the `0x100`-byte block-data records, the `0x88`-byte state records, or the workspace descriptors. Therefore the current Android matrix decoder remains an isolated layout conversion layer; it is not yet wired to `vea_s5_run_residual_dw_mlp_block_f32`, and no PCM synthesis is claimed.

## What is still not connected

- The nine fixed lookup keys in `AI_Megpoid.vtb2` have been found and indexed.
- The model constructor's array and repeated-block hierarchy are now traced.
- The runtime block's channel/position/expansion relationships are established.
- The exact mapping from the nested `FUN_18005e9b0` inputs to depthwise,
  expansion, projection, bias, residual, and epsilon fields is not yet proven.
- The ring-buffer/history state and VSM scheduling still need a separate
  runtime-compatibility boundary.

No Megpoid PCM output is produced by this trace. The next required check is the
shared typed-tensor loader and the source/destination field offsets it fills.
Only then can a real voicebank tensor be passed to the native residual block.
