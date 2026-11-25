# S5API four-part output modulation

Date: 2026-11-25

## Native option fields

The AI Megpoid V7 post-residual multi-part output path uses two independent
optional booleans. Both are read from the selected
`5be3f5d6495f68286523e230e2d0d9a4 / 154c806ba4d274fad9c7484acdc1779e /`
runtime-options object by `FUN_1800ced10`.

| Render-root field | Getter | Lookup key | AI Megpoid V7 value |
|---|---|---|---|
| `+0x3405` | `FUN_1800e3030` | `7a5a0ba0ba2f00ab8a0a0d538add0e6d` | Boolean `true` |
| `+0x3411` | `FUN_1800e2770` | `ef12c1f702abd89b2e1f07a126cc5411` | Boolean `true` |

Both default to false when absent. The first flag gates the nearest-part
mask; the second selects the threshold rule when D04 is true.

When `+0x3405` is true, native also reads the string property returned by
`FUN_1800e2e70` (key
`184ef4ded83d0b35d809a7f1b09b1066`). It must equal the compiled
identifier `117fd5ea7e76f7852c197a284c4dd3bc`; otherwise the native
initializer raises E101B. The installed AI Megpoid VTB2 contains that exact
string. The Android options loader enforces the same guard and fails closed
when the string is absent or different.

The relevant scalar parameters are loaded into integer-backed root words:
D02 uses native truncation from 133.34 to 133, and D03 is 25. D04 is true.
For the supplied AI Megpoid settings, the active threshold is therefore
`133 + 25 = 158`.

## Per-position and per-part coordinates

For each output position p, let:

```text
step = param[4] / param[1] = 32000 / 1024 = 31.25
f0   = max(F0, 1e-5)
u    = p * step / f0
base(k) = k + 1                    where k = 0..3
cycle(k) = roundf((u - base(k)) / 4)
location(k) = max(base(k), cycle(k) * 4 + base(k))
distance(k) = abs(u - location(k)) * f0
```

The native `roundf` behavior rounds half values away from zero.
The four locations and distances are materialized for all parts before the
mask pass. For a voiced frame, the rotation coefficients are:

```text
phase(k) = fmodf(theta_ctr * location(k), 2*pi)
D46(k) = cosf(phase(k) - pi/2)
D4C(k) = sinf(phase(k) - pi/2)
```

For unvoiced frames, rotation is identity: D46=1 and D4C=0.

## Native gain/mask rule

When `+0x3405` is false or the frame is unvoiced, every part's gain is 1.
Otherwise let `nearestOther(k)` be the minimum distance among all other
parts. The scalar branch has this exact two-condition behavior:

```text
isNearest(k) = distance(k) <= nearestOther(k)   // ties are retained
isWithin(k)  = distance(k) <= threshold
gain(k)      = isNearest(k) OR isWithin(k) ? 1 : 0
```

This is **not** an AND gate. Distances inside the threshold keep every
corresponding part active; outside the threshold, only a nearest part stays
active. The threshold comparison is inclusive. The implementation was
corrected against the native scalar branch and has regression coverage for
the exact threshold boundary.

Threshold selection:

- D04 false:
  `threshold = min(D02, max(f0 - 2*step, step))`
- D04 true and `+0x3411` true:
  `threshold = D02 + D03`
- D04 true and `+0x3411` false:
  `threshold = max(f0 - 2*step, D02 + D03)`

## Complex accumulation

For one pair of post-residual feature planes x and y, the scalar native
accumulator uses:

```text
out.real += (x * D46 + y * D4C) * gain
out.imag += (y * D46 - x * D4C) * gain
```

This is implemented by
`S5MegpoidMultipartOutputModulation.accumulatePartInto()`. The table
builder is `S5MegpoidMultipartOutputModulationBuilder`; its input geometry
and D02/D03/D04 and both mask flags are taken from
`S5MegpoidRuntimeOptionsDescriptor`. The native part-modulation identifier
is validated by the same descriptor before a table is created.
`PreparedMegpoidRuntime.prepareMultipartOutputModulation()` accepts only
the frame-specific position count, F0 and `theta_ctr`.

Tests cover all four part locations/distances, the nearest/threshold OR
rule, strict nearest-distance tie behavior, inclusive threshold equality,
both threshold-selector modes, unvoiced identity rotation, and the
complex accumulation sign/order.

## Boundary: this is not the complete output head

This work prepares the per-part rotation and gain tables and ports their
scalar complex accumulator. It does not yet:

1. Split the eight post-residual feature channels into the four per-part
   x/y feature planes.
2. Port the independent input interpolation in `FUN_1800c4080` and
   `FUN_1800c39f0` or the SIMD dispatch in `FUN_1800c3930`.
3. Build the carrier-conditioned feature input and run its full per-frame
   scheduling path.
4. Reduce/fuse the completed part streams into the final two terminal
   feature planes.

The VTMG-to-active-output-head mapping and carrier-conditioned input path
remain separate work. No end-to-end vocal render or PCM/WAV output is
claimed by this operator port.
