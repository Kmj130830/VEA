# S5API Megpoid spectral output and overlap-add boundary

Date: 2026-11-21

## Evidence scope

This is a static Ghidra dataflow trace of the supplied S5API.dll. It does not execute the vendor renderer and does not claim a verified WAV export.

## Corrected public render argument role

The public wrapper path is:

```text
FUN_180126c20(context, outputFloatBuffer, controlValue, modeFlag)
  -> FUN_1800aec40
  -> FUN_1800c7ad0(renderState, outputFloatBuffer, controlValue, modeFlag)
```

In `FUN_1800c7ad0`, the second argument is saved as `local_188` and passed to the output conversion routine near the end of the function. It is therefore an output destination, not the note/phoneme feature tensor.

The neural render state and its descriptor/workspace arrays are owned by the first argument. The forward network consumes those root-owned tensors and writes a complex-valued spectral frame to the root-owned output-spectrum buffer.

## Input-side periodic/noise excitation generator

The trace of `FUN_18012ad70` shows that the frame passed into the forward transform is generated from pitch and two interpolated gains; it is not simply copied from an external audio buffer.

The excitation-state layout observed from native loads/stores is:

| Byte offset | Type/use |
|---:|---|
| +0x00 | frame sample count N |
| +0x04 | phase increment denominator/scale (integer; physical unit unconfirmed) |
| +0x08 | period scale (float; physical unit unconfirmed) |
| +0x0C | harmonic denominator threshold (float) |
| +0x10 | phase-wrap flag |
| +0x14 | previous F0 |
| +0x18 | previous noise gain |
| +0x1C | previous harmonic gain |
| +0x20 | phase accumulator |
| +0x24 | cached Gaussian-sample state |
| +0x13AC | random-engine state |

The per-frame call inputs are:

- `param_3`: target F0/frequency control (unit and normalization unconfirmed)
- `param_4`: target noise/aperiodic gain
- `param_5`: target periodic/harmonic gain

For each sample, the native routine linearly interpolates F0 and both gains from their previous-frame values to the current targets. It accumulates phase using `2π * F0 / phaseScale`, optionally wraps phase, and computes a periodic pulse term:

```text
period = max(1, floor(periodScale / max(F0, 1e-5)))
shape = (cos(phase/2) - cos((period + 0.5) * phase)) / (2 * sin(phase/2))
periodic = sqrt(2 / period) * harmonicGain * shape * (F0 > 0)
noise = gaussianSample * noiseGain
excitation = periodic + noise
```

If the absolute denominator is at or below the state threshold, the periodic shape is set to zero. The previous F0/gains and final phase are retained for the next chunk.

`FUN_180127720` supplies a standard-normal sample using a cached-pair polar (Marsaglia) sampler. The state is initialized by `FUN_180127a40`, called from the transform-state constructor `FUN_180128c40` with seed `0x2a` (42). A zero seed is also replaced by 42. The 624-word generator state uses the recurrence:

```text
state[0] = seed
state[i] = 1812433253 * (state[i-1] xor (state[i-1] >>> 30)) + i
```

`FUN_180026fd0` uses the MT19937 twist constant `0x9908B0DF` and the standard 397-word period. The decompiler represents the temper operations with pre-shift masks `0xff3a58ad` and `0xffffdf8c`; after the left shifts and 32-bit truncation these are equivalent to the conventional MT19937 masks `0x9d2c5680` and `0xefc60000`. The output-to-uniform conversion discards the low eight bits, multiplies the remaining 24-bit integer by `2^-24`, then maps `[0,1)` to `[-1,1)`.

The polar sampler draws x then y, rejects radius squared >= 1 and zero coordinates, returns x times the normal scale first, and caches y times the same scale for the next call. The very-small-radius path rescales the coordinates before the logarithm and compensates by `ln(4) * exponent`. This reconstruction is covered by deterministic seed-42 vectors and tests across the 624-word twist boundary; it has not yet been compared against a live S5API render.

