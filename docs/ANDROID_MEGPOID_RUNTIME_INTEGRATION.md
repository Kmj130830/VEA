# Android Megpoid synthesis-runtime integration

This milestone connects model loading, note scheduling, and the recovered phone-feature input path. It does not claim a complete singing waveform.

## Integrated preparation path

1. The installed voicebank package is read from five private model files: AI_Megpoid.vtb2, AI_Megpoid.vpit, COMMON7.vpit, VOCALOID7.vhnv, and VOCALOID7.vtmg.
2. Each file is checked against its S5 container envelope, decoded, parsed into a bounded object tree, and checked against the Megpoid V7 package catalog hashes.
3. The shared VTMG model is indexed as nine named layers. Its weight and bias tensors are decoded and packed using the Android ARM64 S5 tensor bridge.
4. The Megpoid VTB2 model is indexed and compiled into eight residual blocks. The recovered sequence path preserves the verified Dense/normalization, Temporal, Residual and Banked operation order.
5. Project notes are converted to a portable 44.1 kHz / 256-sample event schedule. Each scheduled note exposes the recovered ASCII-space lyric tokenization.
6. Already aligned phone events pass through break/silence normalization, exact hyphen-row removal, one-millisecond minimum-duration filtering, alias canonicalization and the native log-duration scalar encoder.
7. The static 14-category phone map supplies indices 0–13. The mode-0 feature matrix builder writes duration, the optional leading-bang flag pair, centre category, preceding categories and following categories as row-major float32 values.
8. The matrix can be transposed to the feature-major layout required by the existing Dense/Temporal/Banked/Residual sequence pipeline using runPhoneFeatureMatrix().

The model-package registry is generic. AI Megpoid V7 is the first registered provider; additional voicebanks can add their own loader without changing the editor's generic synthesis interface.

## Note-to-phone alignment added

The Android path now has an explicit, deterministic adapter from each scheduled note's
existing `phoneme` field to timed `VsmPhoneEvent` objects:

- Whitespace separates phone labels. Standalone `.` and `|` tokens are treated as
  syllable/word separators; composite labels such as `y|CH` remain intact.
- Each note's sample duration is divided evenly across its explicit phone labels.
  Remainder samples are assigned to the earliest labels, and each label receives at
  least one sample.
- Gaps before, between and after notes are represented as `sil` events.
- Event boundaries are derived from integer sample positions and converted to seconds
  only at the `VsmPhoneEvent` boundary.
- The 14-category phone map is checked and unsupported labels routed to category 13
  are reported in `fallbackPhoneLabels` for review.

`PreparedMegpoidRuntime.prepareExplicitPhoneInputForDense()` now composes this alignment
with the existing event preprocessor, mode-0 feature matrix builder, dimension checks
and model-backed Dense/normalization stage.

This is a temporary deterministic alignment policy for **already supplied phone
labels**. It is not Yamaha G2P, does not infer pronunciation from lyrics, and does
not reproduce measured per-phoneme durations. The caller must still supply the active
phone-feature layout and minimum/maximum duration values from a verified render
descriptor. A note with an empty phoneme field is rejected rather than silently
inventing a pronunciation.

## Dense input dimensions derived from the model

The Dense input width no longer needs to be supplied as an assumed value.
The selected VTB2 Dense tensor is consumed as a row-major
`[outputFeature][inputFeature]` matrix, while the normalization scale and
bias vectors establish the exact output-feature count. Android now derives:

`outputFeatures = normalizationScale.elementCount`

`inputFeatures = denseWeight.elementCount / outputFeatures`

The derivation rejects empty dimensions, scale/bias width disagreement, and
a weight count that is not an exact multiple of the normalization width.
`PreparedMegpoidRuntime.requiredPhoneFeatureWidth` exposes the resulting
model-required input width. Before Dense inference, the generated phone
matrix must match it exactly; the model-backed Dense/normalization stage is
then built from those same model-derived dimensions.

