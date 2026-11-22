# S5API renderer stage and residual-block call sequence

Date: 2026-11-22

## Scope

This note records the call sequence and descriptor-array strides visible in
`FUN_1800c7ad0`. It describes the renderer's stage orchestration, not a
completed Megpoid graph mapping. In particular, the nine VTMG tensor pairs
`pre1/pre2`, `conv1/cond1`, `conv2/cond2`, `conv3/cond3`, and `linear` are not
yet assigned to these stage descriptors.

## Render entry gate

`FUN_1800aec40` is the direct caller of `FUN_1800c7ad0`. Before entering
the frame renderer it:

- resolves the internal object through `FUN_1800c4730`;
- rejects the call when the signed 32-bit value at internal-object offset
  `+0x624` is less than one;
- copies that value to offset `+0x624` in the renderer-side object;
- calls `FUN_1800c7ad0` with the render-state pointer and the three request
  arguments passed to the wrapper.

This is an entry precondition/state handoff. The `+0x624` value has not yet
been proven to be the renderer's `stageCount` (`param_1[5]`); these fields
must remain distinct until the state-factory path confirms the relationship.

## Confirmed control flow

- `param_1[5]` is the stage count. `param_1[6]` is the repeated residual
  depthwise/MLP block count used inside each reverse stage.
- A forward loop runs `FUN_1800c5440` for stage indices `0` through
  `stageCount - 2`, consuming one stage buffer and writing the next.
- The renderer then walks the stage cascade in reverse. At each step it
  dispatches either `FUN_1800c6390` or `FUN_1800c72d0`, according to the
  byte flag at `(uint8_t*)param_1 + 0x59`.
- The selected stage projection is followed by `param_1[6]` calls to
  `FUN_1800c5d50`, one per residual DW-MLP block. The block index is
  flattened with the reverse-stage index as
  `(reverseStageIndex * blockCount + blockIndex)`.
- The optional history/skip path copies stage activations into per-stage
  circular buffers and adds the retained activation back after the projection
  when the low byte of `param_1[0xd52]` is zero. Because the first argument
  is `int*`, this field is at byte offset `0x3548`, not byte offset
  `0xd52`. This is distinct from the raw byte flag at
  `(uint8_t*)param_1 + 0x59` used to choose the projection function.

## Confirmed Megpoid renderer counts and JSON key tables

The values read by `FUN_1800ced10` and copied into the renderer-state
configuration resolve the runtime dimensions as follows:

| Renderer config index | Value | Meaning |
|---:|---:|---|
| `param_1[3]` | 320 | frame hop / step |
| `param_1[4]` | 32000 | sample rate |
| `param_1[5]` | 4 | renderer stage count |
| `param_1[6]` | 2 | residual DW-MLP block count per stage |

The VTB2 loader confirms **4 stage indices × 2 block indices = 8
c5d50 parameter descriptors**. This is distinct from the separate nine-record
VTB2 array documented in `S5API_VTB2_REPEATED_BLOCK_TENSORS.md`.

The reverse loop uses `iVar29` from zero to
`stageCount - 1`. It enters the projection and c5d50 block section only when
`iVar29 > 0`. Inside that section:

- the selected projection descriptor index is `iVar29 - 1`;
- the c5d50 parameter stage index is `stageCount - iVar29 - 1`;
- each block's flattened c5d50 parameter index is
  `(stageCount - iVar29 - 1) * blockCount + blockIndex`.

Thus projection descriptors advance in forward order (0, 1, 2), while the
c5d50 parameter groups execute in reverse order (2, 1, 0). These are distinct
index spaces and must not be conflated.

For the decoded Megpoid values (4 stages, 2 blocks per stage), this gives the
confirmed c5d50 invocation sequence:

```text
(stage 2, block 0)
(stage 2, block 1)
(stage 1, block 0)
(stage 1, block 1)
(stage 0, block 0)
(stage 0, block 1)
```

The two descriptors for stage 3 are loaded and validated by the VTB2 loader,
but this `FUN_1800c7ad0` call path does not pass them to `FUN_1800c5d50`.
Their use in a separate render path or configuration remains unknown. The
runtime schedule must therefore distinguish loaded descriptors from the six
blocks invoked by this specific renderer loop.

`FUN_18010d550(stage, block)` selects a 32-character JSON property key from
the table at `0x1801cbc20`, with 0x20-byte string entries indexed by
`stage * 16 + block`. `FUN_1800ea000(stage, block)` selects another key
from the table at `0x1801c5bf0`, using the same index and entry stride.

