# S5API tensor-reader findings

Date: 2026-11-19 (updated 2026-11-21)

## Numeric-array conversion

The recovered FUN_180006c80 conversion helper supports at least two input node types.

- For node tag 4, the input is a list of 16-byte elements. Each element has a type tag of 2 and an IEEE-754 64-bit value at offset +8. The helper allocates a contiguous float buffer and converts each double to float32.
- For node tag 3, the helper extracts a string and delegates to FUN_18000f5f0, then copies the resulting float vector into the destination. The exact string grammar is still under analysis.

This is direct evidence for a JSON-like typed object tree used by the layer loader. It is not evidence that the outer .vtb2 file itself is JSON.

## S5API container prefix and transform

The supplied V7 files begin with a 16-byte prefix: magic, a declared span, a format/version word, and a 32-bit transform seed. The S5API routine FUN_18002aa90 uses the seed to decode the body beginning at offset 16. For the five catalog model assets, the declared span is file_size - 136, so the decoder emits file_size - 144 bytes and leaves the final 128 bytes outside the transform range.

The portable implementation is now in `vea_s5_container.cpp` and exposed to Kotlin as `NativeS5TensorBridge.decodeContainerPayload()`. This decodes the outer byte transform only; it does not parse the resulting JSON-like model-object tree.

## Aligned arena and layer descriptors

- FUN_180006a80 advances a caller-owned arena by count * 4 bytes, rounded up to a 32-byte boundary, and returns the aligned region. The region is used for float32 values.
- FUN_180006b10 advances the same style of arena by count * 0x18 bytes, rounded up to a 32-byte boundary. The 0x18-byte records are layer/matrix descriptors.
- FUN_180064670 creates a descriptor table for param_6 matrices. Its shape fields include the logical param_5 dimension, param_4 dimension, and a padded dimension equal to align_up(param_5, 8). It allocates float storage per descriptor and copies source values into the padded representation.
- The matrix copy loops process four floats per iteration and handle the remaining tail separately. The exact semantic orientation of the two dimensions must be reconciled with the layer callers before naming them input/output channels.

This reveals a 32-byte aligned, 8-column-padded float32 matrix representation. It is a concrete Android porting target; it is not a flat row-major tensor.

## Weight and bias lookup keys

FUN_180020190 constructs the suffix .weight; FUN_1800214c0 constructs a .bias member. Both combine the incoming base name with a fixed binary suffix and call FUN_180010260. The current static trace identifies MD5 state initialization in the digest helper, but the exact byte encoding, digest-to-key formatting, and salt/concatenation order must be matched byte-for-byte before implementing key derivation in Android.

## Recurrent parameter naming and direction

FUN_18005ebe0 computes param_5 * 3 as a gate-width factor and builds the names .weight_ih_l... and .weight_hh_l... through FUN_180024a90. When the direction flag is set, FUN_180024a90 appends _reverse.

Three gates plus reverse-direction parameter names remain consistent with a bidirectional GRU-like layer. The exact gate ordering and equations are not yet fully verified. Bias key names and the recurrent forward function still require a focused call/data-flow pass.

## Model payload byte observations

The transformed/encoded V7 payloads have high global entropy and contain many candidate zlib/gzip signatures. The previous bounded scanner attempted full zlib/gzip decompression and found zero valid streams in the untransformed files. Those byte signatures are not evidence of standard zlib/gzip compression. This does not rule out proprietary compression or additional encoding in the decoded JSON-like model body.

## Porting consequence

The Android model loader should be split into four independently testable components:

1. Outer 16-byte prefix parser and seeded body transform.
2. JSON-like typed-object tree parser, including the MD5-based key derivation.
3. Numeric typed-array extraction and float32 matrix packing using the 32-byte arena and 8-column padded descriptor representation.
4. Recurrent/convolutional model graph construction and execution.

Only the outer body transform, typed numeric-array primitives, and matrix packing boundary have been partially recovered and ported here. The complete model-object parser, neural execution graph, and Megpoid V7 synthesis remain incomplete.