This proves the Dense tensor's matrix dimensions from its payload count and
the normalization vectors. It does **not** recover the missing phone-layout
descriptor values: category stride, preceding/following context counts,
leading-flag mode, and duration limits still require the active render-context
data. A mismatch is rejected instead of selecting a plausible layout by guess.

## Mode-0 phone descriptor representation

The native feature builder call chain now has a complete field-offset map.
`FUN_18003ab70` forwards the active render-context descriptor fields at
`+0x10`, `+0x14`, `+0x18`, `+0x1C`, `+0x20`, `+0x24`, `+0x25`
and `+0x26` into `FUN_18006c720`. Android represents these as one
`S5ApiDefaultPhoneFeatureDescriptor`, validates the category capacity,
duration bounds and context counts, and converts it to the mode-0 matrix
layout only when the mode byte is zero.

The phone-category IDs are also now grounded in the constructor path:
`FUN_180003a90` builds the 14 ordered groups and `FUN_18006c520` assigns
each group's ordinal as its numeric phone-map index. This confirms IDs 0–13
and still keeps the feature stride separate from the number of categories.

The new descriptor overloads are wired into
`PreparedMegpoidRuntime.preparePhoneInputForDense()` and
`prepareExplicitPhoneInputForDense()`. The unresolved task is no longer
the matrix field layout itself; it is obtaining the **actual descriptor
values** from the active voice/render context and proving them against a
real reference render.

## Terminal feature-pair to complex-spectrum conversion

The trailing section of `FUN_1800c7ad0` now has a focused static trace.
After the post-residual output head, S5API reads two contiguous float32
feature planes, each with the one-sided bin count, and converts them into
interleaved complex bins. Two encodings are present:

- Direct complex: plane 0 → real, plane 1 → imaginary.
- Log magnitude / phase: `magnitude = min(exp(plane0), 100)`,
  `real = magnitude * cos(plane1)`, `imag = magnitude * sin(plane1)`.

VEA adds `S5MegpoidSpectrumFrameEncoder` for both exact terminal transforms.
The native getter `FUN_1800e6c20()` resolves to key
`d22b2750ccebc1459f7a9d9ea7186bbf`, and the selected AI Megpoid runtime-
options object contains boolean `true`; therefore this bank uses
`LOG_MAGNITUDE_PHASE`. `S5MegpoidRuntimeOptionsDescriptor` reads that value,
and `PreparedMegpoidRuntime.processModelOutputPlanes()` passes the selected
encoding to the output session before the inverse-FFT/overlap-add stage.

This is only the terminal representation conversion. The additional
post-residual Dense/global-normalization head, the optional multi-part
feature branch, carrier-mix history and their model descriptors remain
unconnected. The full output graph is therefore still unavailable.

## Model-confirmed carrier and terminal spectrum options

The selected AI Megpoid VTB2 runtime-options object contains both output
flags used by the native render-state initializer:

- `d22b2750ccebc1459f7a9d9ea7186bbf` = `true`: terminal log-magnitude /
  phase encoding at render-root byte `+0x48`.
- `66bfcc4a31ba796aca88c5eae36870fc` = `true`: carrier fusion at
  render-root byte `+0x3559`.

These are independent settings. The four-part head configuration
(`D00=4`, `D01=true`) separately derives an eight-feature output width.
The Android runtime options descriptor now exposes all three values and
maps the terminal flag to `S5MegpoidSpectrumFrameEncoding`; the prepared
runtime passes this encoding automatically to the spectral output session.

When carrier fusion is enabled, the selected VTB2 configuration contains
these optional carrier-Dense tensor records:

| Role | Metadata | Typed payload count |
|---|---|---:|
| Weights | `[2,32,1,1]` | 64 × F32 |
| Bias | `[2]` | 2 × F32 |

