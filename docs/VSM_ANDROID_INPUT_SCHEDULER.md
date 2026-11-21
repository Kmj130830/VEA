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