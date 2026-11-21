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

## Exact boundaries that remain

- The app still does not derive timed per-phone events from VeaNote.phoneme. That field is editor display text, not already aligned phones.
- G2P output and its per-phone timing/alignment are not yet connected to the phone-event feature preparer.
- The mode-0 feature builder needs the active render descriptor fields. Category width must be at least 14, but actual width and context counts are runtime data and are not hard-coded.
- Alternate phone-feature layout mode +0x25 != 0 is not implemented.
- The complete root render-state descriptor, runtime-derived tensor dimensions, and active workspace bindings are not yet populated.
- Full neural output-domain execution, spectral-frame generation, overlap-add state, sample conversion, and the verified PCM/WAV contract remain incomplete.

Consequently, SynthesisEngine.render() does not emit placeholder WAV data and EngineStatus remains unavailable until the full path has been implemented and verified on Android ARM64.