At the `FUN_1800c7ad0` call site, the forward-transform wrapper receives the F0 control in XMM2, a render-state float at offset `+0x614` in XMM3, and a stack float constant `0.1` as the fifth argument. This supports the noise-gain and harmonic-gain roles above, but the vendor name for the `+0x614` field is not known.

VEA now has `S5MegpoidNativeMersenneTwister` and a `S5MegpoidGaussianNoise.fromNativeSeed()` factory. `S5MegpoidExcitationGenerator.withRecoveredRandomSource()` connects that deterministic source to the isolated excitation generator. Transform-state scale values, the upstream F0/voicing generation path, and the final spectral/audio path remain unconnected.

## Spectral output conversion

At the end of `FUN_1800c7ad0`, the renderer calls:

```c
FUN_18012c740(
    *(undefined8 *)(renderState + 0x20),
    *(undefined8 *)(renderState + 0x4F8),
    outputFloatBuffer
);
```

The third argument is the caller's float output buffer. The second argument is the model-produced complex spectrum. The first argument is a separate transform/overlap descriptor.

The decompiled `FUN_18012c740` establishes these operations:

1. It copies `N/2 + 1` complex values (two float32 values per bin, `(N/2+1)*8` bytes) from the model spectrum into descriptor-owned scratch storage.
2. If descriptor flag `+0x90` is enabled (descriptor word index `0x24`), it flips the sign bit of both float components for alternating complex bins.
3. It calls `FUN_180040630(plan, descriptorScratch, N, 1)`. The inverse wrapper conjugates the interior imaginary bins, packs the Nyquist real value, doubles the packed real workspace and calls the FFT core with direction `-1`.
4. It divides the first N real samples by N.
5. It writes the transformed frame into a circular overlap buffer using the descriptor's current ring index, frame count, hop/overlap geometry and ring mask.
6. It advances the ring index.
7. It copies/accumulates the relevant overlap window into the caller's output buffer and multiplies by the descriptor-owned window coefficients.

This is a time-domain frame reconstruction/overlap-add stage. It is more specific than the earlier neutral description of the two output float buffers, but sample-rate conversion, channel layout, final gain convention and the exact WAV boundary are not yet established.

## Confirmed transform direction and input-side frame preparation

The added Ghidra trace of `FUN_180040630` shows that it calls the core transform with a direction value of `+1` when its final argument is zero and `-1` when nonzero. The surrounding call sites establish the direction:

- `FUN_18012b150` calls `FUN_180040630(..., N, 0)`: forward transform. It first obtains a real frame through `FUN_18012ad70`, maintains a circular history, applies a window, transforms it, and copies `N/2+1` complex values to its output pointer.
- `FUN_18012c740` calls `FUN_180040630(..., N, 1)`: inverse transform. It copies the model-produced `N/2+1` complex bins to scratch, applies the optional alternating-bin sign convention, transforms, normalizes by `N`, and overlap-adds into the caller's float buffer.

This confirms the output transform direction; it is no longer merely inferred from the surrounding dataflow.

At the `FUN_1800c7ad0` callsites, the excitation path uses the root-owned state wrapper at `renderState + 0x78`. `FUN_18012b150` receives this state and the root-owned frame/spectrum buffer pointer at `+0x88`; `FUN_18012b470` and `FUN_18012b450` receive the same state with buffer pointers at `+0xA0` and `+0xB8`. `FUN_18012b150` uses its own nested transform plan/workspace inside the excitation-state object. These fields are separate from the final inverse-output descriptor.

The final inverse transform/overlap-add call passes the wrapper pointer at `renderState + 0x80` as the first argument, the model-produced complex spectrum at `renderState + 0x4F8` as the second argument, and the caller's float output buffer as the third argument. `FUN_18012c740` dereferences the first argument to obtain its descriptor. The descriptor-relative offsets listed below must not be confused with offsets in the render-state root. The root fields at `+0xF0` and `+0x110` are used by a separate conditional processing branch and are not the `FUN_18012c740` output descriptor.

