# S5API banked linear and GEMM memory-layout findings

Date: 2026-11-22

## Scope

This note separates three observed contracts that must not be conflated:
the VTMG file-to-descriptor packing layout, the banked linear render call,
and the generic GEMM kernel's input addressing. It records only the confirmed
contracts and the unresolved pointer bridge between them.

## Banked linear render call: FUN_1800c6390

The layer descriptor passed to `FUN_1800c6390` supplies:

- `param_1[0]`: input feature count;
- `param_1[1]`: output feature count and bias count;
- `param_1[2]`: bank count.

For the pre1 projection, the recovered call uses input width 82, output width
32, and one bank. With `positions` taken from the render-state object, it
calls the GEMM dispatcher with:

```text
M = outputFeatures
N = positions
K = inputFeatures
lda = inputFeatures
ldb = positions
beta = 0
ldc = positions
```

The temporary GEMM result is `[outputFeature][position]`. The caller then
scatters it to `[outputFeature][position][bank]` and adds one bias value per
output feature across all positions and banks.

## Generic GEMM baseline: FUN_180143460 / FUN_180142b80

The kernel selected by the `v0_naive` baseline dispatches through
`FUN_180139b10` into `FUN_180143460`. The latter is a tiled implementation;
the inner tile routine is `FUN_180142b80`.

The scalar edge/fallback path in `FUN_180143460` explicitly computes:

```text
C[m * ldc + n] += alpha
    * A[m * lda + k]
    * B[k * ldb + n]
```

The inner tile routine receives an A pointer offset by the K-block start,
uses `lda` to step between output-feature rows, and reads B across the
position tile. This independently agrees with the scalar formula above.
For pre1, the supplied `lda` is 82.

## VTMG descriptor packing

The VTMG loader `FUN_180064670` constructs one 24-byte descriptor per bank.
The descriptor records logical dimensions and points to a 32-byte-aligned
buffer. The packed values are traversed as:

```text
source[(axis0 * axis1Count + axis1) * bankCount + bank]
destination[(bank * axis1Count + axis1) * paddedAxis0 + axis0]
paddedAxis0 = align_up(axis0, 8)
```

For pre1, the recovered loader dimensions are axis0=32, axis1=82 and
bankCount=1. The resulting packed storage is therefore
`[bank][82][32]`, with 32 output lanes contiguous for each input index.

## Observed bank-entry indirection in the render layer object

Within `FUN_1800c6390`, the weight pointer is not read as a direct field
from a 24-byte VTMG matrix descriptor. The decompiled access sequence is:

```text
bankArrayBase = **(int64_t**)(layer + 0x10)
bankWeightPtr = *(int64_t*)(bankArrayBase + bankIndex * 0x18)
```

The bank loop advances the entry address by `0x18` bytes and passes the
first qword of each entry as GEMM's A pointer. A separate field at
`layer + 0x20` supplies the bias-vector data. This establishes an
intermediate per-bank entry indirection in the render-layer object. The
24-byte stride is consistent with a three-qword C++ container/view object,
but the exact source-level type is not asserted from this access alone.

```text
VTMG descriptor array (24-byte descriptors)
    -> [intermediate construction/ownership step not yet recovered]
    -> render layer object (+0x10 bank-entry array)
    -> first qword per 0x18-byte entry
    -> GEMM A pointer
```

## Unresolved runtime pointer bridge

The contracts above do not describe the same flat A-buffer layout:

- the generic GEMM baseline reads `A[outputFeature][inputFeature]` with
  `lda=82`;
- the VTMG packer stores `[inputFeature][paddedOutputFeature]`, i.e.
  `[82][32]` for pre1.

The renderer's `FUN_1800c6390` passes a per-bank weight pointer to the GEMM
dispatcher, but the current static trace has not yet established whether this
pointer references a separately transformed row-major copy, another
runtime-owned matrix view, or a different selected-kernel contract in all
configurations. The model-loader output field and the banked-layer runtime
object must be traced through their construction/ownership boundary before
choosing a conversion.

**Do not pass the VTMG packed pointer directly to the current row-major
`vea_s5_gemm_row_major_f32` implementation, and do not add an unverified
transpose.** The next port step is to trace the pointer stored in the banked
runtime object from `FUN_180068190` through layer construction to
`FUN_1800c6390`, then verify the resolved address and stride contract with
a synthetic tensor whose values encode both indices.

## Implementation status

VEA already has independent scalar GEMM and banked-linear primitives, plus
the VTMG layer packer and its dimension/padding tests. Those primitives are
not yet connected to this recovered banked render call. The real Megpoid
renderer remains unavailable until the complete forward path, G2P, stateful
spectral processing and waveform output are implemented and validated.
