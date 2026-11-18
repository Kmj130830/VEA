# Engine and compatibility boundary

## Target

VEA's target is offline Android editing and, eventually, local vocal rendering with VOCALOID AI voicebanks. The first integration target is the VOCALOID7 AI generation; VOCALOID6 AI compatibility is also a design target. The data model already retains note timing, lyric, phoneme text, and per-note pitch points so an engine adapter can receive them later.

## Verified platform facts (2026-11-18)

Yamaha announced the VOCALOID7 Editor release for 2026-11-11. Its published specification lists Windows and macOS operation modes and desktop system requirements; it does not list Android. Yamaha also states that VOCALOID3 through VOCALOID6 voicebanks are supported in VOCALOID7 Editor. VOCALOID7's published feature notes describe second-generation VOCALOID:AI, an ATTACK parameter, VOICE TYPE for V7 voicebanks, and preservation/locking of synthesized pitch curves.

Official references:
- https://www.vocaloid.com/en/news/news_51/
- https://www.vocaloid.com/en/news/news_49/

## What the current app does

- Stores notes as 480 ticks per quarter note.
- Stores lyrics and a phoneme text field per note.
- Stores a list of per-note pitch points as relative tick and cents offsets.
- Exposes a SynthesisEngine contract and an explicit unavailable VOCALOID AI adapter.

## What it does not do

- It does not synthesize VOCALOID audio yet.
- It does not parse DDB, VDI, or other proprietary voicebank payloads.
- It does not run the Windows/macOS VOCALOID Editor inside Android.
- It does not bypass activation, encryption, licensing, or access controls.
- It does not claim that a desktop voicebank folder is sufficient to synthesize on Android.

## Required path to real V6/V7 AI rendering

1. Obtain documentation and a runtime/SDK that is licensed for Android embedding, or obtain another legally authorized integration route from the relevant rights holder.
2. Confirm the permitted voicebank installation, authentication, model-loading, and offline-rendering workflow.
3. Implement an adapter that maps VEA's note, phoneme, expression, and pitch data to the authorized engine API.
4. Validate rendered pitch curves and timing against reference renders.
5. Add audio output, cancellation, progress reporting, memory budgeting, and per-device performance tests.

Until those prerequisites exist, the engine status is intentionally reported as unavailable rather than returning silence or placeholder WAV data as if synthesis had succeeded.
