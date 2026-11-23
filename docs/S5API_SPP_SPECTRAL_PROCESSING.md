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

The above operation order is based on decompiled call sites and data flow;
exact FFT scaling, window coefficients, peak interpolation, and all state
field meanings still require dedicated helper decompilation and tests.

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
`FUN_18007db00`; length 32 uses `FUN_18007e140`. For lengths above 32:

- the negative-direction path prepares data through `FUN_18007cb90`;
- the positive-direction path prepares data through `FUN_18007f170`;
- both dispatch to `FUN_18007fff0` below 128, `FUN_1800800c0` below 512,
  and `FUN_180081080` from 512 upward;
- the two directions then use their different final helper, `FUN_18007c1c0`
  or `FUN_18007b930`.

`FUN_180082a30` also performs real/complex edge-bin packing around those
complex FFT kernels. The small-size butterflies, data preparation and
direction-specific output stages are therefore part of the porting contract;
calling a generic complex FFT and only copying the result is not equivalent.

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

## Helper functions requiring exact ports

| Function | Current evidence | Still required |
|---|---|---|
| `FUN_180040630` | Called with direction 0 and 1 for forward/inverse transforms | FFT layout, scaling, normalization, supported sizes |
| `FUN_180139870` | Supplies the analysis window coefficient | Window family, parameter mapping, edge behavior |
| `FUN_1801397d0` | Applies scalar normalization to a frame buffer | Full vector/count semantics and exact scaling |
| `FUN_1801343c0` | Selects/interpolates peaks from a magnitude/phase spectrum | Peak threshold, neighborhood and interpolation details |
| `FUN_180133ba0` | Alternative peak selection used by lazy-phase mode | Exact branch equivalence and output contract |
| `FUN_180135490` | Advances a four-word PRNG state and generates signed/complex spectral values | Seed initialization and complete output interpretation |
| `FUN_180133fd0` | Circularly shifts a float buffer | Exact buffer ownership and aliasing contract |
| `FUN_1801334b0` | Runs an inverse transform and writes frame-domain results | Input spectrum contract, windowing and overlap semantics |

`FUN_180040630`, `FUN_1801347a0`, `FUN_180135270`, `FUN_1801360c0`,
`FUN_180135670`, and `FUN_180135b90` have been added as explicit Ghidra
analysis targets so their prototypes and implementations can be recovered
rather than guessed from call sites.

## VEA boundary

VEA does not yet execute this SPP path. The current implementation has
validated c5d50 residual blocks and their per-block temporal state, but the
complete VTMG stage binding, VTB2 conditioning graph, SPP operator chain,
Yamaha G2P, and final PCM/WAV path are still incomplete. No generated vocal
audio is claimed.

