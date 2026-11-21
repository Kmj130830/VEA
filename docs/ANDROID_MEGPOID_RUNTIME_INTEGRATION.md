# Android Megpoid synthesis-runtime integration

This milestone connects previously separate model and scheduling work without
claiming that the app can render a complete singing waveform yet.

## Integrated preparation path

1. The installed voicebank package is read from the five private model files:
   AI_Megpoid.vtb2, AI_Megpoid.vpit, COMMON7.vpit,
   VOCALOID7.vhnv, and VOCALOID7.vtmg.
2. Each file is checked against its S5 container envelope, decoded and parsed
   into a bounded JSON object tree. The five SHA-256 values are checked again
   against the Megpoid V7 package catalog before a model package is returned.
3. The shared VTMG model is indexed as nine named layers. Its weight and bias
   tensors are decoded and packed using the existing ARM64 S5 tensor bridge.
4. The Megpoid VTB2 model is indexed and compiled into its eight residual
   blocks. The sequence transition implementation preserves the recovered
   Dense/normalization -> Temporal[0..2] -> Residual[3] -> Banked[0] /
   Residual[2] -> Banked[1] / Residual[1] -> Banked[2] / Residual[0] order.
5. Project notes are converted to a portable 44.1 kHz / 256-sample event
   schedule. This is an app-owned event representation, not the desktop VSM
   private event ABI and not phoneme feature generation.

The model-package registry is generic. AI Megpoid V7 is the first registered
provider; other voicebanks can be added through a separate package loader
without changing the editor's generic synthesis interface.

## Explicitly incomplete render stages

The current preparation path does not yet create the complete feature tensor
consumed by the neural sequence pipeline. The note/phoneme event-to-feature
conditioning path, all root render-state descriptor population and their
runtime-derived tensor dimensions remain incomplete. The final frame/spectral
path, overlap-add state, sample conversion and verified PCM/WAV contract also
remain incomplete.

Consequently, SynthesisEngine.render() deliberately reports the missing
stages and does not write a placeholder or fabricated WAV. EngineStatus must
remain unavailable until the full output path has been implemented and
verified on Android ARM64.
