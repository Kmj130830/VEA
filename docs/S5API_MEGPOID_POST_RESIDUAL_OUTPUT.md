# AI Megpoid V7 post-residual output head and spectrum conversion

Date: 2026-11-25

## Evidence and scope

This note records the trailing section of the statically decompiled
`FUN_1800c7ad0` in the supplied `S5API.dll`. The Windows module was not
executed. Offsets below are relative to that function's render-state root
argument, `param_1`. They are not offsets into the separate VTMG model
object.

The trace begins after the 4x2 residual-block feedback loop and continues
through the call to `FUN_18012c740`. It identifies the terminal feature-pair
conversion and output-transform call. It does not yet recover every
intermediate modulation/carrier parameter or the active value of the
terminal representation flag.

## 1. Post-residual projection and normalization branches

After the temporal/reverse-residual work, the function selects an output-head
branch using root flag `param_1[0xd01]` (byte position `+0x3404`). The
multi-part branch also reads `param_1[0xd00]` (root `+0x3400`) as a loop
count and only enters its multi-part body when the count is at least two.

The ordinary path performs a further global feature normalization and dense
projection through descriptors at root `+0x310` and `+0x338`
(`param_1 + 0xc4` and `param_1 + 0xce`, expressed as 32-bit-word indices):

- `FUN_1800c7920(param_1 + 0xc4, inputFeatureBuffer)`
- `FUN_1800c5260(param_1 + 0xce, normalizedFeatureBuffer, outputFeatureBuffer)`

These are additional output-head operators, distinct from the first
pre-residual Dense/normalization descriptors at root `+0x1F8` and
`+0x228`. Their model-property keys, dimensions, and weight provenance
have not yet been mapped. The debug-dump branch for render modes 10 and 11
calls the same operators directly while writing intermediate buffers to
the optional trace file; those debug modes must not be mistaken for separate
model architecture.

## 2. Multi-part feature/modulation branch

When root `+0x3404` is enabled and the count at root `+0x3400` is at least
two, the function prepares per-part interpolation/control buffers from the
render state, calls `FUN_1800c3930`, and then iterates the configured parts
while accumulating complex-valued output pairs. A second flag at root
`+0x3559` gates an additional carrier-mix path.

The latter reads a retained carrier frame/history window and combines its
real/imaginary values with the per-part output. The function's optional
diagnostic labels include `Xcarrier_real`, `Xcarrier_imag`,
`free_real_modN`, `free_imag_modN`, `mask_g_modN`,
`XmodN_real` and `XmodN_imag`. These names support identifying the
buffer roles as carrier and per-part complex feature streams, but do not
establish the full user-facing meaning of each scalar control.

The exact interpolation rule, carrier-history ownership and model property
sources are reserved for a focused trace of `FUN_1800c3930`,
`FUN_1800d52b0` and `FUN_1800d5610`. VEA's current Android pipeline does
not implement this multi-part or carrier-mix branch.

## 3. Terminal two-plane to complex-spectrum conversion

Immediately before the inverse-FFT/output-transform call, the function reads
two contiguous real feature planes from the output feature buffer. Each
plane has `param_1[2]` float values. It writes `param_1[2]` complex values
to the interleaved output buffer at root `+0x13e * 4` (byte offset
`+0x4F8`).

The representation selector is the byte at root `+0x48`
(`param_1[0x12]`). Both branches are direct observations:

### Selector off: direct complex pair

```text
spectrum[2*i]     = featurePlane0[i]
spectrum[2*i + 1] = featurePlane1[i]
```

### Selector on: log magnitude and phase

```text
magnitude = min(exp(featurePlane0[i]), 100)
real      = magnitude * cos(featurePlane1[i])
imag      = magnitude * sin(featurePlane1[i])
spectrum[2*i]     = real
spectrum[2*i + 1] = imag
```

The native branch uses float32 `expf`, `cosf`, `sinf`, and an upper
magnitude limit of 100. No lower clamp is visible in this terminal loop;
underflow to zero is therefore preserved.

This establishes two possible two-plane representations. It does not yet
establish which value of the root `+0x48` selector is used by the supplied
AI Megpoid package at runtime.

## 4. Inverse transform and output block

The resulting interleaved spectrum is passed with the output-transform
descriptor pointer at root `+0x20` to:

`FUN_18012c740(transformDescriptor, interleavedSpectrum, outputBlock)`

The output block contains `param_1[3]` samples. For the supplied AI Megpoid
runtime options, the separately recovered descriptor fields provide
`param[1] = 1024` (FFT size), `param[2] = 513` (one-sided complex bins),
and `param[3] = 320` (output samples per frame). These dimensions agree
with the terminal buffer geometry: 513 complex values, 1026 interleaved
float32 values, then 320 time-domain samples from the inverse/overlap stage.

The existing Android `S5MegpoidSpectralOverlapAdd` ports the latter
inverse-FFT and overlap-add component. The newly added
`S5MegpoidSpectrumFrameEncoder` ports the two terminal feature-pair
representations, and `PreparedMegpoidSpectralOutputSession` can now accept
the two model output planes and pass their encoded complex spectrum into the
existing overlap-add processor.

The caller must explicitly select `DIRECT_COMPLEX` or
`LOG_MAGNITUDE_PHASE`; VEA does not yet map root `+0x48` to a confirmed
AI Megpoid runtime option. The two new modes are operator implementations,
not a claim that the full neural graph has generated those feature planes.

## 5. Remaining output-head work

1. Recover the exact active root `+0x48` representation flag and its model/configuration source.
2. Trace `FUN_1800c3930`, `FUN_1800d52b0` and `FUN_1800d5610` for the multi-part and carrier-mix branches.
3. Map the second global-normalization and dense descriptors at root `+0x310` and `+0x338` to VTB2 keys and active dimensions.
4. Connect the completed upstream sequence pipeline to the second head and the correct modulation/carrier branch.
5. Compare complex spectra and overlap-add output against authorized native reference frames before enabling WAV rendering.

Until these are resolved and tested on Android ARM64, `SynthesisEngine.render()`
must remain unavailable and must not emit placeholder audio.