The native call order now establishes the roles: `FUN_1800bbf10`
constructs an input-width-by-two-output Dense descriptor;
`FUN_1800d4610` receives the 64-value `[2,32,1,1]` matrix first and the
2-value bias second. The index validates this shape and builds the
model-backed `2 × 32` Dense projection. Carrier-conditioned input creation,
history scheduling and the per-part output mix remain under implementation.

## Carrier workspace and planar history ring

The optional carrier path now has a dedicated Android workspace. Its native
buffer dimensions are read from active render context values:

- `D6C = inputFeatures × carrierPositions` (Dense input)
- `D74 = 2 × carrierPositions` (two-row carrier-conditioned projection output)
- `D80 = historySlots × 2 × spectrumBins` (carrier frame ring)
- `D86 = 2 × spectrumBins` (current carrier frame)
- `historySlots = root[0x181] + root[0x182] + 1`

The native history frame is planar, not bin-interleaved:
`[firstPlane[0..bins), secondPlane[0..bins)]`. Android's
`appendCarrierFramePlanes()` and `copyHistoryOldestToNewest()` preserve
that layout and the native write cursor. The new
`accumulateDelayedCarrierFrameInto()` also ports the final carrier-history
complex multiply using the oldest slot selected by the native lag formula.

The active workspace is created from render geometry and the model-derived
input width, not from the fallback Dense weights. Its
`projectCarrierConditionedFeatures()` takes the runtime `[2][32]` source
matrix from root `+0x15E`, the current `[32][positions]` feature tensor,
and the two-value control vectors at `+0x162/+0x166`. It performs the
native 2×32 GEMM and per-row affine correction using the `+0x16A/+0x16B`
scalars. The optional VTB2 `+0xD60` 32→2 Dense layer remains separate and
is exposed only for the native fallback sub-branch.

The source matrix and control vectors are now compiled directly from the
selected VTB2 model data. The `FUN_1800ced10` builder reads the same
`[2,32,1,1]` carrier Dense weights and `[2]` bias that populate
the optional `+0xD60` fallback descriptor, plus the 32-value
normalization scale and bias from the post-residual fallback normalizer.
The recovered fold is:

```text
sourceMatrix[r,k] = carrierWeight[r,k] * normalizationScale[k]
control162[r] = carrierBias[r] + sum_k(carrierWeight[r,k] * normalizationBias[k])
control166[r] = sum_k(sourceMatrix[r,k])
```

`S5MegpoidCarrierFusionActiveParameters` validates all four model vectors,
builds the [2][32] source matrix and both two-value controls, and exposes
copy-isolated arrays. `S5VoicebankModelPackage.prepareCarrierFusionActiveParameters()`
and `PreparedMegpoidRuntime.prepareCarrierFusionActiveParameters()` connect
that compiler to the installed voicebank. The workspace now has a model-backed
overload so callers no longer need to inject guessed source/control arrays.

This advances the parameter-construction boundary only. Runtime values at
root +0x16A/+0x16B, carrier positions and history lag counts still come from
the active render context; the PCM/audio callback and final render session
remain unconnected.

## Non-multi-part fallback output head

The non-multi-part fallback descriptors are now independently indexed from
the selected VTB2 subtree. Native population confirms a 32-channel global
normalizer followed by a biased Dense projection from 32 features to 8.
The normalization tensors contain 32 scale and 32 bias values; the Dense
weights use metadata `[8,32,1,1]` and the bias has 8 values. The epsilon
is the native float32 constant `1.0e-6`.

`S5MegpoidPostResidualFallbackTensorIndex` validates the four exact keys
and metadata, and `S5MegpoidPostResidualFallbackStage.forward()` executes
normalize → Dense, matching the fallback branch's call order. This is
available only when the native options select the fallback branch. The
installed AI Megpoid V7 has `D00=4`, `D01=true`, so its main output path
remains the multi-part head; this fallback stage is present in the model
package but is not used for that configuration.

## Confirmed Alternate branch and sequence integration — 2026-11-25

