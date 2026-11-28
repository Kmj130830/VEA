# AI Megpoid V7 Android multipart output bridge

Date: 2026-11-28

## Scope and evidence

This note records the current Android implementation boundary for the AI
Megpoid V7 multi-part output path. The Windows S5API module was analyzed
statically with Ghidra; it was not executed as an Android renderer. No
bit-exact native audio comparison or end-to-end vocal render is claimed.

## Branch distinction: fallback Dense versus active multipart path

The VTB2 properties indexed by
`S5MegpoidPostResidualFallbackTensorIndex`:

- `d55956a69b51b6f6dd43bd0cc2467bcf` (normalization scale),
- `bf96a6e83ab8bb7d33509f2d3a1a348f` (normalization bias),
- `051fe4a0e8e7e92c3c8791bd28f24da8` (Dense weights),
- `3a9561654ca2e128021a59d14813a88f` (Dense bias),

provide the shared 32-feature input normalizer and [8,32] Dense matrix/bias
used by the non-multipart fallback descriptor. The installed AI Megpoid V7
options select D00=4 and D01=true, so the fallback runtime descriptor itself
must not be used as the active output operator.

The active branch nevertheless reuses these same four source tensors. Native
initialization folds them into a different matrix and two row vectors at
render-root word indices +0x150/+0x154/+0x158 (byte offsets +0x540/+0x550/
+0x560). Android now constructs this folded active operator separately as
`S5MegpoidActiveMultipartProjectionStage`; the guarded
`preparePostResidualFallbackStage()` still refuses when multipart output
is selected.

## Active 32-to-8 folded projection

The active output head consumes a 32-feature render-context tensor before
the eight planes are split into four complex pairs. The VTB2 source tensors
are the same four objects already indexed by
`S5MegpoidPostResidualFallbackTensorIndex`:

- normalizer scale: 32 values;
- normalizer bias: 32 values;
- Dense weights: `[8,32,1,1]`;
- Dense bias: 8 values.

During `FUN_1800ced10`, S5API folds these source values into a row-major
8-by-32 matrix and two eight-value vectors:

```text
Wfold[o,i] = W[o,i] * inputScale[i]
Bfold[o]   = DenseBias[o] + sum_i(W[o,i] * inputBias[i])
Sfold[o]   = sum_i(Wfold[o,i])
```

At render time, `FUN_1800c7ad0` obtains one global mean and inverse standard
deviation over the complete 32-by-position input tensor, computes the
matrix projection, then applies:

```text
Y[o,p] = inverseStd * GEMM(Wfold, X)[o,p]
          + ((-inverseStd * mean) * Sfold[o] + Bfold[o])
```

`S5MegpoidActiveMultipartProjectionStage` now reproduces this folded form
from the four decoded VTB2 source tensors. It does not use the direct
non-multipart +0xC4/+0xCE runtime descriptor. The final sequence-slot buffer at render-state +0xE0 is the actual source
passed into the active 32-to-8 projection. The Android runtime now accepts
`S5MegpoidSequenceTransitionOutput` directly and validates its 32-channel
feature-major shape. It only proceeds when the sequence position count matches
the spectral frame's one-sided bin count; it does not fabricate the missing
native time/interpolation alignment when those counts differ.

## Active eight-plane feature layout

The active `FUN_1800c7ad0` branch consumes eight feature planes, because the
derived post-residual feature width is D00 * 2 = 8. The native per-part loop
advances its real/imaginary feature pointers by `2 * positionCount` values
for each D00 part. The resulting feature-major layout is:

```text
[part0.real][part0.imag]
[part1.real][part1.imag]
[part2.real][part2.imag]
[part3.real][part3.imag]
```

Each row contains exactly `positionCount` float32 values. For AI Megpoid's
1024-point FFT, `positionCount` is 513, so the active feature input contains
4104 float32 values before complex modulation.

Android represents this input as `S5MegpoidMultipartFeaturePlanes`.
`S5MegpoidMultipartFeaturePlaneExtractor` validates the exact
`2 * D00 * positionCount` shape and separates each consecutive pair into
one part's real and imaginary planes. It copies the data defensively and
rejects non-finite values or mismatched dimensions. The upstream graph that
produces the eight rows is not yet connected to this boundary.

