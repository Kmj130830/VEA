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

The note and vibrato descriptors are emitted after type checks against their corresponding VSM classes. Controller records are generated in the controller pass. The helper bodies have now been inspected:

- `FUN_18006c6f0` receives a `VSM::Note` object. It reads the note's lyric string at object offset `+0xA8` (following the long-string pointer when the string is not stored inline), splits the text on ASCII spaces into ordered tokens, and builds a command byte stream using the note and its owning sequence/part context. The command stream includes token bytes, note-associated scalar/byte values and additional control fields. This confirms that the VSM path consumes lyric text at note scope; it does **not** establish that the command bytes are phoneme IDs or reveal the full command grammar.
- `FUN_18006e670` is called for `VSM::VibratoEvent`. Its selector accepts only values 0 and 1, emitting the corresponding command marker byte `'e'` (0x65) or `'d'` (0x64), and includes a byte read from the vibrato object at `+0x3C`. The semantic names of the selector and payload byte are not proven.
- `FUN_18006c2d0` builds the controller/parameter command stream. Its first selector maps 1, 2 and 4 to the marker bytes `'c'`, `'g'` and `'T'`; other values use `'U'`. A second selector has observed cases 6–14 that emit marker bytes `'1'`, `'2'`, `'3'`, `'4'`, `'7'`, `'8'`, `'9'`, `'p'` and `'6'`. These are raw command markers, not verified controller names or phoneme symbols.

The helpers therefore establish a lyric-and-expression serialization layer upstream of the voice-engine call, but their output grammar and the receiving parser are still unresolved.

### Record ordering

`FUN_180071440` sorts the 24-byte records as six 32-bit words. The small-array insertion-sort path and the large-array comparison helpers use the same observed ordering key:

1. word 0, ascending;
2. word 3, ascending;
3. only when word 3 is zero, word 1, ascending.

When word 0 and nonzero word 3 are equal, the comparison does not use word 1. The remaining words are moved with the record but do not participate in this ordering decision. This is a comparator observation, not a semantic definition of those fields.

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


## Note lyric command construction details — 2026-11-21

The full body of `FUN_18006c6f0` confirms that the ASCII-space tokenizer
feeds a larger note-scoped command serializer, not a direct phoneme-ID list.
The following are direct static observations:

- The helper reads the note lyric at object offset `+0xA8`, using the
  pointer at `+0xA8` when the capacity field at `+0xC0` exceeds the
  inline-string threshold.
- It splits on byte `0x20` (ASCII space), stores the non-empty tokens in
  source order, and retains each token's character sequence.
- It resolves the owning VSM sequence and MIDI part from the note's
  object references, then calculates a note-relative time value through
  `FUN_18005e9c0` using the part/sequence offsets and note offsets
  `+0x2C` and `+0x30`.
- When the calculated millisecond value is within the observed 14-bit
  limit (`<= 16383`), the command stream includes its high and low
  seven-bit components. This is a time field, not a phoneme code.
- The stream also reads note-owned bytes/scalars at offsets including
  `+0x34`, `+0x38`, `+0x3C`, `+0x40`, `+0x44`, `+0x48`, `+0x4C`,
  `+0x50` and `+0x51`; several integer values are clamped before being
  serialized. Their vendor parameter names are not established here.
- Token characters are serialized as ordered character entries with
  incrementing per-character selectors. The loop is bounded; the observed
  selector range begins at `0x13` and stops after `0x4D`.

The command stream therefore interleaves note timing, note parameters,
token-character data and expression controls. The marker bytes such as
`0x62`, `0x06`, `0x26`, and `0x63` are command-format bytes in this
serializer. They are not evidence of direct Yamaha phoneme IDs.

Android's `VsmLyricTokenizer` remains a deliberately narrow port of the
confirmed lexical split. The application preserves the original lyric,
phoneme string and scheduled note metadata independently; it does not yet
emit this private command stream. That requires mapping each referenced
VSM note/part property to a stable VEA project field and recovering the
receiving parser before serialization can be implemented.