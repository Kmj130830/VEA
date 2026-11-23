# S5API SPP spectral-processing path

Date: 2026-11-22

## Scope

This note records the optional SPP path reached from the frame-processing
routine `Func_92864e29` at `0x1800925f0`. It is a spectral/time-domain
processing stage. It is not, by itself, proof of the complete Megpoid
neural renderer or final WAV encoder.

## Entry and bypass

`Func_92864e29` checks the process environment variable
`MRW_DISABLE_SPP`. Unless it is set to the character `1`, the frame routine
calls `FUN_1801361f0` with its persistent SPP state and two local transfer
buffers. If disabled, the caller copies the existing 80-float transfer
regions through the same local buffers and clears the secondary buffer.
After either path, the caller copies the transfer buffers back into its
persistent state areas and continues processing.

This is direct evidence that SPP is an optional stage in the enclosing
frame-processing path, not an isolated sample utility.

## Confirmed spectral operations in `FUN_1801361f0`

- The path maintains several ring-buffered state streams, including pitch,
  phase/phase accumulation, and voiced/unvoiced control data.
- Pitch in cents is converted to a frequency ratio using `exp2(cents / 1200)`.
- `MRW_SPP_VUV_XFADE_MS` controls the voiced/unvoiced crossfade duration.
- The analysis frame length is selected from the pitch/range state and
  bounded by the recovered maximum of 4096 samples.
- A windowed time-domain frame is transformed by `FUN_180040630` with its
  direction flag set to `0` (analysis/forward call site).
- The complex spectrum is converted to magnitude/phase data. The normal
  path calls `FUN_1801343c0` for local spectral-peak selection; the
  `MRW_SPP_LAZYPHASE` branch uses `FUN_180133ba0` instead.
- Frequency/peak locations are adjusted using pitch and phase state.
  The path also includes a deterministic-state PRNG helper at
  `FUN_180135490` for spectral phase/noise handling.
- The synthesis spectrum is passed to `FUN_180040630` with direction flag
  `1` (inverse call site), normalized, circularly shifted by
  `FUN_180133fd0`, and copied into an overlap/history buffer.
- The frame routine then invokes `FUN_1801334b0` on one or more spectral
  transfer buffers to apply additional frame-domain processing.

The above operation order is based on decompiled call sites and data flow.
VEA now has a portable real-FFT boundary, native window-table lookup, peak
selection/interpolation, phase-noise generation, frame rotation and scalar
normalization. The exact Yamaha FFT butterfly schedule and the state/descriptor
mapping for the complete SPP orchestrator remain separate integration work.

## FFT size-plan index and branch dispatch

`FUN_18007a000(uint32 n)` is now ported as
`NativeS5TensorBridge.sppFftLog2PlanIndex(n)`. It returns `-1` for `n=0`
and `floor(log2(n))` for positive 32-bit lengths. `FUN_180040630` uses this
index to address a 0x18-byte plan record in the FFT context.

`FUN_180040630` passes a signed direction to `FUN_180082a30`:

| Wrapper direction flag | Signed direction | Core branch | Final helper |
|---:|---:|---|---|
| `0` | `+1` | `FUN_18007fca0` | `FUN_18007b930` |
| non-zero | `-1` | `FUN_18007d700` | `FUN_18007c1c0` |

This table records the actual call branches only. The semantic naming of
positive/negative exponent as a user-facing forward/inverse convention is
not inferred here.

The two core branches have now been recovered as functions. Both contain
dedicated kernels for lengths 4 and 8. Length 16 uses
`FUN_18007db00`; length 32 uses `FUN_18007e140`. VEA now ports all four
leaf sizes through `vea_s5_spp_fft_leaf_f32`: the 16/32 paths consume the
generated plan coefficients and apply the distinct direction-specific output
permutations recovered from `FUN_18007d700` and `FUN_18007fca0`. Native
tests compare both signs against an independent direct complex-DFT reference.

For lengths above 32:

- the negative-direction path prepares data through `FUN_18007cb90`;
- the positive-direction path prepares data through `FUN_18007f170`;
- both dispatch to `FUN_18007fff0` below 128, `FUN_1800800c0` below 512,
  and `FUN_180081080` from 512 upward;
- the two directions then use their different final helper, `FUN_18007c1c0`
  or `FUN_18007b930`.