| Stage | Block | `FUN_18010d550` key | `FUN_1800ea000` key |
|---:|---:|---|---|
| 0 | 0 | `2be3c647a25538e9536d6757202f82c4` | `8abf41425bad2554193a661080753070` |
| 0 | 1 | `de282d6c923b674399f1c1c72c628baf` | `fbaf01274f7d7328e85fe2606636e54a` |
| 1 | 0 | `b1465ea6660173b4a49fc88d6cebf333` | `78ab983f61c5c58ff84d45113026ad8a` |
| 1 | 1 | `a15cd1ac0be5abbba27a53fe03786820` | `0f551cb9b37ff307437be80b2f80961d` |
| 2 | 0 | `207f5586069747df3328b61d143fd74c` | `771075dad8a161731f1ec7849ffccccc` |
| 2 | 1 | `633af40060c0bd27f5637d7a495522a6` | `0beaee9f704f4ac85d8f87a10cfeadc1` |
| 3 | 0 | `763f264fbcf91eb0ee4f198e3c7668f4` | `b639196457944060e60e66b234e2a5e1` |
| 3 | 1 | `224d0c06603f0018052fa9e634fafde1` | `3294d2ee186d6fedf65089d86b78b302` |

For each key pair above, the VTB2 child object selected by the first key
contains a dimension vector `[64,32,1,1]` and one F32 tensor of 2,048
values (64 × 32). The child object selected by the second key contains a
dimension vector `[32,1,7,7]` and one F32 tensor of 1,568 values
(32 × 7 × 7). These are the confirmed per-stage/per-block tensor shapes for
those two fields. Other c5d50 fields (projection return matrix, biases,
affine vectors and state inputs) still require their own helper-key and
descriptor-field mapping; this section does not claim the complete block
is bound.

## Descriptor arrays observed at the call sites

The renderer's first argument is decompiled as `int*`. The `param_1 + N`
expressions below therefore address pointer fields at byte offset `4*N`,
whereas explicit casts such as `(longlong)param_1 + 0x59` use byte offsets.

| Operation | Descriptor base field | Per-stage stride | Bias base field | Bias stride |
|---|---:|---:|---:|---:|
| `FUN_1800c5440` | `param_1 + 0x94` (byte +0x250) | `0x30` | `param_1 + 0x9a` (byte +0x268) | `0x18` |
| `FUN_1800c6390` | `param_1 + 0xa0` (byte +0x280) | `0x30` | `param_1 + 0xa6` (byte +0x298) | `0x18` |
| `FUN_1800c72d0` | `param_1 + 0xac` (byte +0x2b0) | `0x28` | `param_1 + 0xb2` (byte +0x2c8) | `0x18` |
| `FUN_1800c5d50` | `param_1 + 0xb8` (byte +0x2e0) | `0x100` per flattened block | `param_1 + 0xbe` (byte +0x2f8) | `0x88` per block state |

The stage activation pointer arrays are addressed through `param_1 + 0xda`
and `param_1 + 0xe0`; the residual-block call uses the current activation
and an alternate activation array at `param_1 + 0xe6`. The renderer copies
or swaps these activation buffers between operator stages.

## Descriptor-copy boundary and source layout

