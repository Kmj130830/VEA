# S5API Alternate reverse-transition operator

Date: 2026-11-25

## Dispatcher and branch selector

The render loop in `FUN_1800c7ad0` executes one reverse-transition stage
between each residual row. It reads the byte at render-state root `+0x59`:

- zero calls standard Banked `FUN_1800c6390`, descriptor array `+0xA0`,
  workspace array `+0xA6`, descriptor stride `0x30`;
- nonzero calls Alternate `FUN_1800c72d0`, descriptor array `+0xAC`,
  workspace array `+0xB2`, descriptor stride `0x28`.

The initializer obtains this byte from the optional hashed property returned
by `FUN_1800e6de0`. The exact lookup key is
`119558af50f9c8919539abd8e064b287`. If the property is absent, the
initializer stores false; otherwise it requires a Boolean and copies that
value to root `+0x59`.

The offline model-resource inspection in
[workflow run 45590025813](https://github.com/Kmj130830/VEA/actions/runs/45590025813)
found the key exactly once in the AI Megpoid VTB2 options object:

`$.5be3f5d6495f68286523e230e2d0d9a4.154c806ba4d274fad9c7484acdc1779e.a4ba655a061e6151f80574e6088492a0.119558af50f9c8919539abd8e064b287`

Its value is Boolean `true`. The same options object also contains the
confirmed terminal-spectrum and carrier-fusion flags. Therefore AI Megpoid
V7 selects Alternate: the native initializer writes `+0x59 = 1`, and
`FUN_1800c7ad0` dispatches to `FUN_1800c72d0`.

## Operator contract: FUN_1800c72d0

The decompilation shows these descriptor fields:

| Descriptor field | Use |
|---|---|
| `param_1[0]` | Input feature count C |
| `param_1[1]` | Output feature count M |
| pointer pair at `param_1 + 0x08` | Weight vector |
| pointer pair at `param_1 + 0x18` | Output-feature bias vector |

The input descriptor supplies N positions at byte offset `+0x1C`.
The Alternate operator writes exactly `2 * N` output positions.

### Interpolation

For each input feature c and each original position j, it first creates
two samples. Endpoints are clamped to the first or last input sample:

```text
up[c, 2j]   = 0.75 * x[c,j] + 0.25 * x[c,max(j-1,0)]
up[c, 2j+1] = 0.75 * x[c,j] + 0.25 * x[c,min(j+1,N-1)]
```

The native scalar implementation is selected when `FUN_1800df720()`
returns zero. Other dispatch values call specialized helpers
`FUN_1800ce460` and `FUN_1800ce750`; those paths still require
ARM64 parity testing.

### Three-tap context window

For every upsampled position p, the operator builds three feature rows per
input channel. The missing outer neighbor is zero-padded:

```text
left[c,p]   = up[c,p-1] when p > 0, otherwise 0
centre[c,p] = up[c,p]
right[c,p]  = up[c,p+1] when p < 2N-1, otherwise 0
```

The matrix is flattened in channel-major tap order:

```text
windows[3*c + 0, p] = left[c,p]
windows[3*c + 1, p] = centre[c,p]
windows[3*c + 2, p] = right[c,p]
```

### Projection and bias

The final native call is `FUN_1800c3740` with:

- M = output feature count;
- N = 2 * input positions;
- K = 3 * input feature count;
- alpha = 1, beta = 0;
- weight pointer from the Alternate descriptor;
- window matrix laid out as `[3*C][2*N]`;
- output laid out as `[M][2*N]`.

The operator then unconditionally adds one bias value per output feature
across all `2*N` positions.

The Android reference operator is
`S5MegpoidAlternateTemporalProjection`. It ports the scalar interpolation,
edge extension, tap-window layout, row-major GEMM and bias broadcast. Tests
cover the 2x position expansion, left/centre/right windows, one-position
boundary behavior and multi-channel tap flattening.

## AI Megpoid model inventory

The selected AI Megpoid VTB2 subtree contains Alternate weight/bias pairs
at transition indices 0, 1 and 2:

| Index | Weight metadata | Bias metadata | Payload |
|---:|---|---|---|
| 0 | `[32,32,3,1]` | `[32]` | 3,072 + 32 F32 |
| 1 | `[32,32,3,1]` | `[32]` | 3,072 + 32 F32 |
| 2 | `[32,32,3,1]` | `[32]` | 3,072 + 32 F32 |

The Android model index validates metadata products against decoded tensor
payload counts and can compile an Alternate operator for a 32-channel
descriptor. The native operator consumes a contiguous M-by-(3*C) weight
matrix. The remaining tensor-loader parity check is to verify the raw
VTB2 tensor axis order against that consumer layout; the Android operator
does not silently transpose the raw payload.

## Sequence order and remaining integration

The native reverse pass runs after Temporal[0..2] and Residual row 3.
For each reverse transition i=0..2, it runs Alternate[i] and then Residual
row `2-i`. Unlike standard Banked projection, Alternate always doubles the
position count; its output is `[M][2*N]`, not `[M][N][bank]`.

Android now reads the optional `119558af...` Boolean through
`S5MegpoidRuntimeOptionsDescriptor.alternateTransitionEnabled` (missing key
defaults to false, matching the native initializer) and maps it to
`S5MegpoidReverseTransitionMode`. The actual AI Megpoid model reports true.

The model package now exposes
`createMegpoidV7AlternateSequenceTransitionPipeline()`. It validates the
four-by-two residual grid, derives the first Dense dimensions from the
selected tensors, prepares all three Temporal `[32,32,3,1]` stages and all
three Alternate `[32,32,3,1]` stages, then runs the native order:
Residual row 3, Alternate[0]/Residual row 2, Alternate[1]/Residual row 1,
Alternate[2]/Residual row 0. Each Alternate transition doubles the active
position count. The sequence-pipeline test checks the required 49-position
input boundary, residual-row call order, and final 56-position output.

The remaining operator-level verification is the exact raw VTB2 Alternate
weight-axis interpretation relative to the native `[M][3*C]` GEMM rows,
followed by comparison against a reference native render. Note-to-phone
timing and the active phone-feature descriptor are also still separate
upstream inputs. No end-to-end vocal render or PCM/WAV output is claimed.
