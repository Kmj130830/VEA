# VSM MIDI-to-audio boundary (static reconstruction)

## Scope and evidence

Target: the VSM.dll and S5API.dll modules extracted from the supplied VOCALOID7 Editor package.

Method: Ghidra 12.2 static decompilation of the VSM render path. The target DLLs were not executed. This document records the VSM-side boundary only; it does not claim that the Android renderer or the VSM-to-S5API ABI has been implemented.

## Confirmed VSM-side flow

1. The VSM MIDI-part render entry path reaches `FUN_18006eab0`.
2. `FUN_18006eab0` obtains a voice-engine object through indirect manager/interface calls, checks the requested voicebank path, then calls `FUN_18006f500` to prepare event batches for the selected tick/frame interval.
3. `FUN_18006f500` reads the MIDI track's event collection and uses RTTI checks to distinguish:
   - `Yamaha::VOCALOID::VSM::Note`
   - `Yamaha::VOCALOID::VSM::VibratoEvent`
   - `Yamaha::VOCALOID::VSM::MidiController`
4. Tick positions are converted to frame positions through `FUN_18005e9c0`. The caller rounds the results with `floor(value + 0.5)`; offsets stored in the chunk are relative to that chunk's starting frame.
5. The preparation loop advances the render window by `0x100` (256) samples at a time. The resulting per-chunk event list is passed to a virtual method at byte offset `0x78` on the voice-engine object.
6. In the 44.1 kHz branch, `FUN_18006eab0` configures the object with sample rate `0xac44` and chunk length `0x100`. It allocates a `0x200`-byte output buffer for the 256-sample chunk.
7. `FUN_18000f810` appends the returned samples to the VSM audio-buffer accumulator using `memcpy(..., sampleCount * 2)` and advances the destination position in two-byte sample units. This establishes a 16-bit-width VSM audio-buffer representation on this path. Signedness and exact quantization semantics are not established by this helper alone.

## Raw 24-byte staging-record observations

The temporary records constructed in `FUN_18006f500` occupy six 32-bit words (24 bytes) on the observed 64-bit build. The fifth word pair contains a pointer to an event-owned payload/member. These are records in the per-chunk event vector. Before the engine call, `FUN_180071440` is invoked with the vector bounds and a count calculated as byteLength / 24, indicating that it operates on 24-byte records. The caller later copies the vector's raw memory in eight-byte units (byteLength / 8) into a vector-like argument descriptor. This is consistent with the same 24-byte records being copied as three 64-bit words each; it is not evidence for a different eight-byte event format. The exact argument descriptor type and any semantic rewriting performed by `FUN_180071440` are not yet recovered. The values below are raw decompiler-visible fields; names such as `kind` or `controllerId` are deliberately not assigned until the helper bodies confirm their semantics.

| Event family | word 0 | word 1 | word 2 | word 3 | word 4-5 |
|---|---|---|---|---|---|
| Note | frame offset within chunk | `0x0f` | `2` | `1` | pointer to `Note + 0x08` |
| Vibrato | frame offset within chunk | `0x0f` | vibrato slot (0 or 1) | `2` | pointer to `VibratoEvent + 0x10` |
| MIDI controller | frame offset within chunk | controller-index loop value | `2` | `0` | pointer to `MidiController + 0x10` |

The note and vibrato descriptors are emitted after type checks against their corresponding VSM classes. Controller records are generated in the controller pass. The helper routines `FUN_18006c2d0`, `FUN_18006c6f0`, and `FUN_18006e670` participate in these conversions; their full decompilation is the subject of the follow-up analysis in PR #10.

## Voice-engine boundary that remains unresolved

The VSM caller obtains the object through indirect manager/interface calls and invokes several virtual methods, including offsets `+0x10`, `+0x30`, `+0x50`, `+0x58`, `+0x78`, `+0x80`, and `+0x88`. At `+0x78`, it supplies the per-block event descriptor and the output-buffer descriptor. Those byte offsets are observed on the VSM object, but their C/C++ signatures and ownership rules are not recovered. In particular, the meaning of the 24-byte event record fields and the type/ownership contract of the vector-like argument at `+0x78` are not recovered yet.

Separately, `FUN_18003b380` loads `S5API.dll` and `FUN_180086750` resolves 28 obfuscated exports with `GetProcAddress`, including `Func_92864e29`. The available static trace does not yet prove which VSM virtual method dispatches to which S5API export. In particular, do not equate VSM `+0x78` with `Func_92864e29` without a recovered vtable or thunk mapping.

## Android port implications

- Keep the imported AI Megpoid V7 model package and its prepared weights as the voicebank layer; do not treat successful bank parsing as a rendered singer.
- Represent MIDI notes, vibrato and controller events as separate typed source events. Do not assume the desktop 24-byte event records are a portable ABI: they contain process-local pointers, and the behavior of `FUN_180071440` plus the vector-like call argument still needs confirmation.
- Use an explicit sample-format boundary. The VSM 44.1 kHz path consumes 256-sample blocks with a two-byte sample stride; signedness, scaling and conversion from S5API's internal float buffers must be resolved before writing WAV output.
- The exact VSM interface ABI, event payload layouts, S5API export mapping, upstream F0/voicing/features and final PCM conversion are still outstanding. The current Android `SynthesisEngine.render()` remains unavailable.

## Next evidence needed

1. Decompile the three event conversion helpers and `FUN_180071440` to confirm how the 24-byte records are ordered, normalized and consumed, and to recover the call-argument ownership contract.
2. Recover the VSM voice-engine interface vtable/thunks and map its methods to the 28 S5API exports.
3. Trace the first S5API input state populated from note/phoneme/pitch/controller events, then confirm the final sample format and scaling.
