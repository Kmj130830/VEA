# AI Megpoid V7 Android multipart output bridge

Date: 2026-11-26

## Scope and evidence

This note records the current Android implementation boundary for the AI
Megpoid V7 multi-part output path. The Windows S5API module was analyzed
statically with Ghidra; it was not executed as an Android renderer. No
bit-exact native audio comparison or end-to-end vocal render is claimed.

## Important branch distinction: fallback Dense versus active multipart path

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
guarded and refuses when the active options select multipart output.

## Active two-plane feature pair

The active `FUN_1800c7ad0` path consumes one shared complex feature pair:

- first plane: one float32 value per output position;
- second plane: one float32 value per output position.

The two planes are contiguous in the active feature buffer. The native outer
loop then iterates the four D00 parts, reusing the same feature-plane pointers
for each part. It does not require an eight-row tensor split into four distinct
complex pairs.

Android represents this input as
`S5MegpoidMultipartComplexFeaturePair`. The
`S5MegpoidMultipartFeaturePlaneExtractor` validates a feature-major array
containing exactly `2 * positionCount` values, then extracts the two planes.
The upstream model graph that creates those values is not yet connected.

## Part modulation and complex accumulation

`S5MegpoidMultipartOutputModulationBuilder` prepares the four-part
position, distance, gain and rotation coefficients using the recovered
runtime options and per-frame F0/theta controls.

Native coefficient arrays are **part-major**:

```text
tableIndex = partIndex * positionCount + position
```

The same input pair `x[position]`, `y[position]` is reused for every
part. Each part applies its own complex rotation and gain:

```text
out.real += (x * D46 - y * D4C) * D40
out.imag += (y * D46 + x * D4C) * D40
```

`S5MegpoidMultipartOutputModulation.accumulateAllPartsInto()` follows the
native part loop and adds the contributions into one interleaved complex
spectrum buffer.

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

1. a caller-supplied active two-plane feature tensor;
2. the per-frame four-part modulation table;
3. the separate carrier feature/history inputs required by this voicebank;
4. the stateful `PreparedMegpoidSpectralOutputSession`.

The spectral session checks the expected one-sided complex-bin count,
executes the 1024-point inverse transform and overlap-add, and returns a
320-sample PCM block. The per-utterance reset helper clears the spectral
session and optional carrier history state.

This is a downstream frame-processing API. It is not yet a complete renderer
from piano-roll notes to PCM.

## Still missing before note-to-WAV synthesis

1. Produce the shared active two-plane feature tensor from the actual
   note/phoneme-conditioned VTMG/VTB2 forward path. The current explicit-phone
   session ends at an intermediate 32-channel sequence tensor; its mapping
   to the active two-plane output has not been proven or implemented.
2. Implement the upstream carrier generator that supplies the independent
   two-plane history frame and the correct carrier-conditioned feature input.
3. Connect real-time F0, theta, scale and amplitude controls to the modulation
   and carrier input stages.
4. Compare Android complex spectra and PCM frames against authorized native
   reference frames, including history warm-up, voiced/unvoiced transitions,
   and chunk boundaries.
5. Only after those checks, connect the complete path to
   `SynthesisEngine.render()` and enable WAV export.

No end-to-end Megpoid vocal render or verified WAV is claimed at this stage.