`FUN_1800c4730` resolves the source config from a wrapper handle: it requires
the wrapper's `+0x38` object pointer and that object's `+0x10` inner-state
pointer to be non-null, then returns the inner-state pointer in `RAX` (the
decompiler's inferred return type is incomplete). `FUN_1800ccce0` passes that
inner-state pointer as the source argument to `FUN_1800cdbb0`.

`FUN_1800ccce0` allocates a fresh `0x37a0` execution state, runs the
`FUN_1800bca40` initializer, obtains the inner config pointer through
`FUN_1800c4730`, then passes that config to `FUN_1800cdbb0`.
`FUN_1800cdbb0` is a copy/translation step, not the stage-weight loader.
It copies the already-populated arrays from the source config into the
execution-state arrays consumed by `FUN_1800c7ad0`:

| Source field (byte offset) | Destination field (`int*` index) | Count expression | Entry stride |
|---:|---:|---|---:|
| `+0x250` | `+0x94` | `(end - begin) / 0x30` | `0x30` |
| `+0x280` | `+0xa0` | `(end - begin) / 0x30` | `0x30` |
| `+0x2b0` | `+0xac` | `(end - begin) / 0x28` | `0x28` |
| `+0x2e0` | `+0xb8` | `(end - begin) >> 8` | `0x100` |

The projection-selection bytes at `+0x59` and `+0x5a` are copied separately.
The history/skip flag at source index `0xd52` is also copied, retaining the
`int*`-relative indexing described earlier.

`FUN_1800c47a0` is a separate config-object copy routine. It clones vectors
at byte offsets `+0x268` (entry stride `0x18`), then either `+0x298` or
`+0x2c8` depending on the mode byte at `+0x59`, and a residual-state vector
at `+0x2f8` (entry stride `0x88`). Those are not the same arrays/strides as
the c7ad0 execution descriptors and must not be substituted for them.

`FUN_18005cba0`, called by the adjacent render preparation path, allocates
scratch/shape descriptors and zeroes their data buffers. Its recovered body
does not read or bind the VTMG weight values; therefore it is not evidence
for assigning VTMG layer names to c5440 or c6390 stages.

## What is still unresolved

The constructor that populates the descriptor arrays above has not yet been
fully traced. The current static evidence does not establish:

- which VTMG layer tensor is assigned to each `FUN_1800c5440` stage;
- whether `FUN_1800c6390` descriptors point at VTMG weights, VTB2-conditioned
  matrices, or a mixture, for a specific Megpoid configuration;
- how the `0x28` descriptors consumed by `FUN_1800c72d0` are populated;
- the conditioning tensors, parameter normalization values, and block
  instance counts for the installed Megpoid package.

These mappings must come from the render-state factory and its model-binding
callers. VEA must not bind the nine VTMG tensor pairs by layer name or matching
dimensions alone.

## VEA implementation boundary

VEA now has independent, tested ports of the stride-2 projection
`FUN_1800c5440`, the tensor A projection `FUN_1800c6390`, the 2x upsample
projection `FUN_1800c72d0`, and the residual DW-MLP block `FUN_1800c5d50`.
Their descriptor construction and model-tensor binding remain separate work.
The public engine status therefore stays unavailable and `render()` does not
claim success until a complete Megpoid render produces validated PCM/WAV.

## Render-state object lifecycle and c5d50 descriptor ownership

The latest Ghidra report exposes the public object wrappers around the core
frame routine:

- `FUN_180126940` allocates an 8-byte handle and a zeroed `0x37a0`-byte
  render-state object, then initializes it with `FUN_1800bca40`.
- `FUN_1801269f0(handle)` obtains the shared config through `FUN_1800c4730`,
  creates another `0x37a0`-byte state, initializes it, and copies the source
  configuration with `FUN_1800cdbb0`.
- `FUN_180126c20(...)` is the render entry wrapper. It resolves the context
  with `FUN_1800c4730`, checks the positive frame/block count at `+0x624`,
  copies that count to the render state, and calls `FUN_1800c7ad0`.
- `FUN_180126850(a, b)` validates two non-null handles and delegates to
  `FUN_1800aee20`, which reaches `FUN_1800c47a0`. The latter copies many
  arrays and scalar settings between the two large objects. The precise
  semantic names and source/destination roles of every copied field remain
  unresolved because the vendor ABI prototypes are incomplete.

`FUN_1800cdbb0` is especially relevant to the stage descriptors: it copies
the vector at int32 slot `+0xb8` (byte offset `0x2e0`) using an element
stride of `0x100` bytes. The renderer later indexes that same slot vector
with a `0x100`-byte stride for each c5d50 call. This ties the copied
render-state array to the per-block objects consumed by `FUN_1800c5d50`.

The VTB2 entry `FUN_180127220` calls `FUN_1800af170`, which prepares a
temporary loader object, calls `FUN_1800ced10`, and then passes the result
through `FUN_1800dc4b0`. The exact ownership/registration semantics of
`FUN_1800dc4b0` are still under analysis; do not yet equate the temporary
VTB2 loader object with the final `+0xb8` render-state vector.

## Confirmed operator order and descriptor-array layout in c7ad0

The decompiled `FUN_1800c7ad0` makes the stage control flow more precise
than the six-call c5d50 schedule alone:

The renderer entry is decompiled with `param_1: int*`. Therefore
`param_1 + N` means a 32-bit slot index (byte offset `4*N`), not byte offset
`N`. The byte flag at `(uint8_t*)param_1 + 0x59` is a separate raw-byte
address. The exact slot-to-byte mapping is listed in the descriptor table
above.

1. A forward loop runs `FUN_1800c5440` for each stage transition from
   `0` through `stageCount - 2`. It reads descriptors through slot
   `param_1 + 0x94` (byte offset `0x250`) with `0x30`-byte elements.
2. A reverse-stage loop then selects one projection operator based on the
   raw byte flag at byte offset `0x59`:
   - flag `0`: `FUN_1800c6390`, using slot `param_1 + 0xa0`
     (byte offset `0x280`), stride `0x30`;
   - flag nonzero: `FUN_1800c72d0`, using slot `param_1 + 0xac`
     (byte offset `0x2b0`), stride `0x28`.
3. Each reverse stage then executes `blockCount` c5d50 blocks. Their
   descriptors are read through slot `param_1 + 0xb8` (byte offset
   `0x2e0`) with `0x100`-byte elements.

`param_1[5]` is the stage count and `param_1[6]` is the block count per
stage in this function. The forward and reverse stage operators use the
per-stage activation buffers through slots `param_1 + 0xda` and
`param_1 + 0xe0` (byte offsets `0x368` and `0x380`). The alternate
activation array is at slot `param_1 + 0xe6` (byte offset `0x398`).
The exact semantics of their ring/window subfields still require the
corresponding descriptor constructors.

`FUN_1800cdbb0` copies all four descriptor vectors into a render-state clone:
destination slots `+0x94`, `+0xa0`, `+0xac`, and `+0xb8`, corresponding
to byte offsets `0x250`, `0x280`, `0x2b0`, and `0x2e0`. The element
strides are respectively `0x30`, `0x30`, `0x28`, and `0x100` bytes.
This is strong evidence that these arrays belong to the renderer
configuration object consumed by c7ad0.

The VTMG file contains nine named tensors, but there is still no verified
one-to-one mapping from those tensor names to the three operator descriptor
arrays at slots `+0x94`, `+0xa0`, and `+0xac`. VEA therefore exposes generic
stride-2, banked-linear, and upsample-2 operators without hard-coding a
VTMG layer-to-stage assignment. The raw byte at `0x59` must be read from the
actual model configuration, not assumed from the default initializer.

For a VTMG layer with three matrix banks, `toRendererProjectionPlan()` now
prepares both layouts from the same decoded tensor:
- bank-major `[bank][output][input]` weights for c6390;
- temporal `[output][input][tap]` weights for c72d0.

Its `project(rawModeFlag, ...)` selects c6390 when the unsigned mode byte is
zero, and c72d0 when it is nonzero. The plan also validates tensor dimensions
and output lengths. This closes the decoded-tensor-to-operator call boundary
for either projection mode, but it intentionally does not choose which of
`conv1`, `conv2`, or `conv3` belongs to a specific descriptor index.

## State-buffer sizing is separate from model-weight loading

`FUN_1800d5a00(int* state)` is called at the end of `FUN_1800cdbb0` after
the descriptor vectors and scalar settings have been copied. Its body
sizes/clears renderer-owned memory from the current dimensions; it does not
decode VTB2 tensors or assign VTMG tensor names to operator descriptors.

Recovered allocation requirements include:

- `stageCount - 1` entries for the forward `c5440` auxiliary vector at slot
  `+0x9a` (each entry `0x18` bytes);
- `stageCount - 1` entries for the active projection auxiliary vector at
  slot `+0xa6` when the mode flag is zero, or `+0xb2` when nonzero
  (each entry `0x18` bytes);
- `stageCount * blockCount` residual-block workspace entries at slot
  `+0xbe`, each `0x88` bytes, initialized from the local workspace template;
- `stageCount` activation descriptors for each of slots `+0xda`, `+0xe0`,
  and `+0xe6`, with sample-buffer lengths derived from the stage
  dimension arrays.

For the decoded Megpoid configuration (4 stages, 2 blocks), the state
therefore allocates 3 forward auxiliary entries, 3 projection auxiliary
entries for the selected mode, and 8 residual workspace entries. This
allocation count is separate from the six c5d50 calls made by the current
c7ad0 loop.

## Explicit VTMG temporal operator boundary

`S5VtmgPackedGraphLayerWeights.toTemporalProjectionPlan()` converts one
packed matrix-bank descriptor to row-major `[outputChannel][inputChannel][kernelTap]`
weights. The resulting plan exposes two explicit operations:

- `projectStride2()` invokes the `FUN_1800c5440` stride-2 Conv1D primitive.
- `projectUpsample2()` invokes the `FUN_1800c72d0` 2x interpolation plus 3-tap projection.

The upsample path requires three taps and validates its expected output frame
count. Both operations validate input, output, and finite values.
This is an operator API only: it does not assign `conv1`, `conv2`, or `conv3`
to a renderer stage or infer which operation the per-voice configuration selects.

