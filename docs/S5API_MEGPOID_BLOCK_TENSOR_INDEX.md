# AI Megpoid V7 model-block tensor index

Date: 2026-11-20

## Scope

This milestone binds the nine fixed object-tree lookup keys used by the S5API
`FUN_1800537e0` model-block setup path to the typed tensor strings nested below
those keys. It does not assign semantic parameter names where the decompilation
has not established them, and it does not execute a neural forward pass.

The Android implementation is `S5MegpoidBlockTensorIndex`. It is created lazily
from the loaded `AI_Megpoid.vtb2` object tree. It records each tensor's lookup
slot, structural path, marker, encoded byte length, and scalar count. Base64
payloads remain in the original parsed tree and are decoded to `FloatArray`
only when a caller requests an individual tensor.

## Verified model-tree inventory

The `VEA S5API model payload transform validation` workflow ran against the
repository's supplied `megpoidV7.zip` and `AI Model.zip` archives. The native
payload decoder matched the independent Python reference byte-for-byte for all
five model members. For `AI_Megpoid.vtb2`, the nine recovered lookup keys were
present and their nested typed payloads were summarized without printing
weight values.

| Lookup slot | Matching nodes | Nested floating tensors | Observed node structure |
|---|---:|---:|---|
| KEY_1 | 100 | 100 | typed `*F32` leaves |
| KEY_2 | 88 | 88 | typed `*F32` leaves |
| KEY_3 | 4 | 72 | objects containing arrays and nested objects |
| KEY_4 | 4 | 8 | objects with two typed `*F32` leaves |
| KEY_5 | 4 | 8 | objects with two typed `*F32` leaves |
| KEY_6 | 5 | 76 | arrays containing nested objects |
| KEY_7 | 16 | 32 | objects with two typed `*F32` leaves |
| KEY_8 | 16 | 32 | objects with two typed `*F32` leaves |
| KEY_9 | 4 | 8 | objects with two typed `*F32` leaves |
| **Total** | **241** | **424** | |

The payload inventory found `*F32` leaves for these nine key families. This
report intentionally does not assign matrix dimensions from element counts
alone. Several observed lengths are compatible with more than one row/column
interpretation; dimensions must be derived from the corresponding S5API
constructor arguments and matrix descriptors.

## Android implementation

- `S5MegpoidBlockLookupSlot` stores the nine recovered lookup hashes.
- `S5MegpoidBlockTensorIndex.build` walks objects and arrays recursively,
  recording floating-point tensor leaves below each fixed lookup key.
- `S5MegpoidModelTensorReference.decodeFloatArray` delegates to the existing
  `S5TypedTensorDecoder` and checks the decoded element count.
- `S5VoicebankModelPackage.modelBlockTensorIndex` exposes the index lazily.
- Unit tests cover nested key resolution, F32/F16 element counts, deferred
  decoding, malformed Base64 lengths, and scalar alignment.

## Limits and next trace

The index makes the real model's typed tensors addressable from Android code;
it does not prove the semantic role, logical dimensions, execution order, or
feature conditioning of each tensor. The next required trace is to correlate
the caller-supplied dimensions in `FUN_1800537e0` / `FUN_180052f90` with the
matrix descriptors consumed by the model-block and residual depthwise/MLP
operators. Only after that mapping is verified should tensors be passed to a
specific native forward operation.

No AI Megpoid PCM output is produced by this milestone.


## Follow-up: nested matrix property pairs

The later static trace of `FUN_180052f90` → `FUN_18005e9b0` →
`FUN_180063ca0` refined the meaning of the first two hashes. KEY_1 and KEY_2
are two property lookups performed inside a selected layer object and passed
together to the matrix-object loader; they are not two independent top-level
block sections. The index now offers `matrixPropertyPairs()`, pairing only
unique typed leaves with the same immediate parent path. Incomplete or
ambiguous groups are omitted.

The pair members deliberately retain neutral names. Static evidence proves
that both values are loaded as matrix properties, but does not yet establish
which is a kernel, bias, projection, or another matrix role. Shape validation
against the descriptor arguments and the residual runtime operator is the
next step. This is a structural binding improvement only; it does not add
neural inference or PCM rendering.