The higher-level render path also calls `FUN_180127330`, which forwards a per-frame 32-bit value to `FUN_1800aeea0`. That setter writes the value to render-state offset `+0x3678`. The caller sets it to `1.0f` when the rounded frame index is out of range or the selected frame entry is zero, otherwise `0.0f`. This is a verified frame-validity gate value; its vendor field name is unknown.

## Descriptor fields observed in FUN_18012c740

The function treats the first argument as a pointer to an object whose first word is a descriptor pointer. Within that descriptor:

| Word index | Byte offset | Observed use |
|---:|---:|---|
| 0 | +0x00 | Output frame/hop-related loop bound |
| 1 | +0x04 | Window/FFT geometry |
| 2 | +0x08 | Transform size N |
| 4 | +0x10 | Overlap/window offset |
| 5 | +0x14 | Number of overlap contributions |
| 7 | +0x1C | Circular-buffer mask |
| 8 | +0x20 | Circular-buffer write index |
| 10 | +0x28 | Transform plan pointer |
| 12 | +0x30 | Transform scratch / plan workspace |
| 14 | +0x38 | Complex scratch pointer |
| 18 | +0x48 | Window pointer |
| 30 | +0x78 | Circular overlap-buffer pointer |
| 36 | +0x90 | Alternating-bin sign-flip flag |

These names are descriptive observations, not vendor ABI field names. The constructor and all write sites must be traced before using the offsets as a portable public contract.

## Porting implications

- Do not pass note/phoneme features through the public output-buffer argument.
- Keep the existing model forward graph's output as complex spectral bins until the transform stage is implemented.
- The scalar output descriptor, its geometry, frame/output windows, and persistent overlap ring are now represented by the Android reference implementation. The vendor FFT plan factory at `FUN_18004e580` and its table-generation helpers still need an equivalent implementation or differential parity check.
- The Android implementation must preserve the ring-buffer state across sequential audio chunks and reset it at utterance boundaries.
- The caller output length is owned by the render state; do not infer it from the VTB2 model tensor sizes.

## Remaining work

1. Trace `FUN_18004e580`, `FUN_18004e620`, `FUN_18002ace0` and `FUN_180010110` to recover the FFT-plan tables and execution workspace; then compare the Android reference transform against the vendor scalar FFT path.
2. Connect the render-state model spectrum at root +0x4F8 to the complete neural forward graph and its upstream note/phoneme feature-conditioning path.
3. Establish the signal-domain meaning and scaling of the higher-level float output buffers at engine offsets +0xA0 and +0xB8.
4. Verify sample-rate conversion, channel layout and the final PCM/WAV boundary in the higher-level VSM caller.

No successful Megpoid vocal render or WAV output is claimed.

## Model-produced spectrum buffer shape

The focused root-state trace and FUN_1800d5a00 decompilation establish the
model-spectrum vector at render-state root +0x4F8:

- `FUN_1800d5a00` adjusts the vector to `param[2]` elements using 8-byte
  element arithmetic.
- `FUN_1800c7ad0` clears the vector as `param[2]` qwords before filling it.
- The final call passes that vector directly as the complex-spectrum argument
  to `FUN_18012c740`.
- For AI Megpoid V7, runtime-options `param[1] = 1024` is the FFT size and
  `param[2] = 513` is the complex-bin count. This matches `N/2 + 1` for a
  one-sided real FFT.

`S5MegpoidSpectrumOutputShape` models this as 513 interleaved complex bins,
or 1026 float32 values in `[real0, imag0, real1, imag1, ...]` order.
`PreparedMegpoidRuntime.newModelSpectrumFrame()` now allocates a correctly
sized zeroed destination for the future neural forward pass. This is only
the output-buffer contract; no model values are generated by this allocator.
