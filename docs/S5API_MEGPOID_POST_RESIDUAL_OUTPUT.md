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

### Model tensors for the non-multi-part fallback head

The render-state population path constructs the fallback descriptors
unconditionally, even though the forward dispatcher selects them only when
`D01 == false` or `D00 < 2`. Their four VTB2 getter keys and exact
metadata are now recovered:

| Descriptor role | Getter | Lookup key | Metadata / payload |
|---|---|---|---|
| +0xC4 normalization scale | `FUN_1800e9ac0` | `d55956a69b51b6f6dd43bd0cc2467bcf` | `[32]` / 32 F32 |
| +0xC4 normalization bias | `FUN_1800e9900` | `bf96a6e83ab8bb7d33509f2d3a1a348f` | `[32]` / 32 F32 |
| +0xCE Dense weights | `FUN_1800e9e40` | `051fe4a0e8e7e92c3c8791bd28f24da8` | `[8,32,1,1]` / 256 F32 |
| +0xCE Dense bias | `FUN_1800e9c80` | `3a9561654ca2e128021a59d14813a88f` | `[8]` / 8 F32 |

The setter order at `FUN_1800ced10` confirms scale before bias for
`FUN_1800d4a90`, then Dense matrix before Dense bias for
`FUN_1800d4610`. The Dense constructor is called with 32 input features,
8 output features and bias enabled. The normalizer epsilon comes from
float32 bit pattern `0x358637bd` (1.0e-6), and the forward order is global
normalization followed by Dense projection.

Android now indexes and validates these four nested metadata/payload
objects in `S5MegpoidPostResidualFallbackTensorIndex`, and builds a
32→8 stage in the exact native operator order. This is not the active
AI Megpoid head: its VTB2 options set `D00=4` and `D01=true`, selecting
the four-part/8-feature path. The fallback builder refuses to run when
that multi-part branch is selected.

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

When the carrier-fusion option is present, the configuration loader also
constructs a fallback Dense descriptor at root word `+0xD60` (byte offset
`+0x3580`) using the shared VTB2 matrix descriptor factory. This descriptor
is used by the fallback sub-branch when the runtime `MRW_CARRIER_FUSE`
switch is disabled, root `+0x16C` is false, or the dynamic source matrix at
`+0x15E` is absent. That fallback copies the current feature tensor, applies
the global normalizer at word `+0xC4`, and projects with `+0xD60`.

The active carrier branch does **not** use the VTB2 `+0xD60` weights as its
projection matrix. It uses a runtime-generated `[2][K]` source matrix at
`+0x15E`, multiplies it by the current `[K][N]` feature tensor with
`FUN_1800c3740(M=2,N,K)` to create D74, then applies per-row affine values
derived from scalars at `+0x16A/+0x16B` and two-value vectors at
`+0x162/+0x166`. It subsequently mixes the delayed planar carrier-history
slot into the complex per-part output.

The carrier selector is therefore not implied by D00/D01. The getter
`FUN_1800dfb50()` resolves to key
`66bfcc4a31ba796aca88c5eae36870fc`; the selected AI Megpoid runtime-options
object contains this key with boolean value `true`. Therefore the supplied
bank enables the independent carrier-fusion flag at root `+0x3559`, and the
native setup enters the branch that constructs the optional `+0xD60` Dense
descriptor and carrier-history workspaces. Android now loads this flag from
the selected VTB2 options tree.
### Actual AI Megpoid carrier tensors

The two carrier Dense properties use the exact keys returned by
`FUN_1800e9580()` and `FUN_1800e9740()`:

| Role | Lookup key | VTB2 metadata | Payload |
|---|---|---|---:|
| Carrier weights | `61ef9b49f8fe353fe0d586220366ce92` | `[2,32,1,1]` | 64 × F32 |
| Carrier bias | `0128c19d610695f96c546cb8e04839fd` | `[2]` | 2 × F32 |

Both objects are under the selected residual configuration subtree at
`43726e4ce752ea6cc300dc2fca3e8372`. Each property is an object containing
one integer metadata array at `4ba18a32e4fa1a2f3b293713692a3a2e` and one
typed payload at `0ed3f4c15ca6d021d59b2b1038226a7f`. Android's
`S5MegpoidCarrierFusionTensorIndex` validates the metadata product against
the encoded payload length and retains the native dimensions verbatim.

The call sequence resolves the layout: `FUN_1800bbf10` constructs the
optional fallback carrier Dense descriptor with the shared input width and
two output features, then `FUN_1800d4610` receives the 64-element tensor
from `FUN_1800e9740()` as the matrix before the two-element tensor from
`FUN_1800e9580()` as the bias. The metadata `[2,32,1,1]` therefore gives
a row-major `[output=2][input=32]` fallback matrix; `[2]` is its bias.
Android validates these roles and exposes them through
`prepareCarrierFusionFallbackDenseProjection()`. The active branch instead
uses the dynamic source matrix at root `+0x15E` and the generic GEMM path.

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

