# Android Megpoid synthesis-runtime integration

This milestone connects model loading, note scheduling, and the verified portion
of phone-event preprocessing. It does not claim a complete singing waveform.

## Integrated preparation path

1. The installed voicebank package is read from five private model files:
   AI_Megpoid.vtb2, AI_Megpoid.vpit, COMMON7.vpit,
   VOCALOID7.vhnv, and VOCALOID7.vtmg.
2. Each file is checked against its S5 container envelope, decoded and parsed
   into a bounded object tree. The five SHA-256 values are checked again against
   the Megpoid V7 package catalog before a model package is returned.
3. The shared VTMG model is indexed as nine named layers. Its weight and bias
   tensors are decoded and packed using the Android ARM64 S5 tensor bridge.
4. The Megpoid VTB2 model is indexed and compiled into eight residual blocks.
   The recovered sequence path preserves Dense/normalization, Temporal,
   Residual and Banked operations in their verified order.
5. Project notes are converted to a portable 44.1 kHz / 256-sample event
   schedule. Each scheduled note now also exposes the observed ASCII-space-only
   lyric tokenization boundary and an interval based on rounded sample positions.
6. Already aligned phone events can pass through the verified preprocessing
   stage: break/silence normalization, exact hyphen-row removal, one-millisecond
   minimum-duration filtering, known alias canonicalization, duration encoding,
   and the exact fixed category exceptions.

The model-package registry is generic. AI Megpoid V7 is the first registered
provider; additional voicebanks can add a package loader without changing the
editor's generic synthesis interface.

## Exact boundaries that remain

- VeaNote.phoneme is editor display text, not already timed per-phone data.
  The app does not split it into phone events or invent equal phone durations.
- G2P output and its per-phone timing/alignment are not yet connected to the
  feature preparer.
- Only the fixed SIL/Sil/Br/BR category exceptions are known. Ordinary phone
  category IDs still require the active render-context lookup table.
- The default phone-feature row layout is partially recovered, but the full
  context-window category mapping and complete feature-row materialization
  remain incomplete.
- The complete root render-state descriptor and runtime-derived dimensions
  are not yet populated.
- The full neural output-domain execution, spectral-frame generation,
  overlap-add state, sample conversion, and verified PCM/WAV contract remain
  incomplete.

Accordingly, SynthesisEngine.render() does not emit placeholder WAV data and
EngineStatus remains unavailable until the full path has been implemented and
verified on Android ARM64.
