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
