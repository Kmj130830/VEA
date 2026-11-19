# Optional MIDI interchange note

This document describes an optional interoperability feature only. It is not the selected rendering workflow and does not satisfy VEA's standalone Android synthesis requirement.

VEA can export a Standard MIDI File containing:
- project tempo and time signature;
- track names and clip start-bar offsets;
- note pitch, start time, and duration;
- lyric text as MIDI Lyric meta events (0x05), encoded as UTF-8.

The MIDI file does not carry Yamaha's proprietary AI Megpoid Voice Type metadata, per-note pitch curves, or the original AI rendering state.

The project target remains native Android rendering. A licensed desktop VOCALOID7 installation may optionally be used as an external comparison/reference for differential tests, but the final app must not require a PC connection or a desktop render handoff.

Current native milestones:
- `libveag2p.so` builds for Android `arm64-v8a`.
- It currently implements only the recovered Korean input canonicalization step.
- It does not provide Yamaha phoneme conversion or singing synthesis.
- The app continues to report the VOCALOID backend as unavailable.

See:
- `docs/NATIVE_G2P_BRIDGE.md`
- `docs/G2P_STATIC_FINDINGS.md`
- `docs/VOCALOID7_ENGINE_STATIC_ANALYSIS.md`