`FUN_180082a30` also performs real/complex edge-bin packing around those
complex FFT kernels. The 4/8/16/32 vendor leaf butterflies, data preparation
and direction-specific output stages remain part of the bit-exact porting
contract. Separately, VEA now supplies a portable mathematically equivalent
real-FFT boundary for forward/inverse SPP frame processing. That portable
wrapper is DFT/round-trip tested but is not bit-exact with the vendor schedule.

## Ported 64/128-lane mixed radix stages

`FUN_18007fff0` is now represented by
`vea_s5_spp_fft_radix_stage_f32()`. Its `fft_size` argument counts float
lanes in the interleaved complex buffer, not complex samples:

| Float lanes | Chunk layout | Per-chunk leaf calls |
|---:|---|---|
| 64 | 4 × 16 floats | `db00`, `ddc0`, `db00`, `db00` |
| 128 | 4 × 32 floats | `e140`, `e820`, `e140`, `e140` |

The `ddc0` and `e820` alternate sub-block butterflies are ported as
private native kernels. The stage receives `param_3 = split_table[0] = N/4` from the FFT
plan record. Its exact pointer offsets are therefore `N/4 - 8` for the
`db00/e140` chunks and, for the 128-lane alternate `e820` chunk,
`N/4 - 32`. The caller's data is copied to bounded scratch storage before
the sub-blocks are executed, so malformed parameters leave the original
buffer unchanged.

`NativeS5TensorBridge.applySppFftRadixStage()` and
`S5SppFftPlan.transformRadixStage()` expose the operation. Native tests
cover 64- and 128-lane plans, repeatability, finite outputs and short-plan
rejection. This stage is only the inner `FUN_18007fff0` subroutine: the
root's direction-specific input preparation (`cb90/f170`), recursive
larger-stage dispatch (`0800c0/081080`), final reorder (`c1c0/b930`),
and real-spectrum packing in `FUN_180082a30` are not yet joined.

## Portable real-FFT wrapper

`vea_s5_spp_real_fft_f32()` provides the full SPP real-spectrum boundary
for power-of-two frame sizes from 4 through 65536. It packs adjacent real
samples into a half-size complex vector, runs an iterative radix-2 complex
FFT, then emits the packed real spectrum:

- forward (`direction < 0`): `[DC, Nyquist, Re(X1), Im(X1), ...]`;
- inverse (`direction >= 0`): reconstructs real samples from that packed
  spectrum and retains the source S5 unnormalized inverse scale of `N/2`.

The portable wrapper is mathematically equivalent at the real-spectrum
boundary and is validated against an independent direct real DFT plus an
inverse round-trip. It does not claim bit-for-bit identity with Yamaha's
specialized `FUN_180082a30` / `FUN_18007d700` / `FUN_18007fca0` butterfly
schedule. The lower-level vendor-layout kernels through 512 lanes remain
available as separate primitives for comparison and future replacement.

`NativeS5TensorBridge.applySppRealFft()` and
`S5SppFftPlan.transformReal()` expose this transform. The larger voicebank
render path still needs spectral envelope/noise processing, phase-vocoder
state integration, and waveform reconstruction before it can produce a
complete singing WAV.

## Ported 256/512-lane large radix stage

`FUN_1800800c0` is now ported as
`vea_s5_spp_fft_large_radix_stage_f32(fft_size, mode, ...)`. The `fft_size`
argument counts interleaved float lanes; the two supported sizes are 256
and 512. The stage divides the input into four contiguous groups and calls
the two recovered outer kernels `FUN_1800803f0` and `FUN_180080940`, then
the previously ported `db00/ddc0/e140/e820` sub-block butterflies.

| Float lanes | Outer group sequence | Local leaf chunks |
|---:|---|---|
| 256 | `803f0`, `0940`, `803f0`, mode-selected `0940/803f0` | 4 × 16 floats per group, mixing `db00` and `ddc0` |
| 512 | `803f0`, `0940`, `803f0`, mode-selected `0940/803f0` | 4 × 32 floats per group, mixing `e140` and `e820` |

The plan pointer passed to the decompiled routine is `N/4`. Therefore
the 256-lane branch uses coefficient bases `N/4-32` (`803f0`),
`N/4-8` (`db00/ddc0`) and `N/4-64` (`0940`). The 512-lane branch uses
`N/4-64` (`803f0`), `N/4-8` (`e140/e820`), `N/4-32` (`e820`) and
`N/4-128` (`0940`). The raw mode byte selects which outer kernel and
terminal leaf handle the last group; the other three groups are unchanged.