The complex modulation accumulator has a common scalar equation now ported
as `S5MegpoidOutputHeadKernels.accumulateComplexModulatedOutput`; the
CPU-dispatched native AVX2 and general paths implement the same equation:

```text
x = gain * feature0
y = gain * feature1
out.real += modulationReal * x - modulationImag * y
out.imag += modulationImag * x + modulationReal * y
```

The affine vector operation used by the carrier path is also ported as
`applyAffineInPlace`, matching `value = value * scale + bias` from
`FUN_1800cea30` / `FUN_1800ceba0`. These are portable scalar reference
kernels, not the complete SIMD dispatch or per-part scheduler. The remaining
work is the interpolation in `FUN_1800c4080` / `FUN_1800c39f0`,
carrier-conditioned input construction, ring-buffer traversal and final
fusion scheduling. The supplied AI Megpoid model has carrier fusion enabled.

## Carrier workspace and native history mixing layout

The carrier-history buffer is not stored as bin-interleaved `[real,imag]`
pairs. The native capture loop in `FUN_1800c7ad0` copies two source values
per bin into separate halves of `D86`, then copies the entire flat buffer
into `D80`. The confirmed per-slot layout is therefore:

```text
D80 slot = [firstPlane[0..bins), secondPlane[0..bins)]
slotStride = 2 * bins
historySlots = root[0x181] + root[0x182] + 1
```

The write cursor is `root[0xD7E]`; after each frame, native increments it
and wraps at `root[0xD7C]`. When reading the carrier frame for fusion, native
uses:

```text
selectedSlot = (writeCursor - root[0x182] - root[0x181] - 1) mod historySlots
```

Because `historySlots = root[0x181] + root[0x182] + 1`, this selects the
oldest ring slot at the current write cursor. Android's
`S5MegpoidCarrierFusionWorkspace` mirrors the two-plane storage, write
cursor, oldest-slot order and reset behavior.

### Final carrier-history accumulation

For each spectrum bin, native reads carrier history as
`carrierFirst = slot[bin]` and `carrierSecond = slot[bins + bin]`, and the
carrier Dense output as two rows `denseFirst` and `denseSecond`. It
accumulates the complex product into the terminal interleaved output:

```text
out.real += denseSecond * carrierFirst - denseFirst * carrierSecond
out.imag += denseFirst * carrierFirst + denseSecond * carrierSecond
```

Android implements this in
`S5MegpoidCarrierFusionWorkspace.accumulateDelayedCarrierFrameInto()`.
Tests cover the planar ring layout, wraparound, delayed-slot selection,
complex multiplication, zero-initialized slots and reset. This does not yet
create the carrier-conditioned 32-feature Dense input or recover the
normalization vectors feeding the `+0xC4` descriptor.

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

This establishes two possible two-plane representations. Although
`FUN_1800bca40` initializes root byte `+0x48` to zero, the subsequent
`FUN_1800ced10` model-option loader overwrites it using the boolean property
returned by `FUN_1800e6c20()`. That getter resolves to key
`d22b2750ccebc1459f7a9d9ea7186bbf`, which is present with value `true` in
the selected AI Megpoid runtime-options object. The supplied bank therefore
uses log-magnitude/phase encoding. `S5MegpoidRuntimeOptionsDescriptor`
loads this key and `PreparedMegpoidRuntime` passes the selected encoding to
the spectral output session.

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

The active value of root `+0x48` is loaded by `FUN_1800ced10` from the
boolean key returned by `FUN_1800e6c20()`. The installed AI Megpoid VTB2
contains that key with value `true`, so its selected mode is
`LOG_MAGNITUDE_PHASE`. Android reads this setting from the same runtime
options object and passes it to `processModelOutputPlanes()`. The direct
complex mode remains available for other banks/configurations.

## 6. Remaining output-head work

1. Recover the hashed boolean option returned by `FUN_1800e6c20()` and identify its value in AI Megpoid VTB2; this controls root `+0x48` direct-complex versus log-magnitude/phase encoding.
2. Map the second global-normalization and dense descriptors at root `+0x310` and `+0x338` to VTB2 keys and active dimensions.
3. Trace `FUN_1800c3930`, `FUN_1800d52b0` and `FUN_1800d5610` for the per-part interpolation and carrier-mix branches.
4. Implement the model-backed 8-feature output head, its per-part modulation and final reduction to the two terminal feature planes.
5. Connect the completed upstream sequence pipeline to the second head and the carrier-history branch.
6. Compare complex spectra and overlap-add output against authorized native reference frames before enabling WAV rendering.

Until these are resolved and tested on Android ARM64, `SynthesisEngine.render()`
must remain unavailable and must not emit placeholder audio.
