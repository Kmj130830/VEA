# AI Megpoid V7 runtime block-record population trace

Date: 2026-11-21

## Scope and evidence

This note records a static Ghidra trace of the supplied S5API.dll in the
VEA GitHub Actions workflow. The DLL was not executed. The first successful
export is workflow run
[44773123856](https://github.com/Kmj130830/VEA/actions/runs/44773123856),
artifact `14648034005`.

## Confirmed parent construction path

### FUN_1800ced10

The decompilation at `0x1800ced10` shows:

1. The routine computes a required count for the block-data vector at
   `+0x2E0`. Its begin/end difference is divided by `0x100`; insufficient
   capacity is passed to `FUN_1800b3130(param_1 + 0xB8, &local_2f0)`.
2. It manages the companion vector at `+0x2F8`. The element count is
   `param_1[5] * param_1[6]`; its begin/end difference is divided by
   `0x88`. Depending on capacity, the routine trims, initializes, or grows
   that vector.
3. For each nested model entry, it reads multiple properties from the parsed
   model object and prepares temporary descriptors.
4. It initializes the temporary block descriptor with
   `FUN_1800bc5e0`, then calls
   `FUN_1800d4bf0` to assemble its vectors and nested records.
5. The resulting object is appended to the `+0x2E0` vector. If there is no
   spare slot, `FUN_1800b28a0` constructs/appends the record; otherwise
   `FUN_1800bc0c0` copies it into the current end slot and the end pointer
   advances by exactly `0x100` bytes.
6. After the model-block loop, `FUN_1800d5a00(param_1)` prepares the
   associated frame/workspace vectors.

This is a direct constructor-to-vector connection: `FUN_1800ced10` writes
the block records using the same field and stride later consumed by
`FUN_1800c7ad0`.

| Model context field | Element stride | Runtime use |
|---|---:|---|
| `+0x2E0` | `0x100` bytes | block-data records consumed by `FUN_1800c7ad0` |
| `+0x2F8` | `0x88` bytes | companion history/workspace records consumed by `FUN_1800c7ad0` |

## Partial field-construction trace: FUN_1800d4bf0

The function at `0x1800d4bf0` receives a destination block record followed
by eight model-property values. The Ghidra call-site instructions recover all
eight Win64 argument positions, including stack-passed arguments omitted from
the decompiler's high-level call expression. The associated property-key
factory functions are mapped below; the source roles are described by the
fields that consume them, rather than by guessed vendor parameter names.

The following operations are direct observations from the decompilation:

- The first input vector is copied into an owned float buffer stored through
  the record's `+0x20` shared-pointer field. The buffer has
  `param_1[4] * param_1[5] * param_1[6]` elements. Its traversal changes
  source order from `[C][H][W]`, index
  `((c * H + y) * W + x)`, to `[C][W][H]`, index
  `((c * W + x) * H + y)`. The channel axis remains outermost; the two
  spatial axes are exchanged.
- `FUN_1800d4a90` populates a nested descriptor beginning at destination
  offset `+0x30` from the first descriptor pair.
- `FUN_1800d4610` populates nested descriptors beginning at `+0x58` and
  `+0x88` from the next two descriptor pairs.
- `FUN_1800c0320` attaches the final shared float-vector input at
  destination offset `+0xB8`.
- The destination field at `+0xC8` is allocated as a float vector with
  `C * E * C` elements, where `C = *param_1` and
  `E = param_1[1]).
- The destination fields at `+0xD8` and `+0xE8` are each allocated as
  float vectors with `C * E` elements.
- The body sets the scalar at `+0xF8` to bit pattern `0x358637bd`,
  which is IEEE-754 float32 `1.0e-6`.

The computed vector lengths are important for the Android residual depthwise
MLP kernel: the expansion matrix storage has the expected
`(C * E) * C` size, and its two expanded-feature arrays have length `C * E`.
This also corrects the earlier rough note that described the normalization
arrays as length `C`; the builder's actual allocation loop uses `C * E`.

The `+0x20` source vector's spatial-axis permutation is explicit. Its actual
runtime dimensions are supplied by the per-block descriptor initialized by
`FUN_1800bc5e0`; the static VTB2 metadata array is recorded separately and
must not be confused with the destination pointer's axis order.

## Existing runtime field consumers

The residual runtime operator `FUN_1800c5d50` consumes the established
block-record fields as follows:

| Record offset | Observed consumer | Current interpretation |
|---:|---|---|
| `+0x00` | residual operator | channel count `C` |
| `+0x04` | residual operator | expansion multiplier `E` |
| `+0x10` to `+0x20` | history/depthwise helper | depthwise descriptor and coefficient pointer |
| `+0x90` | residual operator | optional residual-bias presence flag |
| `+0x98` | second linear projection | projection matrix pointer |
| `+0xA8` | residual merge | optional residual-bias pointer |
| `+0xB8` | residual merge | residual-scale pointer |
| `+0xC8` | first linear projection | expansion matrix pointer |
| `+0xD8` | normalization/GELU affine step | expanded-feature gamma array |
| `+0xE8` | normalization/GELU affine step | expanded-feature beta array |
| `+0xF8` | normalization | epsilon, now confirmed as `1.0e-6` by the builder |

The `+0x88` companion record is a separate workspace/history structure.
Its internal pointers and history substructure are not part of the model
matrix bank.

## Recovered source-property routing

The follow-up Ghidra export established the eight input routes used by
`FUN_1800ced10` when it calls the per-block builder. Each factory resolves
one entry from a 16×16 table using the two loop indices `iVar14` and
`iVar15`. The factory's table slot is
`row * 16 + column`, with a `0x20`-byte entry stride.

| Factory | Builder input | Destination in the 0x100-byte block | Current source role |
|---|---:|---:|---|
| `FUN_1800ea000` | param2 | `+0x20` | depthwise tensor; builder permutes `[C,H,W]` to `[C,W,H]` |
| `FUN_1800ff330` | param3 | `+0x38` | first shared descriptor vector |
| `FUN_1800f8220` | param4 | `+0x48` | second shared descriptor vector |
| `FUN_18010d550` | param5 | `+0x68` via descriptor at `+0x58` | nested matrix weights |
| `FUN_180106440` | param6 | `+0x78` via descriptor at `+0x58` | optional nested bias/vector |
| `FUN_18011b770` | param7 | `+0x98` via descriptor at `+0x88` | nested matrix weights |
| `FUN_180114660` | param8 | `+0xA8` via descriptor at `+0x88` | optional nested bias/vector |
| `FUN_1800f1110` | param9 | `+0xB8` | residual-scale shared vector |

Each factory contains 256 static initialization entries. Each entry's
32-character key is formed by formatting four embedded 32-bit constants as
8-digit hexadecimal words through `FUN_18001acc0`, then copying the
resulting string into the table through `FUN_1800e63b0`. A raw memory dump of
the table in the unexecuted DLL is all zero because these static tables are
populated by runtime initialization; the key strings are instead recoverable
from the initialization code itself.

The verifier extracts all 2,048 candidate keys (eight 16x16 tables) and
matches them against the actual `AI_Megpoid.vtb2` tree. The supplied bank
contains eight entries per factory, at rows 0..3 and columns 0..1, for 64
matched properties total. The report records only the key, factory/grid
position, parent path, scalar type, payload element count and small integer
metadata; it does not print tensor contents.

### Observed VTB2 layouts

All eight factory properties occur in the same per-block object. The
following metadata and payload lengths were observed for each of the eight
blocks in the supplied bank:

| Factory | Metadata | Payload elements | Builder/runtime role |
|---|---:|---:|---|
| `FUN_1800ea000` | `[7,7,1,32]` | 1,568 F32 | depthwise filter input |
| `FUN_1800ff330` | `[32]` | 32 F32 | descriptor vector A |
| `FUN_1800f8220` | `[32]` | 32 F32 | descriptor vector B |
| `FUN_18010d550` | `[1,1,32,64]` | 2,048 F32 | expansion-layer matrix source |
| `FUN_180106440` | `[64]` | 64 F32 | expansion-layer source bias |
| `FUN_18011b770` | `[1,1,64,32]` | 2,048 F32 | projection matrix source |
| `FUN_180114660` | `[32]` | 32 F32 | optional projection bias |
| `FUN_1800f1110` | `[1,1,32,1]` | 32 F32 | residual-scale vector |

The channel and expanded-feature sizes are therefore `C=32` and
`C*E=64` for these blocks (`E=2`). The generated expansion matrix has
`C*E*C=2,048` values; both generated affine arrays have 64 values. The
epsilon stored at `+0xF8` is `1.0e-6`.

### Generated expansion parameters

The scalar loop in `FUN_1800d4bf0` consumes the source expansion matrix in
feature-major groups of `C` values and forms the runtime arrays as follows:

```text
W'[f,c] = W[f,c] * A[c]
gamma[f] = sourceBias[f] + sum_c(W[f,c] * B[c])
beta[f]  = sum_c(W[f,c] * A[c])
```

Here `A` and `B` are the two 32-element descriptor vectors. The runtime
builder writes `W'` to `+0xC8`, `gamma` to `+0xD8`, and `beta` to
`+0xE8`. The 64x32 projection source is copied into the nested descriptor
without an additional matrix transpose in `FUN_1800d4610`.

### History/ring-buffer boundary

`FUN_1800c5d50` calls `FUN_1800c67e0` before the statistics and MLP steps.
The history helper is stateful: it sizes a workspace from the channel count,
chunk position count and the descriptor's history depth; copies each incoming
chunk into a rotating history slot; then builds pointers to the retained slots
before applying the depthwise filter. Consequently, the stateless Android
`vea_s5_run_residual_dw_mlp_block_f32` path, which accepts already materialized
`[channel][7][position]` windows and applies a direct reflect-padded 7x7
convolution, is currently a reference kernel, not a proven bit-exact port of
`FUN_1800c67e0`.

The residual affine order was checked against the original operator and the
C++ helper matches it: `(projected + optionalBias) * residualScale + residual`.
The expansion, normalization/GELU, projection and residual stages are thus
connected as an isolated block reference, but the streaming history scheduler
and complete singing forward pass remain unfinished.

### Android implementation status

- `S5MegpoidRuntimeBlockTensorIndex` indexes exact keys and retains the
  per-property shape metadata.
- `S5MegpoidRuntimeBlockAssembler` decodes all eight tensor roles, checks
  metadata and element counts, forms the generated expansion matrix and
  affine arrays, and keeps both the source channel-major filter and the
  `[W][C][H]` native-record permutation.
- `S5VoicebankModelPackage.prepareRuntimeBlocks(..., positions)` prepares
  the eight blocks from the loaded voicebank package. It requires the
  caller's actual chunk length.
- `runReferenceWindow` is explicitly limited to kernel-level experiments;
  it does not claim to reproduce the original stateful history path.
- `SynthesisEngine.render` remains unimplemented. No end-to-end vocal
  synthesis or WAV/PCM output is claimed.
