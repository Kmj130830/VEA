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
```

S5API has three coordinate implementations:

- `FUN_1800c3930` dispatches to `FUN_1800c4080` when
  `FUN_1800dc2e0()` returns zero, and to `FUN_1800c39f0` otherwise.
  Both helpers materialize every part's locations/distances before the
  mask pass.
- The SSE-style `FUN_1800c4080` converts the cycle quotient to int32,
  truncating toward zero:
  `cycle = trunc((u - base(k)) / 4)`.
- The AVX-style `FUN_1800c39f0` uses `vroundps(..., 8)`; with the
  ordinary MXCSR nearest-even mode this is:
  `cycle = roundToNearestEven((u - base(k)) / 4)`.
- The inline scalar fallback uses `roundf((u - base(k)) / 4)`, with
  half values away from zero.

The Windows caller has an `MRW_MODROT_SIMD=0` override to force the inline
scalar path. Otherwise, the callsite first evaluates `FUN_1800dc2e0`:

- `FUN_1800dc2e0` checks CPUID leaf 1 for OSXSAVE, AVX and FMA, verifies
  that XCR0 has the XMM/YMM state enabled, then checks CPUID leaf 7 EBX
  bit 5 (AVX2). It returns nonzero only for the AVX2-capable route.
- When that check succeeds, `FUN_1800c3930` dispatches to the 8-lane
  AVX helper `FUN_1800c39f0`.
- When AVX2 is unavailable, the caller checks the processor feature level
  returned by `FUN_18003cbe0`. Feature level 3 selects the inline scalar
  fallback; other levels use `FUN_1800c3930`, which dispatches to the
  4-lane SSE helper `FUN_1800c4080`.

AI Megpoid has four parts, within both table helpers' supported ranges.
Android does not execute x86 SSE/AVX instructions: its default Kotlin path
reproduces the 4-lane SSE helper's truncation and polynomial semantics as
the portable baseline. Callers can select the AVX helper's nearest-even
rounding mode for comparison, or the inline scalar `roundf` mode.

For the SSE table path:

```text
location(k) = max(base(k), trunc((u - base(k)) / 4) * 4 + base(k))
distance(k) = abs(u - location(k)) * f0
```

For the AVX table path, replace `trunc` with MXCSR nearest-even rounding.
For the inline scalar path, replace it with `roundf`.
The four locations and distances are materialized for all parts before the
mask pass. The SSE and AVX table helpers reduce their phase in turns using
`theta_ctr * 0.15915494 * location(k)` and evaluate the following
quarter-wave polynomial. SSE uses truncation for quadrant reduction; AVX
uses MXCSR nearest-even rounding:

```text
P(z) = ((((0.00016044118*z - 0.004681754)*z + 0.079692625)
          *z - 0.6459641)*z + 1.5707964)

turn = theta_ctr * 0.15915494 * location(k)
D46  = signed(P(x_sin*x_sin) * x_sin)
D4C  = -signed(P(x_cos*x_cos) * x_cos)
```

Here `x_sin` and `x_cos` are produced by the native two-stage
fractional-turn/quadrant reduction; the sign toggles when the reduced
quadrant integer is odd. Android ports the same polynomial in both table
modes, using truncation in `SIMD_SSE_TABLE_TRUNCATION` and nearest-even
rounding in `SIMD_AVX_TABLE_NEAREST_EVEN`. The scalar fallback
`INLINE_SCALAR_ROUNDF` uses native `roundf` for locations and platform
`cos/sin`, matching the inline scalar branch.

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

Tests cover the four part locations/distances, all three native
coordinate rounding routes (SSE truncation, AVX nearest-even and
inline-scalar `roundf`), the native SIMD quarter-wave polynomial, the
nearest/threshold OR rule, strict nearest-distance tie behavior, inclusive
threshold equality, both threshold-selector modes, unvoiced identity
rotation, and the complex accumulation sign/order.

## Boundary: this is not the complete output head

This work prepares the per-part location, distance, gain and rotation
tables and ports the complex accumulator. It does not yet:

1. Split the eight post-residual feature channels into the four per-part
   x/y feature planes.
2. Integrate the modulation buffers into the VTMG four-part output-head
   operator callsites and their native interpolation/conditioning stages.
3. Build the carrier-conditioned feature input and run its full per-frame
   scheduling path.
4. Reduce/fuse the completed part streams into the final two terminal
   feature planes.

The VTMG-to-active-output-head mapping and carrier-conditioned input path
remain separate work. No end-to-end vocal render or PCM/WAV output is
claimed by this operator port.