## Part modulation and complex accumulation

`S5MegpoidMultipartOutputModulationBuilder` prepares the four-part
position, distance, gain and rotation coefficients using the recovered
runtime options and per-frame F0/theta controls.

Native coefficient arrays are **part-major**:

```text
tableIndex = partIndex * positionCount + position
```

Each part applies its own feature pair and complex rotation/gain:

```text
scaledReal = x[part][position] * D40
scaledImag = y[part][position] * D40
out.real += D46 * scaledReal - D4C * scaledImag
out.imag += D4C * scaledReal + D46 * scaledImag
```

`S5MegpoidMultipartOutputModulation.accumulateAllPartsInto()` follows the
native part loop, adds all four contributions into one interleaved complex
spectrum buffer, and preserves the native signs and per-part coefficient
indexing.

## Carrier fusion

The selected AI Megpoid options independently enable carrier fusion.
`S5MegpoidCarrierFusionWorkspace` ports the active 2-by-K projection,
affine controls, two-plane history ring, native delayed-slot selection and
complex accumulation.

The history frame comes from a separate carrier-generation input at native
render-state +0x2E4; it is not the output of the D74 carrier Dense projection.
The carrier Dense input, however, is the same final 32-channel sequence
tensor at +0xE0. `S5MegpoidCarrierFusionFrameInput` takes that sequence output
plus the interleaved complex carrier spectrum and splits it into the two
planar D80 history inputs.

The carrier spectrum path is now ported separately as
`S5MegpoidCarrierSpectrumSession`, based on the native
`FUN_18012b140 -> FUN_18012b4a0` path. The wrapper passes a null optional
sample pointer, so the scalar core fills the hop with its state-owned
Gaussian source. The Android session uses the recovered MT19937/Marsaglia
sampler with the native constructor seed 12345, writes samples into the
power-of-two ring, selects the delayed windowed frame, runs the forward real
FFT, and optionally flips both real and imaginary values for odd bins
starting at bin 1.

For the AI Megpoid initializer call sites, the state constructor receives
hop length from render-root +0x0C, window length and FFT size from +0x04,
window core length from +0x61C, odd-bin inversion from +0x620, seed 12345,
and window shape mode 0 for the primary carrier state. The alternate
extended state can take its window shape mode from +0x3418. The ring geometry follows the native
`ceil(windowLength/hop)+1` lag count, `(fftSize-hop)/2` left pad, and
next-power-of-two allocation formula:
`(ceil(windowLength/hop)+2)*hop + leftPad + fftSize - 1 + hop`.
 The prepared runtime exposes a
factory for this session and a sequence-output-to-PCM path that can generate
the carrier spectrum internally.

`FUN_18012b320` calls the same `FUN_18012b4a0` core through an alternate
wrapper form. `FUN_18012b330` dispatches multiple transform states through
the scalar `FUN_18012b620` path or the batched/vectorized
`FUN_18012bb30` path. That multi-state SIMD route is not yet ported; the
current Android session follows the scalar `FUN_18012b4a0` route used by
the direct wrapper. This carrier path must not be replaced by the distinct
periodic/F0 excitation transform `FUN_18012b150`.

## Timed phone-to-frame ownership

The desktop S5API front end builds an event-level phone feature matrix with
`FUN_18006c720`, then `FUN_18003ab70` constructs a separate timed frame map.
The frame period is the float at phone descriptor +0x30 (seconds). The native
frame count is the integer truncation of the final phone end time divided by
that period. Each event owns frames from the previous event's end-frame cursor
through `min(frameCount, ceil(eventEnd / framePeriod))`.

For every owned frame, native S5API stores:
- the source phone-event index;
- the frame start relative to that event's start time;
- the full event duration;
- an event control of -1 for phone categories 0/1, otherwise the caller's
  `param_8` value.

