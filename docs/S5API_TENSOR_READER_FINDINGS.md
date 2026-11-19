# S5API tensor-reader findings

Date: 2026-11-19

## Numeric-array conversion

The recovered FUN_180006c80 conversion helper supports at least two input node types.

- For node tag 4, the input is a list of 16-byte elements. Each element has a type tag of 2 and an IEEE-754 64-bit value at offset +8. The helper allocates a contiguous float buffer and converts each double to float32.
- For node tag 3, the helper extracts a string and delegates to FUN_18000f5f0, then copies the resulting float vector into the destination. The exact string grammar is still under analysis.

This is direct evidence for a JSON-like typed object tree used by the layer loader. It is not evidence that the outer .vtb2 file itself is JSON.

## Aligned arena and layer descriptors

- FUN_180006a80 advances a caller-owned arena by count * 4 bytes, rounded up to a 32-byte boundary, and returns the aligned region. The region is used for float32 values.
- FUN_180006b10 advances the same style of arena by count * 0x18 bytes, rounded up to a 32-byte boundary. The 0x18-byte records are layer/matrix descriptors.
- FUN_180064670 creates a descriptor table for param_6 matrices. Its shape fields include the logical param_5 dimension, param_4 dimension, and a padded dimension equal to align_up(param_5, 8). It allocates float storage per descriptor and copies source values into the padded representation.
- The matrix copy loops process four floats per iteration and handle the remaining tail separately. The exact semantic orientation of the two dimensions must be reconciled with the layer callers before naming them input/output channels.

This reveals a 32-byte aligned, 8-column-padded float32 matrix representation. It is a concrete Android porting target; it is not a flat row-major tensor.

## Weight and bias lookup keys

FUN_180020190 constructs the suffix .weight; FUN_1800214c0 constructs .bias. Both combine the incoming base name with a fixed binary suffix and call FUN_180010260.

FUN_180010260 initializes the standard MD5 state words (67452301, efcdab89, 98badcfe, 10325476) and delegates to the lower-level block update/finalization helpers. The observed lookup key path is therefore MD5-based. The exact byte encoding and whether the suffix is used as a salt must be matched byte-for-byte before implementing the key derivation in Android.

## Recurrent parameter naming and direction

FUN_18005ebe0 computes param_5 * 3 as a gate-width factor and builds the names .weight_ih_l... and .weight_hh_l... through FUN_180024a90. When the direction flag is set, FUN_180024a90 appends _reverse.

Three gates plus reverse-direction parameter names remain consistent with a bidirectional GRU-like layer. The exact gate ordering and equations are not yet fully verified. Bias key names and the recurrent forward function still require a focused call/data-flow pass.

## Model payload byte observations

The V7 payloads have high global entropy and contain many candidate zlib/gzip headers:

- AI_Megpoid.vtb2: 23,541,731 bytes, global entropy approximately 8 bits/byte.
- VOCALOID7.vhnv: 5,485,182 bytes, global entropy approximately 8 bits/byte.
- VOCALOID7.vtmg: 148,942 bytes, global entropy approximately 8 bits/byte.
- AI_Megpoid.vpit: 16,754 bytes, global entropy approximately 7.99 bits/byte.

The presence of embedded compression signatures is only a candidate indication until each stream is successfully decompressed and its boundaries/parent records are mapped. The updated static inspection workflow attempts bounded zlib/gzip decompression and records only streams that reach a valid end. No model bytes are modified.

## Porting consequence

The Android model loader should be split into three independently testable components:

1. Outer container reader for VTBR/VPIT/VHNV/VTMG.
2. Typed-object/tensor extraction, including the MD5-based key derivation and numeric-array conversion.
3. Float32 matrix packing using the 32-byte arena and 8-column padded descriptor representation.

Only the tensor extraction and packing boundary has been partially recovered here. The complete V7 container parser, neural execution graph, and Megpoid V7 synthesis are still not implemented.


## 2026-11-19 compression-candidate validation correction

The follow-up byte scanner tested candidate zlib/gzip signatures by attempting decompression from each candidate offset and requiring the decompressor to reach a valid end-of-stream marker. The inspected `.vtb2`, `.vpit`, `.vhnv`, and `.vtmg` files produced **zero** candidates that passed this full-stream check.

Therefore, the byte signatures in the high-entropy payloads are not evidence of embedded standard zlib/gzip streams; they are likely coincidental byte sequences or use a different framing/codec. The scanner records both the candidate offsets and the successful-stream list so this distinction is explicit. This result does not rule out proprietary compression or encrypted/obfuscated sections.
