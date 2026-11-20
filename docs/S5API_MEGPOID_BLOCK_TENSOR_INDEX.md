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


## Follow-up: primary matrix and secondary layer property

The later static trace of `FUN_180052f90` → `FUN_18005e9b0` →
`FUN_180063ca0` distinguishes the two fixed-key values:

- KEY_1 is resolved as the primary typed tensor and instantiated as one or
  more `BlockMatrix` objects using descriptor-supplied row/column dimensions.
- KEY_2 is resolved later only when the relevant descriptor field enables the
  secondary-property path. It is passed to `FUN_180064e90`, not instantiated
  as a second `BlockMatrix` by `FUN_180063ca0`.

The index exposes `modelPropertyPairs()` to group the two typed values only
when they share the same immediate parent object. This pairing preserves the
observed data relationship without assigning a semantic name to KEY_2.

The real `AI_Megpoid.vtb2` audit found 88 such containing objects. Every one
had exactly one `*F32` tensor for each fixed key; no incomplete or ambiguous
pairs were found. The common primary/secondary element counts include
92,160/192 (16 objects), 9,216/96 (12), 18,432/192 (12), and 24,576/128 (9).
These values are structural size evidence only; the exact auxiliary-property
meaning and final matrix orientation still require the `FUN_180064e90`
consumer trace and descriptor cross-check.

The descriptor math gives a concrete count relation. `FUN_180063ca0`
creates `param_8` matrices of `param_7 × param_6`, so KEY_1 contains
`param_7 * param_6 * param_8) elements. It then calls
`FUN_180064e90(secondary, 1, param_7, ...)`, which creates a one-row,
`param_7)-column tensor (with the column stride padded to a multiple of 8).
KEY_2 therefore contains `param_7) elements, and the ratio
`KEY_1 / KEY_2` equals `param_6 * param_8`.

The Android pair value `primaryToSecondaryElementRatio` reports this quotient
only when both counts are positive and exactly divisible. It is a structural
descriptor consistency value, not a semantic label for weights, biases, or
conditioning roles.

For the repeated-block loader call at `0x180053421`, the raw caller
instructions establish `columns = descriptor[+4]`,
`rows = 2 * descriptor[+4]`, and
`matrixCount = descriptor[+0]`. The second property contains one row of
length `rows`; the first property contains
`rows * columns * matrixCount) elements. The new
`repeatedBlockMatrixShapeOrNull()` helper reconstructs this shape only when
the element counts satisfy the exact relationship.

The actual AI Megpoid audit found **69/88** paired objects matching this
specific repeated-block shape. The inferred matrix-count distribution is
1 (18), 2 (14), 3 (15), 5 (16), and 6 (6). The remaining 19 paired objects
are retained without this shape classification because other loader call
sites use different dimension tuples. The separate entry-level call at
`0x180053e13` has a single-bank tuple, but has not yet been associated with
each unmatched pair.

This remains a structural model-data binding improvement. It does not add
neural inference or PCM rendering.

## Follow-up: named neural layer parameters in VOCALOID7.vtmg

The shared-model loader `FUN_180068190` was traced independently of the
fixed-key block index. It calls `FUN_180064670` for nine named layers and
resolves each layer's `.weight` and `.bias` properties from the shared
`VOCALOID7.vtmg` object tree.

`S5MegpoidNamedLayerTensorIndex` now exposes those typed properties from
the loaded shared model member. `S5VoicebankModelPackage.namedLayerTensorIndex`
builds this index lazily. The index validates that each property is a typed
F32/F16 string, records its scalar count, and keeps float decoding deferred
until a caller requests the tensor.

The current archive contains all 18 named parameter tensors:

| Layer | Matrix count | Weight elements | Bias elements |
|---|---:|---:|---:|
| pre1 | 1 | 2,624 | 32 |
| pre2 | 1 | 1,024 | 32 |
| conv1 | 3 | 6,144 | 64 |
| cond1 | 1 | 1,664 | 64 |
| conv2 | 3 | 6,144 | 64 |
| cond2 | 1 | 1,664 | 64 |
| conv3 | 3 | 6,144 | 64 |
| cond3 | 1 | 1,664 | 64 |
| linear | 1 | 96 | 3 |

The matrix-count values come from the nine `FUN_180068190` call sites.
The `FUN_180064670` constructor stores `param_5` as `axis0`,
`param_4` as `axis1`, and `align_up(param_5, 8)` as
`padded_axis0`; the matrix bank count is `param_6`. The secondary
bias vector has one row and `param_5` elements. The recovered descriptor
axes are therefore:

| Layer | axis0 | axis1 | padded axis0 | Matrix count |
|---|---:|---:|---:|---:|
| pre1 | 32 | 82 | 32 | 1 |
| pre2 | 32 | 32 | 32 | 1 |
| conv1 | 64 | 32 | 64 | 3 |
| cond1 | 64 | 26 | 64 | 1 |
| conv2 | 64 | 32 | 64 | 3 |
| cond2 | 64 | 26 | 64 | 1 |
| conv3 | 64 | 32 | 64 | 3 |
| cond3 | 64 | 26 | 64 | 1 |
| linear | 3 | 32 | 8 | 1 |

The source traversal order in `FUN_180064670` is
`[axis0][axis1][matrix]`, matching the existing native
`packMatrixBank()` input contract. `S5MegpoidNamedLayerParameters`
now exposes `shapeOrNull()`, `packWeightMatrixBank()`, and
`packBiasVector()`. The weight path decodes the selected layer's payload,
invokes the native matrix-bank packer, and verifies storage length including
axis0 padding. The bias path invokes native `packRowMajor()` with
`axis0 = bias count` and `axis1 = 1`, matching the second one-row
descriptor created by `FUN_180064670`. The prepared result retains both
unpadded bias values and the padded one-row bias buffer. No
input/output-channel names are assigned beyond the recovered descriptor axes.

### Correct parameter hash serialization

The model tree stores the salted MD5 digest as its 16 bytes in canonical
lowercase hexadecimal order. The native hash implementation previously
formatted its four 32-bit state words directly, reversing the byte order
inside each word. The formatter now emits the bytes of each state word in
little-endian order. Native tests include both generic MD5 vectors and two
real `VOCALOID7.vtmg` keys (`pre1.weight` and `conv1.bias`).

These named tensors are now addressable from the verified model package.
They have not yet been assigned to the residual operator's runtime record
offsets, and this index does not claim to execute the named-layer network or
produce PCM audio.