`NativeS5TensorBridge.applySppFftLargeRadixStage()` and
`S5SppFftPlan.transformLargeRadixStage()` expose the stage. Native tests
build the 256/512 plan arrays, execute both modes, check repeatability and
finite output, and reject short coefficient tables and unsupported sizes.
This remains an inner FFT stage: the root direction-specific pre-permutation,
the final inverse/forward reorder, and the real-spectrum boundary packing
have not yet been composed into `FUN_180040630`.

## Generated FFT plan-record arrays

`FUN_180010110` selects inclusive plan indices
`floor(log2(minSize)) .. floor(log2(maxSize))` and stores one 0x18-byte
record for each size `1 << index`. Each record contains:

- the FFT size at byte offset `+0x00`;
- a pointer at `+0x08` to a compact uint32 stage/decomposition schedule;
- a pointer at `+0x10` to `N/2` float coefficient values.

`FUN_18004ec80` allocates both backing buffers at 32-byte alignment. The
first buffer contains the recursively generated split schedule from
`FUN_180082220`; its required count is `int(sqrt(N/2) + 2)`. The second
buffer combines the staged cosine/sine recurrence with the terminal
quarter-wave coefficient table. `S5SppFftPlanBuilder` now ports those
two array builders to the native S5 library and returns a validated
logical Kotlin `S5SppFftPlan` copy.

Native tests check sizes 4, 8, 16, 32 and 256, including the generated split
schedule for 256 and representative float coefficients. The 4/8/16/32 leaf
operator consumes the generated twiddle coefficients; the 16/32 branches are
also checked against a direct DFT reference for both transform directions.
The full real-FFT wrapper, stage kernels above 32, and reference-audio
validation remain outstanding.

## Implemented SPP buffer primitives

Two buffer-level operations in the recovered SPP path are now ported to the
portable S5 tensor library:

- `FUN_180133fd0`: circularly rotates a real-valued frame buffer. Positive
  shifts rotate left; negative shifts rotate right. The VEA adapter
  `vea_s5_spp_rotate_circular_f32` normalizes any signed shift modulo the
  buffer length and performs the operation in-place after validating the
  input. Kotlin exposes `NativeS5TensorBridge.rotateSppCircularBuffer()`.
- `FUN_1801397d0`: multiplies every element in a frame buffer by one scalar.
  The VEA adapter `vea_s5_spp_scale_buffer_f32` validates the entire
  multiplication before mutating the buffer. Kotlin exposes
  `NativeS5TensorBridge.scaleSppBuffer()`.

Native tests cover both rotation directions, wraparound shifts, zero-length
buffers, invalid pointers, scale factors, NaN input and failure atomicity.
These are isolated DSP primitives, not the complete SPP pipeline.

## Frequency-weighted peak candidate selection

`FUN_180136150` computes the periodic frequency penalty consumed by the two
SPP peak candidate scanners. Its five-float configuration is:

| Index | Meaning |
|---:|---|
| `0` | coordinate period |
| `1` | low-edge threshold and first ramp denominator |
| `2` | center of the penalty region |
| `3` | high-edge cutoff |
| `4` | penalty amplitude |

The coordinate is wrapped into the period interval. Outside
`[lowEdge, highEdge)` the penalty is zero. From lowEdge to center the
function ramps down linearly; from center to highEdge it follows the
second affine segment recovered in the original function. The peak scanners
add this penalty to the candidate magnitude before comparing candidates.

`FUN_180135b90` scans a contiguous magnitude array (`valueStride=1`).
`FUN_180135670` scans interleaved `[magnitude, phase]` records and compares
only each record's first lane (`valueStride=2`). Both initialize at the
start index, advance forward, and replace the current winner only on a
strictly greater score, so the first index wins ties.

VEA implements `vea_s5_spp_peak_weight_f32` and the shared
`vea_s5_spp_find_weighted_peak_f32` native primitive, exposed through
`NativeS5TensorBridge.sppPeakWeight()` and
`NativeS5TensorBridge.findSppWeightedPeak()`. The
`S5SppPeakSearchPlan` stores the five-float weighting config plus bin
spacing/normalization and offers separate stride-1 magnitude and stride-2
magnitude/phase entry points. Tests cover periodic wrap, both sides of the
weighting region, both scan layouts, changed peak ranking, first-wins tie
behavior, and invalid input preservation.

