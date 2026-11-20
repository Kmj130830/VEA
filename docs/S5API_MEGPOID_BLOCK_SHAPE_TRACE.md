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

## Matrix-property lookup correction

The second Ghidra report includes the full body of `FUN_180052f90` and
`FUN_18005e9b0`. It clarifies an important distinction in the earlier flat
tensor index:

- The first two fixed hashes are generated as two property names inside the
  selected per-layer object.
- `FUN_180052f90` obtains that layer object and passes the two property names
  to `FUN_18005e9b0`.
- `FUN_18005e9b0` copies both names and passes the layer object plus those
  names to `FUN_180063ca0`.
- `FUN_180063ca0` resolves the two properties independently and creates
  matrix objects using the supplied row/column descriptor arguments. It
  supports multiple numeric storage forms, including float32 and float16
  paths, and writes matrix elements into the native matrix objects.

Therefore KEY_1 and KEY_2 are not two independent top-level model-block
sections. They are a pair of matrix-property lookups within a layer object.
The Android index now exposes `matrixPropertyPairs()`, which pairs typed
leaves only when exactly one KEY_1 and one KEY_2 occur under the same
immediate parent object. Ambiguous and incomplete groups are deliberately
excluded. The pair names remain neutral; the available evidence does not yet
prove which one is a kernel, bias, projection, or another matrix role.

The shape arguments passed to `FUN_180063ca0` are also not sufficient on
their own to assign a semantic role to the pair. The next step is to connect
the descriptor fields at each call site to the runtime operator dimensions
and then validate the resulting element counts against the actual model data.

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