The S5API initializer reads optional VTB2 key
`119558af50f9c8919539abd8e064b287` and writes its Boolean value to render
root `+0x59`. An offline scan of the exact AI Megpoid VTB2 selected options
object found this value to be `true`; absence defaults to false, matching
the native initializer. AI Megpoid therefore selects Alternate
`FUN_1800c72d0`, not standard Banked `FUN_1800c6390`.

Android now reads that value as
`S5MegpoidRuntimeOptionsDescriptor.alternateTransitionEnabled` and exposes
the corresponding `reverseTransitionMode`. The Alternate operator
`S5MegpoidAlternateTemporalProjection` implements 2x linear interpolation,
zero-padded left/centre/right context windows, row-major GEMM and bias
broadcast. The dedicated
`S5MegpoidAlternateSequenceTransitionPipeline` executes Temporal[0..2],
Residual row 3, then Alternate[0]/Residual row 2,
Alternate[1]/Residual row 1, and Alternate[2]/Residual row 0. Each
Alternate transition doubles its sequence positions.

`S5VoicebankModelPackage.createMegpoidV7AlternateSequenceTransitionPipeline()`
assembles the checked Dense/normalization stage, three model-backed Temporal
stages, three model-backed Alternate stages and the eight residual blocks.
The sequence test checks the minimum 49 phone-position input, residual row
order, and 56 output positions after three 2x transitions.

The remaining neural-operator check is the raw VTB2 Alternate weight-axis
mapping against the native `[M][3*C]` GEMM row order. The phone-event
upstream still lacks G2P, real per-phone timing, and the actual phone feature
descriptor values. The post-residual multi-part output and final spectrum to
PCM/audio callback are also incomplete. No successful vocal render or WAV
output is claimed.

## Exact boundaries that remain

- The app still does not derive timed per-phone events from VeaNote.phoneme. That field is editor display text, not already aligned phones.
- G2P output and its per-phone timing/alignment are not yet connected to the phone-event feature preparer.
- The mode-0 feature builder needs the active render descriptor fields. Category width must be at least 14, but actual width and context counts are runtime data and are not hard-coded.
- Alternate phone-feature layout mode +0x25 != 0 is not implemented.
- The complete root render-state descriptor, runtime-derived tensor dimensions, and active workspace bindings are not yet populated.
- The second post-residual Dense/global-normalization head and full multi-part/carrier processing remain unresolved. The active spectrum-encoding and carrier-fusion options are read from VTB2. The optional fallback carrier Dense tensor objects and their 2×32 descriptor are indexed separately from the active runtime-generated source matrix. Carrier source/control preparation, four-part interpolation scheduling and the verified PCM/WAV contract remain incomplete.

Consequently, SynthesisEngine.render() does not emit placeholder WAV data and EngineStatus remains unavailable until the full path has been implemented and verified on Android ARM64.

## Actual Megpoid sequence-transition tensors and branch selection

The selected AI Megpoid V7 VTB2 subtree contains three per-sequence
Alternate transition weight/bias pairs at indices 0..2. Each weight is
3,072 F32 with native metadata `[32,32,3,1]`; each bias has 32 F32
values with metadata `[32]`. The same three indices are populated for
Temporal weights/biases. The standard Banked-linear weight and bias
factories are absent from this selected Megpoid model subtree.

The native dispatcher does not decide by tensor presence. It checks the
render-context byte at `+0x59`: zero invokes standard
`FUN_1800c6390`; nonzero invokes Alternate `FUN_1800c72d0`.
The active value of that byte still needs to be traced, so the Android
runtime must not assume the branch from the model inventory alone.

The sequence tensor index now retains and validates every native shape
metadata array instead of storing only the flattened element count. The
three Alternate matrices and biases can therefore be selected by their
exact four-axis shapes, while the standard Banked adapter remains separate.
The Ghidra model-runtime pass explicitly decompiles both transition
operators next; the Alternate operator is not executed in Android until
its matrix layout, temporal addressing, padding and output shape are
confirmed.
