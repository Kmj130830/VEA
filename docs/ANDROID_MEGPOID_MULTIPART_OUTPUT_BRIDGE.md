# AI Megpoid V7 Android multipart output bridge

Date: 2026-11-26

## Scope

This note records the current Android implementation boundary for the AI Megpoid V7 multi-part output path. The S5API Windows DLL was statically inspected; it was not executed as an Android runtime, and no bit-exact native audio comparison has been completed.

## Important correction: the 32-to-8 Dense is fallback-only

The VTB2 properties indexed by `S5MegpoidPostResidualFallbackTensorIndex`:

- `d55956a69b51b6f6dd43bd0cc2467bcf` (normalization scale),
- `bf96a6e83ab8bb7d33509f2d3a1a348f` (normalization bias),
- `051fe4a0e8e7e92c3c8791bd28f24da8` (Dense weights),
- `3a9561654ca2e128021a59d14813a88f` (Dense bias),

construct a 32-to-8 post-residual projection used by the non-multipart fallback branch. The AI Megpoid V7 options select D00=4 and D01=true, so this projection must not be inserted into the active four-part path.

Android therefore does **not** use this fallback stage to synthesize the active multipart feature tensor. The model-package API `preparePostResidualFallbackStage()` remains guarded so it refuses when the multi-part path is active.

## Ported pieces

### Four-pair feature-plane address mapping

The native per-part loop consumes a sequence of consecutive feature planes in pairs. `S5MegpoidMultipartFeaturePlaneExtractor` accepts an already-produced feature-major tensor with `2 * partCount` rows and separates each adjacent row pair into the corresponding part's first and second plane.

For AI Megpoid V7, the expected count is eight rows (four pairs). The extractor validates the tensor length and finite values. It does not claim to generate this tensor from note/phoneme inputs.

### Per-part modulation

`S5MegpoidMultipartOutputModulationBuilder` and `S5MegpoidMultipartOutputModulation` provide the recovered four-part location, distance, gain and real/imaginary rotation values. The scalar accumulation order is:

```text
real += gain * (rotationReal * first - rotationImag * second)
imag += gain * (rotationImag * first + rotationReal * second)
```

`PreparedMegpoidRuntime.accumulateMultipartComplexOutput()` applies those coefficients to the caller-supplied multipart feature tensor and returns one interleaved complex accumulator.

### Carrier fusion

The selected AI Megpoid options independently enable carrier fusion. `S5MegpoidCarrierFusionWorkspace` ports the active 2-by-K projection, affine controls, two-plane ring history, native delayed-slot selection and complex accumulation.

The history frame comes from a separate carrier-generation input at native render-state +0x2E4; it is not the D74 projection output. `S5MegpoidCarrierFusionFrameInput` keeps the feature input and the two history planes as explicit independent inputs. The upstream source that creates those buffers is not yet implemented in Android.

### Spectral block output

`PreparedMegpoidRuntime.processMultipartOutputFrameToPcm()` combines the already-prepared multipart feature tensor, modulation, optional active carrier inputs and `PreparedMegpoidSpectralOutputSession`. For AI Megpoid it requires the carrier input because that option is enabled.

The spectral session validates the one-sided complex-bin count, runs the 1024-point inverse transform and overlap-add, and returns one 320-sample PCM block. This is a downstream frame processor, not yet an end-to-end voice renderer.

## Still missing before note-to-WAV synthesis

1. Produce the active multi-part feature tensor from the real VTMG/sequence forward path. The currently available explicit-phone session ends at an intermediate 32-channel VTB2 sequence tensor; it is not yet proven to be the active multi-part input.
2. Implement the upstream carrier generator that supplies the independent two-plane history frame and the correct carrier-conditioned feature input.
3. Connect the real-time F0, theta, scale and amplitude controls to the modulation and carrier input stages.
4. Compare Android complex spectra and PCM blocks against authorized native reference frames, including history warm-up, voiced/unvoiced transitions and chunk boundaries.
5. Only after those checks, connect the complete path to `SynthesisEngine.render()` and enable WAV export.

No end-to-end Megpoid vocal render or verified WAV is claimed at this stage.
