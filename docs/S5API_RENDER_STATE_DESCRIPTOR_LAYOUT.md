# S5API render-state root: operator descriptors and vector ownership

Date: 2026-11-21

## Evidence scope

This note records offsets relative to the render-state root passed as
param_1 to FUN_1800c7ad0. It does not treat the same numeric offsets in an
unrelated nested object's this-pointer as the same C++ member.

The primary evidence is the callsite in FUN_1800c7ad0, the field-copy logic
in FUN_1800cdbb0 and FUN_1800c47a0, and the cleanup sequence in
FUN_1800becd0.

## Dense projection descriptor at root +0x1F8

FUN_1800c7ad0 passes root +0x1F8 to FUN_1800c5260.

FUN_1800c5260 reads the descriptor as:

| Root-relative field | Meaning |
|---|---|
| +0x1F8 | input feature count K |
| +0x1FC | output feature count M |
| +0x200 | optional-bias flag (byte) |
| +0x208 | weight matrix descriptor, a pointer-pair object |
| +0x218 | bias vector descriptor, a pointer-pair object |

The input tensor descriptor is passed separately from root +0x3D0 and
the output tensor descriptor from root +0x3B0.

The operator computes the row-major matrix product
[M,K] x [K,N] -> [M,N]. If the byte flag at descriptor +0x08 is nonzero,
it adds the bias vector across every position.

FUN_1800cdbb0 copies the two dimension fields and bias flag, then copies the
weight and bias descriptor objects using FUN_1800c0290 at root-relative
+0x208 and +0x218. FUN_1800becd0 destroys the embedded descriptor subobject
at root +0x1F8 through FUN_1800beaa0.

## Global normalization descriptor at root +0x228

FUN_1800c7ad0 passes root +0x228 to FUN_1800c7920.

FUN_1800c7920 reads:

| Root-relative field | Meaning |
|---|---|
| +0x22C | epsilon |
| +0x230 | per-feature scale vector descriptor |
| +0x240 | per-feature bias vector descriptor |

The feature count is carried by the separately supplied input tensor
descriptor (its +0x18 field); its position count is at +0x1C.

The statistics domain is the entire feature-position input tensor. One
global mean and inverse standard deviation are calculated, then each feature
uses its own scale and bias over all positions.

FUN_1800cdbb0 copies the two scalar words at root +0x228/+0x22C and invokes
FUN_1800c0290 for the two vector descriptor objects at +0x230/+0x240.
FUN_1800becd0 releases this embedded subobject through FUN_1800c0110.

## Sequence operator descriptor and workspace arrays

The render-state root owns the following arrays:

| Root-relative member | Element stride | Role established at FUN_1800c7ad0 |
|---|---:|---|
| +0x250 | 0x30 | per-sequence temporal projection descriptors |
| +0x268 | 0x18 | per-sequence temporal projection workspace descriptors |
| +0x280 | 0x30 | per-sequence banked-linear descriptors |
| +0x298 | 0x18 | per-sequence banked-linear workspace descriptors |
| +0x2B0 | 0x28 | alternate banked/operator descriptors |
| +0x2C8 | 0x18 | alternate operator workspace descriptors |

For each adjacent sequence pair, FUN_1800c7ad0 calls FUN_1800c5440 with:
- descriptor = *(root + 0x250) + sequenceIndex * 0x30;
- current input feature descriptor = sequenceFeatures + sequenceIndex * 0x20;
- next output feature descriptor = sequenceFeatures + (sequenceIndex + 1) * 0x20;
- workspace descriptor = *(root + 0x268) + sequenceIndex * 0x18.

The later banked-linear branch calls FUN_1800c6390 with the descriptor at
*(root + 0x280) + reverseSequenceIndex * 0x30 and workspace at
*(root + 0x298) + reverseSequenceIndex * 0x18. When the dispatch flag
selects the alternate branch, it calls FUN_1800c72d0 with the descriptor
at *(root + 0x2B0) + reverseSequenceIndex * 0x28 and workspace at
*(root + 0x2C8) + reverseSequenceIndex * 0x18.

The copy constructor FUN_1800cdbb0 copies descriptor arrays at +0x250,
+0x280 and +0x2B0 using element strides 0x30, 0x30 and 0x28. The
copy-assignment path FUN_1800c47a0 copies workspace descriptor arrays at
+0x268, +0x298 and +0x2C8 using stride 0x18. The two banked workspace
vectors are selected according to the render-state mode flag.

## Residual block records

The same root object separately owns:

| Root-relative member | Element stride | Role |
|---|---:|---|
| +0x2E0 | 0x100 | residual block-data records |
| +0x2F8 | 0x88 | residual block-state records |

FUN_1800cdbb0 copies the block-data vector using FUN_1800afc30 and the
0x100-byte stride. FUN_1800c47a0 copies the state vector using FUN_1800af720
and the 0x88-byte stride.


## Terminal model-output buffers and representation selector

The trailing section of `FUN_1800c7ad0` establishes the terminal buffer
contract after the output-head and optional modulation/carrier branches:

| Root-relative field | Observed role |
|---|---|
| `+0x48` | Byte selector for the final two-plane representation |
| `+0x4F8` | Pointer to the interleaved complex-spectrum output buffer |
| `+0x13E * 4` | Word-index form of the same output-buffer pointer field used by the decompiler |
| `+0x20` | Output-transform descriptor passed to `FUN_18012c740` |

The output-feature buffer contains two contiguous float32 planes, each with
`param[2]` values. The function converts them to `param[2]` interleaved
complex bins and passes the result to `FUN_18012c740` together with the
descriptor at root `+0x20`. The selector at root `+0x48` chooses between
direct real/imaginary planes and log-magnitude/phase planes. Its key is
`d22b2750ccebc1459f7a9d9ea7186bbf` from `FUN_1800e6c20()`; the supplied
AI Megpoid VTB2 contains boolean `true`, selecting log-magnitude/phase.

For the supplied AI Megpoid runtime options, `param[2] = 513`, so the
terminal output contains 513 complex bins / 1,026 float32 values. The
separate output-transform descriptor consumes this frame and produces
`param[3] = 320` time-domain samples.

The output-head descriptors at root `+0x310` and `+0x338` are additional
Dense/global-normalization objects reached after the sequence/residual
operators in the non-carrier branch. Their model-property provenance is not
the same as the pre-residual descriptors at `+0x1F8` and `+0x228`.
The optional multi-part and carrier-mix paths use further state at offsets
around `+0x3400`, `+0x3559`, `+0xD40`, `+0xD6C`, `+0xD74` and
`+0xD80`; their complete ownership and model-value mapping remain under
investigation.

## Object-relative offset caution

FUN_180015d10 and FUN_180016dc0 are destructors for other nested object
types. For those functions' own this-pointers, numeric offsets +0x1F8,
+0x228 and +0x250 occur as vector end pointers in separate [begin,end,
capacity] triples. This does not contradict their render-state-root roles
above; it demonstrates why a global scan matching a numeric offset alone
cannot identify a field. The object base and the owning constructor/destructor
must be established for each access.

The current map proves the render-state member layout and operator call
contracts. It does not yet prove which precise model-tree property keys
populate the dense and normalization descriptors at +0x1F8/+0x228, or
the temporal/banked operator descriptors in the arrays. Those model-value
provenance paths remain the next mapping task.


## VTB2 property provenance: dense projection and global normalization

