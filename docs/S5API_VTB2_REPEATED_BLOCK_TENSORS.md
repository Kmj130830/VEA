# AI Megpoid VTB2 separate parameter tensor collection

Date: 2026-11-22

## Verified JSON location

The decoded `AI_Megpoid.vtb2` model object contains a repeated tensor array at:

```text
$.6dbe9f75292d82b3cc6c57bd65089b20
  .c688ac0ef614fc2004874245946c9580
  .60934ffa2a466aa4dea617defa78f36b[0..8]
```

The parent model configuration also contains `d31a... = 9` at
`$.a4ba655a061e6151f80574e6088492a0.b4a5a1d9f51f573d714597f1e5e64f17`,
matching the nine array entries. The loader checks both sources rather than
relying on array length alone.

## Per-entry tensor schema

Every entry has two child objects:

| Child object | Field | Count | Derived factorisation |
|---|---|---:|---|
| `3d8a9ac90f7ac150415ce60a7b3aea38` | `30c16498ff30815c43d58f610a91f938` | 24,576 F32 | `3 × 128 × 64` |
| same | `c169d8ef47cc23f097ff312d08a6f829` | 128 F32 | `128` |
| `88f599b6848cb38e980ea3467fbec4b6` | `30c16498ff30815c43d58f610a91f938` | 8,192 F32 | `64 × 128` |

All 27 tensors (9 × 3) decode as finite float32 values. Tensor paths, not
the repeated leaf hash alone, are used to associate them with an array index.

## Separation from renderer c5d50

This nine-record array is stored under a separate model object and is not
selected by the stage/block-indexed JSON key generators used by
`FUN_1800ced10`. Its tensor lengths are recorded without assigning semantic
roles from their factorisation alone. It is therefore loaded as a separate
VTB2 collection and is **not** treated as the c5d50 block descriptor list.

## Android implementation

`S5Vtb2RepeatedParameterLoader` scans only the `a4ba...` and `6dbe...`
objects, decodes the 27 selected F32 tensors by their complete JSON paths,
checks the declared record count and exact element counts, and keeps the
per-entry raw arrays. It does not build a full JSONObject tree or decode
unrelated VTB2 tensors.

`VocaloidAiEngineAdapter.render()` now runs this validation alongside VTMG
loading before returning the existing explicit unsupported-render status.
It does not claim to execute these separate collection tensors or generate audio.

Loader integrity checks additionally require exactly 27 typed tensor leaves
under the separate record array (three per record), reject duplicate full JSON value
paths, and reject extra/missing array records. Identical leaf hashes elsewhere
in the VTB2 document are not conflated because each selected tensor is matched
by its complete array-indexed path.