Candidate-index selection is followed by a separate local-maximum
refinement. VEA now also ports the core interpolation in `FUN_1801343c0`:
it fits a three-bin parabola to refine magnitude/sub-bin offset and unwraps
the neighboring phase delta into the shortest `[-pi, pi]` interval before
interpolating phase. `NativeS5TensorBridge.interpolateSppPeak()` exposes
that operator and tests cover both offset directions, phase wrap, edge bins,
non-local maxima and invalid samples.

The surrounding peak-list loops in `FUN_1801343c0` and the separate
`FUN_180133ba0` lazy-phase branch still require a full state/descriptor
binding and render-side integration.

## Deterministic spectral phase-noise generation

`FUN_180135490` advances a four-word xorshift state and converts magnitude
bins to interleaved real/imaginary values. The update sequence is:

```text
t = state[0] ^ (state[0] << 11)
next = (((state[3] >> 11) ^ t) >> 8) ^ state[3] ^ t
state = [state[1], state[2], state[3], next]
```

The sign of the generated 32-bit word determines the real-only DC sign or
the real-only terminal-bin sign. Interior bins use
`angle = float(word >> 8) * 3.7450704e-7`, then
`real = magnitude * cos(angle)` and `imag = magnitude * sin(angle)`. The
magnitude is therefore preserved while the bin receives a deterministic
phase.

`edgeMode & 1` selects the terminal-bin treatment. With bit 0 clear, the
last bin is emitted as real-only; with bit 0 set, it is processed as a
phase-rotated complex bin. The first bin is always real-only.

VEA now implements `vea_s5_spp_generate_phase_noise_f32` in native C++,
exposes it through `nativeGenerateSppPhaseNoise`, and provides
`NativeS5TensorBridge.generateSppPhaseNoise(magnitudes, state, edgeMode)`.
The mutable four-word `IntArray` carries the updated PRNG state between
frames. `S5SppPhaseNoiseState` wraps that state as a synchronized frame
stream and exposes snapshot/restore operations; the seed is supplied by the
renderer/model state rather than hard-coded. Native tests cover deterministic
output/state advancement, exact xorshift state progression, magnitude
preservation, both terminal-bin modes, the one-bin boundary, and failure
atomicity for invalid magnitude inputs. Kotlin tests cover persistent state,
checkpoint restore and input validation.

## Pitch/phase cubic curve interpolation

`FUN_1801360c0(position, controls)` is the four-point cubic segment helper
used by `FUN_1801314f0` when the cached curve segment matches the requested
interval. The control vector is `[p0,p1,p2,p3]` and the recovered
polynomial is kept in its original arithmetic order:

`u = position * position`

`b0 = ((2 * position) - 3) * u + 1`

`b1 = ((position - 2) * u) + position`

`result = b1*p1 + b0*p0 + (1-b0)*p2 + (position-1)*u*p3`

VEA exposes this as `vea_s5_spp_cubic_segment_f32()` in the native tensor
library and `NativeS5TensorBridge.interpolateSppCubicSegment()` in Kotlin.
`S5SppCubicSegmentPlan` validates and owns the four control values before
sampling. `S5SppCurveSegmentCache` implements the recovered fast-path guard:
the cached segment index must match and local position must lie in
`[0,1)`; otherwise it returns a cache miss so the caller can use the normal
curve-table lookup path. Native and JVM tests cover endpoints, midpoint,
cache hit/miss, invalid positions and non-finite/short control vectors. The
full `FUN_1801314f0` table search and edge-extension logic remain separate.

## Inverse-frame post-processing plan

`FUN_1801334b0` has the following confirmed operation sequence:

1. Clear its frame auxiliary region and ask `FUN_1801347a0` to prepare the
   partial/window contribution buffer.
2. Run `FUN_180040630` with direction `1` to produce a real time-domain
   frame in the internal work buffer.
3. Run `FUN_1801397d0` over exactly `frameSize` floats with scale
   `1.0 / frameSize`.
4. Run `FUN_180133fd0` over the same frame buffer with shift `-offset`.
5. Accumulate output samples from the shifted frame, the prepared partial/
   window contribution, and a descriptor-provided amplitude stream.

`S5SppInverseFramePlan` implements steps 2–4 in that order, and
`S5SppFftPlan.postProcessInverseFrame()` exposes the same sequence directly
from a size-indexed FFT plan. The operation validates the FFT size and offset,
copies the packed spectrum before invoking the inverse transform, and checks
the length/finite-value contract at every boundary. JVM tests verify the
exact call order and parameters: `direction=1`, `scale=1/frameSize`,
`shift=-offset`.