The model-population trace in \`FUN_1800ced10\` resolves the three exact
property keys used by the operators at root offsets \`+0x1F8\` and
\`+0x228\`. The key generator \`FUN_18001acc0\` formats four 32-bit words
from the embedded constants as zero-padded lowercase hexadecimal words.

| Descriptor | Lookup key | VTB2 role | Construction evidence |
|---|---|---|---|
| Dense weights | \`dd8f659ac91cbd5ef58ebd6ed786339e\` | \`[outputFeature][inputFeature]\` matrix | \`FUN_1800d5970\` -> \`FUN_1800d4610\` |
| Normalization scale | \`6121c88871cfb21dab889701aef1922a\` | Per-feature multiplier | First vector passed to \`FUN_1800d4a90\`, stored at descriptor \`+0x08\` |
| Normalization bias | \`f860a37573c14d61e3a339d70eadd90f\` | Per-feature offset | Second vector passed to \`FUN_1800d4a90\`, stored at descriptor \`+0x18\` |

The dense descriptor is initialized with its bias-enable flag set to zero by
\`FUN_1800c06c0\`; the subsequent \`FUN_1800d4610\` call attaches the dense
weight vector but does not allocate a bias vector. Therefore this dense
projection has no model bias. Its matrix size is the product of the active
input and output feature dimensions supplied by the render-state descriptor.

The normalizer epsilon comes from the float32 bit pattern \`0x358637bd\`,
which is \`1.0e-6\`. The decompiled \`FUN_1800c7920\` reads the vector at
descriptor \`+0x08\` as the scale and the vector at \`+0x18\` as the bias,
then applies the global mean/inverse-standard-deviation transform across the
complete feature-position tensor. The Android index preserves these exact
roles and constructs the dense/normalization stage from these three VTB2
properties when the caller supplies the active descriptor dimensions.

## Per-sequence Temporal and Banked property provenance

The six key factories each return a 16-entry table indexed by adjacent-sequence
index. All table values have now been extracted from their static initializers
and added to \`S5MegpoidSequenceOperatorTensorIndex\`.

| Native factory | Model property role | Consumer / descriptor path |
|---|---|---|
| \`FUN_1800e8620(i)\` | Temporal projection weights | \`FUN_1800d44b0\` -> descriptor +0x10 -> \`FUN_1800c5440\` |
| \`FUN_1800e76c0(i)\` | Temporal optional bias | \`FUN_1800d44b0\` -> descriptor +0x20 when enabled |
| \`FUN_1801256a0(i)\` | Standard banked-linear weights | \`FUN_1800d47c0\` -> per-bank matrices -> \`FUN_1800c6390\` |
| \`FUN_180122880(i)\` | Standard banked-linear optional bias | \`FUN_1800d47c0\` -> output-feature bias when enabled |
| \`FUN_180124740(i)\` | Alternate transition weights | alternate descriptor +0x08 -> \`FUN_1800c72d0\` |
| \`FUN_1801237e0(i)\` | Alternate transition bias | alternate descriptor +0x18 -> \`FUN_1800c72d0\` |

The temporal constructor call is
\`FUN_1800d44b0(descriptor, temporalWeights, temporalBias)\`. The operator
reads weights from descriptor +0x10 and optional bias from +0x20. The native
initializer uses indices from zero to \`param_1[5] - 2\`; the static factory
table has sixteen entries. Android requires the exact weight key for the
requested index and resolves bias only when the render descriptor's
bias-enable flag is true.

The standard banked-linear constructor call is
\`FUN_1800d47c0(descriptor, bankedWeights, bankedBias)\`. Its source tensor is
\`[inputFeature][outputFeature][bank]\`; the native initializer transposes it
to \`[bank][outputFeature][inputFeature]\`. Android performs the same
permutation before calling the existing native banked-linear operator, which
returns \`[outputFeature][position][bank]\`. Bias has one value per output
feature and is broadcast across positions and banks when enabled.

The alternate branch stores its weight and bias references at different
offsets and uses \`FUN_1800c72d0\`. Android indexes those two tensor families,
but does not conflate their interpolation/operator layout with standard
banked-linear execution.

All key constants are extracted from native factory initializers, not
inferred from tensor sizes. Active sequence count, feature dimensions,
kernel size, bank count and optional-bias flags still come from render
descriptor metadata and are not guessed by this index.


## Native sequence/transition counts and reverse feedback order

The supplied AI Megpoid V7 `VTB2` runtime-options tree yields
`param[5] = 4` sequence-feature slots and `param[6] = 2` residual columns.
The initializer allocates `param[5]` feature slots, runs the Temporal and
Banked descriptor loops `param[5] - 1` times, and populates the residual
factory grid `param[5] * param[6]` (4 rows × 2 columns).

The verified call order in `FUN_1800c7ad0` is:

| Order | Operator | Sequence slot / residual row |
|---:|---|---|
| 1 | Temporal[0] | slot 0 → slot 1 |
| 2 | Temporal[1] | slot 1 → slot 2 |
| 3 | Temporal[2] | slot 2 → slot 3 |
| 4 | Residual row 3 | initial highest slot, before the reverse Banked sweep |
| 5 | Banked[0] → Residual row 2 | slot 3 → slot 2 |
| 6 | Banked[1] → Residual row 1 | slot 2 → slot 1 |
| 7 | Banked[2] → Residual row 0 | slot 1 → slot 0 |

Thus there are four sequence slots, three Temporal transitions, three
Banked transitions, and eight residual blocks. The initial Residual row 3
does not have a preceding Banked operation.

Android's `S5MegpoidSequenceTransitionPipeline` mirrors this order:
Dense + Global Normalization, Temporal[0..2], Residual row 3, then three
Banked/Residual pairs. A Banked output is stored as
`[feature][position][bank]`; the innermost bank axis is flattened into
`position * bankCount` for the following residual row.

The pipeline starts at an already prepared feature tensor. Note/phoneme
feature generation and final post-residual decoding remain outside this code.
