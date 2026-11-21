# Android VSM input scheduler — step 7

## Scope

This step adds an app-owned event timeline from an existing VEA vocal track. It is deliberately not a serialization of Yamaha's private VSM event ABI.

The source of truth is VEA's current project model:

- Project tempo is constant BPM.
- The editor uses 480 ticks per quarter note.
- Clips have a one-based start bar.
- Notes contain start tick, duration, MIDI pitch, lyric and phoneme text.

## Recovered desktop boundary

The supplied desktop VSM static trace establishes the following for the 44.1 kHz render path:

- The render loop advances in blocks of 256 samples.
- Note, vibrato and MIDI-controller source objects are distinguished before the voice-engine call.
- Event frame positions are rounded with `floor(position + 0.5)`.
- Note and vibrato events are staged as 24-byte records containing process-local pointers to source objects.
- The 24-byte array is sorted before each block is dispatched.
- The VSM callback's 44.1 kHz output buffer is allocated as 512 bytes for 256 samples, but exact signedness and scale are not established by that observation alone.

Evidence: static decompilation of `FUN_18006eab0`, `FUN_18006f500`, `FUN_180071440`, `FUN_18006c6f0` and `FUN_18006e670` in the renderer-core artifact.

## Android implementation in this step

`VsmMidiInputScheduler` converts each note to an app-owned `VsmScheduledNote` and places the event in the 256-sample chunk containing its onset.

For each note it computes:

```text
clipStartTick = (clip.startBar - 1) * ticksPerBar
noteStartTick = clipStartTick + note.startTick
noteEndTick   = noteStartTick + note.lengthTick

sample(tick) = floor(
    tick * sampleRate * 60 / (BPM * ticksPerQuarter) + 0.5
)

durationSamples = sample(noteEndTick) - sample(noteStartTick)
chunkIndex      = sample(noteStartTick) / 256
frameOffset     = sample(noteStartTick) % 256
```

The schedule includes consecutive blocks through the last note end. Each block has a fixed render size (256 samples) and a separate valid-sample count so the final partial block can be zero-padded without losing the true project length.

Events at the same sample retain their source order. That is an explicit VEA-side deterministic rule, not a claim that it matches the desktop VSM comparator.

## Deliberate boundaries

This step does not:

- Recreate the desktop 24-byte record or copy pointer values.
- Claim the event helper's packed command bytes are phoneme IDs.
- Implement VSM's private virtual interface or call S5API.
- Generate vibrato/controller events; VEA's current project model does not yet store those event types.
- Convert lyric text to Yamaha phoneme codes.
- Interpret pitch points as a vendor pitch/expression stream.
- Render audio or claim PCM/WAV synthesis.

The next dependency is to recover the note payload fields and the custom lyric/phoneme command format sufficiently to define a portable phoneme request. Only after that should the Android G2P result be connected to the sequence-feature builder.

## VSM lyric-token preparation — step 8

The focused Ghidra report for the supplied VSM.dll confirms that
FUN_18006c6f0 reads the note lyric string from the VSM Note object at
+0xA8. The string uses the object's small-string storage convention
(inline for short strings, with a separate pointer when the capacity field
at +0xC0 exceeds the inline threshold). The helper scans the lyric text
in order and ends a token only on the ASCII space character (0x20).
Empty runs caused by leading, trailing, or repeated ASCII spaces are skipped.
Other characters, including tabs and non-ASCII text, remain part of a token.

Android now mirrors only this proven lexical boundary in
VsmLyricTokenizer. Every VsmScheduledNote exposes lyricTokens,
computed from its unchanged source lyric. This keeps the editor's original
lyric and phoneme text intact while making the observed token sequence
available to a later phoneme/command builder.

This is NOT the complete VSM note payload. The same native helper emits
additional note/sequence/part-dependent command bytes and consults
voice/database and expression objects. Their complete command grammar and
the receiver-side decoding are not yet established. In particular, the
observed command marker bytes must not be treated as phoneme IDs. The Android
implementation does not create the private pointer-bearing 24-byte records
and does not yet call the VSM/S5API render boundary.

## Portable note interval view

`VsmScheduledNote.interval` now exposes the scheduled sample range as
`VsmEventInterval(startSeconds, endSeconds)`. Both endpoints are derived
from the already-rounded sample positions and the active render sample rate;
they are not recomputed from ticks or BPM. At 44.1 kHz, for example, a note
at sample 88,200 with 22,050 samples of duration maps to `[2.0, 2.5]` seconds.

This is the app-owned temporal pair needed by the next feature-input stage.
It is intentionally not treated as a complete S5API 0x38-byte event row:
the five categorical key values following the interval are not yet mapped.

## Editor phoneme text versus renderer phone events

`VeaNote.phoneme` is currently an editor/display string generated by
`XsampaMapper`; it is not a sequence of already timed S5API phone events.
The mapper can return multiple phone-like tokens in one string (for example,
`k a`) and can use `.` as a syllable separator in English output. Its own
source comment explicitly describes it as a fallback rather than Yamaha
G2PA output.

The recovered S5API path expects one owned phone-label string per 0x38-byte
event row, together with that phone segment's start and end times. The
0x38 row has an MSVC string object at +0x10, not a five-value category key.
`FUN_180074a50` classifies that individual label into an internal category.

Therefore VEA must not pass the entire editor `phoneme` string to the
renderer as one phone label, nor split it and assign equal durations without
evidence. A correct bridge needs the language phonemizer's ordered phone
sequence and per-phone timing/alignment first. The current scheduler
preserves the original editor phoneme string and note interval unchanged.