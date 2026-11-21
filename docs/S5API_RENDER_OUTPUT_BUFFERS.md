# S5API render output-buffer boundary

Date: 2026-11-21

## Higher-level render chain

The supplied S5API static trace identifies the following call sequence:

Func_92864e29 -> FUN_180126c20 -> FUN_1800aec40 -> FUN_1800c7ad0

After the forward-dispatch call returns, the render routine invokes two
separate output accessors:

- FUN_180126e30, which forwards through FUN_1800aed70;
- FUN_180126d40, which forwards through FUN_1800aea60.

Both wrappers validate the engine context and output pointer, then copy a
float array whose element count is read from the engine object's field at
byte offset +0x0c.

## Observed output storage

The copy helpers read different engine-object offsets:

| Public wrapper | Copy helper | Source byte offset | Copy size |
|---|---|---:|---:|
| FUN_180126e30 | FUN_1800aed70 | +0xA0 | field +0x0C floats |
| FUN_180126d40 | FUN_1800aea60 | +0xB8 | field +0x0C floats |

A third wrapper, FUN_180127330, forwards to FUN_1800aeea0, which writes a
caller-supplied 32-bit value to engine-object offset +0x3678. This is a
cursor/state update and is separate from copying either float buffer.

## What this does and does not establish

The callsite proves that the higher-level renderer exposes two distinct
float buffers of the same declared element count. The current static evidence
does not prove that either buffer is already interleaved PCM, left/right audio,
or the final speaker waveform. Their signal-domain meaning and scaling need
to be established by tracing the producer writes to engine offsets +0xA0 and
+0xB8 and the consumers after the accessor calls.

VEA therefore must not label these arrays as PCM yet. The Android renderer
still needs the model forward graph, output-domain conversion, sample-rate
handling, channel layout, and a verified WAV export stage.

## Porting note

The two source offsets and shared count are now part of the analysis record.
A future Android callback bridge should preserve them as two named-but-neutral
float streams until the producer/consumer dataflow proves their semantics.
