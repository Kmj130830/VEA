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

## Exact boundaries that remain

- The app still does not derive timed per-phone events from VeaNote.phoneme. That field is editor display text, not already aligned phones.
- G2P output and its per-phone timing/alignment are not yet connected to the phone-event feature preparer.
- The mode-0 feature builder needs the active render descriptor fields. Category width must be at least 14, but actual width and context counts are runtime data and are not hard-coded.
- Alternate phone-feature layout mode +0x25 != 0 is not implemented.
- The complete root render-state descriptor, runtime-derived tensor dimensions, and active workspace bindings are not yet populated.
- Full neural output-domain execution, spectral-frame generation, overlap-add state, sample conversion, and the verified PCM/WAV contract remain incomplete.

Consequently, SynthesisEngine.render() does not emit placeholder WAV data and EngineStatus remains unavailable until the full path has been implemented and verified on Android ARM64.