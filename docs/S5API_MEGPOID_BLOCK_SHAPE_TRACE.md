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
The quotient validates the combined `param_6 * param_8` factor, but it does
not recover the two dimensions separately or assign a semantic role to either
payload.

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
