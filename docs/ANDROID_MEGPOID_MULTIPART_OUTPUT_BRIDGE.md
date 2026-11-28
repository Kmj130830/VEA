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
tensor at +0xE0. `S5MegpoidCarrierFusionFrameInput` now has a factory that
takes that sequence output plus the separately generated interleaved complex
carrier spectrum and splits the spectrum into the two planar D80 history
inputs. The exact native carrier-spectrum generator behind FUN_18012b140 is
being traced separately; it must not be replaced by the periodic/noise
excitation transform FUN_18012b150 without evidence.

## Spectral frame reconstruction

`PreparedMegpoidRuntime.processMultipartOutputFrameToPcm()` currently
combines:

1. the caller-supplied eight-plane active feature tensor;
2. the per-frame four-part modulation table;
3. the separate carrier feature/history inputs required by this voicebank;
4. the stateful `PreparedMegpoidSpectralOutputSession`.

The spectral session checks the expected 513-bin one-sided complex spectrum,
executes the 1024-point inverse transform and overlap-add, and returns a
320-sample PCM block. The per-utterance reset helper clears the spectral
session and optional carrier history state.

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

1. Produce the eight active feature planes from the actual note/phoneme-
   conditioned VTB2 forward path. The current explicit-phone session ends at
   an intermediate 32-channel sequence tensor; the mapping through the
   active post-residual/interpolation graph is not yet implemented.
2. Implement the upstream carrier generator that supplies the independent
   two-plane history frame and the correct carrier-conditioned feature input.
3. Reconstruct native excitation frame history/window scheduling and connect
   F0, voicing, theta, scale and amplitude controls to their correct stages.
4. Compare Android complex spectra and PCM frames against authorized native
   reference frames, including history warm-up, voiced/unvoiced transitions,
   and chunk boundaries.
5. Only after those checks, connect the complete path to
   `SynthesisEngine.render()` and enable WAV export.

No end-to-end Megpoid vocal render or verified WAV is claimed at this stage.
