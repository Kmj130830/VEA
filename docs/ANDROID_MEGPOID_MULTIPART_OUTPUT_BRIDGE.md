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

construct a 32-to-8 post-residual projection for the non-multipart fallback
branch. The installed AI Megpoid V7 options select D00=4 and D01=true, so this
fallback projection must not be inserted into the active multipart path.

The Android model-package API `preparePostResidualFallbackStage()` remains
guarded and refuses when active options select multipart output.

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
out.real += (x[part][position] * D46 - y[part][position] * D4C) * D40
out.imag += (y[part][position] * D46 + x[part][position] * D4C) * D40
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
`S5MegpoidCarrierFusionFrameInput` keeps the Dense feature input and the two
history planes as explicit independent inputs. The upstream generator that
creates those buffers is not yet integrated into Android.

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
recovered seed-42 MT19937/Marsaglia path. A new
`S5MegpoidForwardRealFft` provides a portable radix-2 real-to-complex
reference kernel for a complete, already-windowed excitation frame. It
returns 513 complex bins for a 1024-sample frame and is paired with the
existing inverse reference kernel in round-trip tests.

This FFT is a mathematical Android reference, not a bit-exact reconstruction
of the vendor plan tables. Native excitation history/window scheduling and
the mapping from the generated excitation spectrum into the active model
feature tensors remain unconnected.

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