The later sample accumulation (step 5) is deliberately outside the plan.
The decompilation reads a separate partial/window buffer, a double-table
window coefficient configuration, and a per-sample amplitude stream; these
must be bound from the model/render state before the complete function is
considered ported.

## Window-table coefficient lookup

`FUN_180139870(double* config, double x)` is the SPP window-coefficient
lookup used while assembling the FFT analysis frame. The config contains:

- `config[0]`: non-negative coordinate scale;
- `config[2]`: fallback coefficient;
- the 32-bit segment count at byte offset `0x18`;
- `config[4]`: pointer to `segmentCount + 1` double table values.

The lookup computes `scaled = abs(x) * scale`, selects
`index = floor(scaled)`, and linearly interpolates between
`table[index]` and `table[index + 1]`. When `scaled >= segmentCount`, it
returns the fallback coefficient. The implementation uses double precision
to match the original helper's double table and interpolation arithmetic.

VEA now exposes `vea_s5_spp_lookup_table_f64` in C++,
`nativeLookupSppWindowTable` through JNI, and
`NativeS5TensorBridge.lookupSppWindowTable()` in Kotlin.
`S5SppWindowLookupPlan` packages the double table, segment count, scale and
fallback value in the recovered config-field shape, and provides a validated
per-coordinate lookup entry point. Native and JVM tests cover interpolation,
negative coordinate symmetry, exact upper boundary, fallback, empty table,
short table, non-finite values and invalid scale/pointer inputs.

## Real FFT wrapper and core

`FUN_180040630` is a real-FFT wrapper, not the butterfly implementation.
It selects a size-indexed plan using `FUN_18007a000`, packs/unpacks the real
Nyquist/DC layout, applies the forward/inverse sign and amplitude handling,
and calls `FUN_180082a30` with the plan's twiddle/scratch pointers. The
latter dispatches to `FUN_18007d700` for the negative-direction branch and
`FUN_18007fca0` for the positive-direction branch.

Those two internal kernels have now been added as explicit Ghidra targets.
Do not substitute an arbitrary FFT implementation until their array layouts,
twiddle table construction, scaling and ordering have been recovered and
tested against the wrapper's observable output.

## Remaining exact-port and SPP integration work

| Function | Ported boundary | Still required |
|---|---|---|
| `FUN_180040630` / `FUN_180082a30` | Portable real forward/inverse transform, packed DC/Nyquist bins and unnormalized inverse scale | Bit-exact vendor butterfly schedule, root permutations and stage-descriptor composition |
| `FUN_180139870` | Double-precision table lookup and interpolation | Bind the actual window-table config to the renderer state |
| `FUN_1801397d0` | Frame-wide scalar multiplication | Bind exact renderer buffer ownership |
| `FUN_1801343c0` | Three-bin peak refinement and phase unwrap | Full peak-list loop and descriptor mapping |
| `FUN_180133ba0` | Analysis target only | Lazy-phase output contract and implementation |
| `FUN_180135490` | Four-word xorshift phase/noise generation | Seed/state source in the renderer object |
| `FUN_180133fd0` | Circular buffer rotation | Bind source/destination buffers in the SPP state |
| `FUN_1801334b0` | Typed post-inverse frame step: inverse FFT, `1/N` scale, `-offset` rotation | Exact windowed buffer/partial-amplitude inputs and final output accumulation |
| `FUN_1801347a0` | Decompilation recovered | Bind/execute its partial-bin window synthesis descriptors |
| `FUN_1801361f0` | Top-level SPP call sequence identified | Persistent ring-buffer state, VUV/pitch phase and lazy-phase orchestration |

`S5SppInverseFramePlan` reproduces only the confirmed post-transform
subsequence of `FUN_1801334b0`. The function's following sample loop combines
that shifted frame with a separate partial/window buffer and amplitude
stream; those descriptors are not fabricated by the typed plan.

## VEA boundary

VEA now has a portable real-FFT boundary and several individually tested SPP
operators, plus a typed inverse-frame post-processing step matching the
confirmed `FUN_1801334b0` operation order. It does not yet execute the complete
`FUN_1801361f0` SPP state machine or connect its persistent pitch/phase/VUV
buffers to the renderer.

The complete VTMG stage binding, VTB2 conditioning/activation graph, remaining
spectral synthesis operators, root-level vendor FFT permutations, waveform
reconstruction and PCM/WAV output are still incomplete. Yamaha G2P remains
deferred until the renderer is ready to use real rendering requests. No
generated vocal audio is claimed.