Android now preserves optional descriptor +0x30 in
`S5ApiDefaultPhoneFeatureDescriptor` and implements the timing/index arrays
in `S5ApiTimedPhoneFrameAligner`. The explicit-phone sequence session can
attach this plan to its result when the caller supplies the native
category-2-to-13 control value. The plan indexes the post-preprocessing phone
events that correspond row-for-row to the event-level feature matrix.

This is the time ownership map only. The subsequent stateful
`FUN_180076080` / `FUN_180077520` transformations that turn those event
features and per-frame timing values into the actual frame-aligned model
features are not yet ported. The current sequence pipeline still processes
the event-level phone matrix separately; the new timing map is exposed for
the next integration stage and is not claimed to be a VTMG output tensor or
a 513-bin acoustic spectrum.

## Spectral frame reconstruction

`PreparedMegpoidRuntime.processMultipartOutputFrameToPcm()` currently
combines:

1. the caller-supplied eight-plane active feature tensor;
2. the per-frame four-part modulation table;
3. the separate carrier feature/history inputs required by this voicebank;
4. the stateful `PreparedMegpoidSpectralOutputSession`.

The spectral session checks the expected 513-bin one-sided complex spectrum,
executes the 1024-point inverse transform and overlap-add, and returns a
320-sample PCM block. The per-utterance reset helper clears the spectral session, optional
carrier history workspace, and optional carrier spectrum generator state.

This is a downstream frame-processing API. It is not yet a complete renderer
from piano-roll notes to PCM.

## Forward excitation transform

`S5MegpoidExcitationGenerator` already maintains continuous F0, harmonic/
noise gain and phase state, and its optional Gaussian source uses the
recovered seed-42 MT19937/Marsaglia path. `S5MegpoidForwardRealFft` provides a portable radix-2 real-to-complex
reference kernel for a complete, already-windowed excitation frame. It
returns 513 complex bins for a 1024-sample frame and is paired with the
existing inverse reference kernel in round-trip tests.

`S5MegpoidForwardExcitationSession` now combines the recovered excitation
generator, a circular sample-history buffer, an explicitly supplied hop size
and window, and the forward FFT. Each call generates one excitation hop,
writes it into history, reconstructs a chronological N-sample frame, applies
the caller's window, and returns one-sided complex bins. The prepared runtime
factory requires phase/period scales, denominator threshold, wrap mode,
hop size and window to be supplied; it does not guess the vendor's transform
state values or assume that the 320-sample final output block is the input
excitation hop.

This FFT/session is a mathematical Android reference, not a bit-exact
reconstruction of the vendor plan tables. The exact native initialization
history, frame-validity gate, hop/window parity and mapping from generated
excitation spectra into the active model feature tensors remain unverified.

## Still missing before note-to-WAV synthesis

1. Port the stateful frame-feature path after
   `S5ApiTimedPhoneFrameAligner`: native `FUN_180076080` / `FUN_180077520`
   consume event features, event indexes and per-frame relative-time/duration
   values to create the actual frame-aligned model features. The current
   frame map is an intermediate timing/index plan, not that VTMG output.
2. Reconstruct the exact mapping from those frame-aligned features to the
   513-bin spectral frame positions; the present direct sequence-to-PCM API
   only accepts a caller-provided position count that already matches the
   spectral session and does not infer the missing mapping.
3. Port the multi-state carrier dispatch route
   (`FUN_18012b330` -> scalar `FUN_18012b620` / vectorized
   `FUN_18012bb30`) and confirm which route is selected for each singer and
   CPU capability.
4. Map the remaining per-frame control values (theta, F0, carrier scale,
   amplitude, window core and shape flags) from the native render context
   rather than requiring the caller to provide them.
5. Compare Android carrier spectra, active multipart spectra and PCM blocks
   against authorized native reference frames, including startup delay,
   odd-bin inversion, history wrap, SIMD/scalar paths and chunk boundaries.
6. Only after those differential checks, connect the complete note-to-frame
   path to `SynthesisEngine.render()` and enable WAV export.

No end-to-end Megpoid vocal render or verified WAV is claimed at this stage.
