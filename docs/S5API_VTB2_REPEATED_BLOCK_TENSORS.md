# AI Megpoid VTB2 repeated parameter tensor collection

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

## Relationship to c5d50

The counts are compatible with the renderer block's observed 64-channel
and 128-expanded-feature dimensions: `128 × 64` for an expansion matrix and
`64 × 128` for a return matrix. The first tensor has an additional factor
of three. However, `FUN_1800c5d50` passes a single matrix pointer to its
first GEMM call, and the exact runtime selection/consumption of the three
banks is not established by tensor sizes alone. These objects are therefore
loaded as repeated VTB2 parameter packs, but are **not yet wired** to the
`c5d50` descriptor's `+0x32` / `+0x26` fields.

## Android implementation

`S5Vtb2RepeatedBlockParameterLoader` scans only the `a4ba...` and `6dbe...`
objects, decodes the 27 selected F32 tensors by their complete JSON paths,
checks the declared block count and exact element counts, and keeps the
per-entry raw arrays. It does not build a full JSONObject tree or decode
unrelated VTB2 tensors.

`VocaloidAiEngineAdapter.render()` now runs this validation alongside VTMG
loading before returning the existing explicit unsupported-render status.
It does not claim to execute these block tensors or generate audio.
