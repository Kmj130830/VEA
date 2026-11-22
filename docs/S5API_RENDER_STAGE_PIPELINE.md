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
