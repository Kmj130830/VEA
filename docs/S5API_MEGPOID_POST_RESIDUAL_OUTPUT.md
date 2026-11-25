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
conversion and output-transform call. The supplied AI Megpoid option values
also establish that its multi-part output-head condition is enabled. The
remaining unknowns are the detailed per-part interpolation/carrier parameters
and the model tensors feeding the second output head.

## 1. Post-residual projection and normalization branches

After the temporal/reverse-residual work, the function selects an output-head
branch using root flag `param_1[0xd01]` (byte position `+0x3404`). The
multi-part branch also reads `param_1[0xd00]` (root `+0x3400`) as a loop
count and only enters its multi-part body when the count is at least two.

The non-multi-part fallback path performs a further global feature
normalization and dense projection through descriptors at root `+0x310`
and `+0x338` (`param_1 + 0xc4` and `param_1 + 0xce`, expressed as
32-bit-word indices):

- `FUN_1800c7920(param_1 + 0xc4, inputFeatureBuffer)`
- `FUN_1800c5260(param_1 + 0xce, normalizedFeatureBuffer, outputFeatureBuffer)`

These are additional operators, distinct from the first pre-residual
Dense/normalization descriptors at root `+0x1F8` and `+0x228`. Their
model-property keys, dimensions, and weight provenance have not yet been
mapped. This fallback branch is not the same as the selected D01 multi-part
branch used by the current AI Megpoid options. The debug-dump branch for
render modes 10 and 11 calls the same operators directly while writing
intermediate buffers to the optional trace file; those debug modes must not
be mistaken for separate model architecture.

### AI Megpoid branch selection from the installed VTB2 options

The native model-population routine `FUN_1800ced10` copies the option
`D00` to render-root word `+0xD00` and option `D01` to byte
`+0xD01`. It derives root word `+0xD07` as:

```text
if (D01 && D00 >= 2):
    root[0xD07] = D00 * 2
else:
    root[0xD07] = 2
```

The supplied AI Megpoid V7 runtime-options object contains `D00 = 4`
and `D01 = true`. Thus its traced render path enters the multi-part
branch and the derived post-residual head feature width at root word
`+0xD07` is 8. This width is distinct from the final two feature planes
converted to 513 complex spectral bins; it describes the preceding
multi-part output-head representation.

Android now exposes `usesMultiPartOutputHead` and
`postResidualHeadFeatureWidth` on `S5MegpoidRuntimeOptionsDescriptor`.
Tests cover the actual 4-part/8-feature case, disabled mode, one-part
fallback and integer overflow rejection. The per-part projection,
interpolation, carrier-history and final reduction kernels are still not
wired to VTB2 tensors.

## 2. Independent carrier-fusion selector

The model loader separately reads another boolean option through
`FUN_1800dfb50()` and stores it at root byte `+0x3559`. The forward
renderer checks this byte independently of the D00/D01 multi-part selector.
When enabled, it prepares an additional carrier-fusion path; this path
allocates per-frame carrier history at root words `+0xD80` and a second
feature buffer at root word `+0xD74`, with auxiliary dimensions/cursors at
`+0xD7C` and `+0xD7E`.

When the carrier-fusion path is enabled, the configuration loader constructs
a separate Dense descriptor at root word `+0xD60` (byte offset `+0x3580`)
using the shared VTB2 matrix descriptor factory. The forward path also
contains a fallback sub-branch that copies the current feature tensor,
applies the global normalizer at word `+0xC4`, and projects with that
`+0xD60` Dense descriptor. In the active carrier-fusion sub-branch, the
renderer instead builds a carrier-conditioned two-feature input, projects
and combines the output over retained carrier frames, then mixes the result
with the complex per-part output.

The carrier selector is therefore not implied by D00/D01. VEA must resolve
the `FUN_1800dfb50()` property key separately and check whether it exists in
the selected AI Megpoid VTB2 tree before choosing whether to build the
carrier Dense descriptor or allocate carrier-history workspaces. This also
decides whether the optional `+0xD60` Dense branch is active.
## 3. Multi-part feature/modulation branch

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

## 4. Terminal two-plane to complex-spectrum conversion

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

This establishes two possible two-plane representations. In the traced
new-root initialization path, `FUN_1800bca40` zeroes the selector byte at
root `+0x48`, and `FUN_1800ced10` does not overwrite it. The supplied AI
Megpoid render-state creation path uses that fresh root, so direct-complex
is the selected default for this path. Android's
`processMegpoidDefaultOutputPlanes()` now uses `DIRECT_COMPLEX`; the explicit
log-magnitude/phase mode remains available for other render-state paths that
may set the selector differently.

## 5. Inverse transform and output block

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

The active value of root `+0x48` is loaded by `FUN_1800ced10` from a
separate boolean option key returned by `FUN_1800e6c20()`. The root
constructor's zero initialization is therefore not the final configured
value. VEA keeps the encoding explicit until that hashed key is recovered
from the helper and matched to the installed AI Megpoid VTB2 tree. The two
modes are operator implementations, not a claim that the full neural graph
has generated those feature planes.

## 6. Remaining output-head work

1. Recover the hashed boolean option returned by `FUN_1800e6c20()` and identify its value in AI Megpoid VTB2; this controls root `+0x48` direct-complex versus log-magnitude/phase encoding.
2. Map the second global-normalization and dense descriptors at root `+0x310` and `+0x338` to VTB2 keys and active dimensions.
3. Trace `FUN_1800c3930`, `FUN_1800d52b0` and `FUN_1800d5610` for the per-part interpolation and carrier-mix branches.
4. Implement the model-backed 8-feature output head, its per-part modulation and final reduction to the two terminal feature planes.
5. Connect the completed upstream sequence pipeline to the second head and the carrier-history branch.
6. Compare complex spectra and overlap-add output against authorized native reference frames before enabling WAV rendering.

Until these are resolved and tested on Android ARM64, `SynthesisEngine.render()`
must remain unavailable and must not emit placeholder audio.
