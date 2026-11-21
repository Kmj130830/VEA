# S5API layer runtime findings

Date: 2026-11-19

## Model configuration entry

The decompilation of `FUN_180056f40` shows it is a model-layer configuration routine, not an audio callback.

Observed steps:

1. It builds hashed property keys and reads three scalar values from a typed model object. The values are converted from stored doubles to integer fields at offsets +0x68, +0x6c, and +0x70 in its owning object.
2. It builds a short layer-kind string containing `rnn`.
3. It calls `FUN_18005ebe0` with the model-object context and the `rnn` kind to create/recover recurrent-layer parameters.
4. It then calls `FUN_18005df50` with a dimension derived as `field_0x6c * 2` and `FUN_180065510` with a constant dimension argument of 1, both using the remaining scalar field as another parameter.

This establishes a recurrent layer plus two additional model-layer setup paths in the same configuration routine. It does not yet prove whether those additional paths are projection, normalization, activation, or another operation.

## Recurrent parameter reader

`FUN_18005ebe0` iterates over layer/direction combinations, constructs `.weight_ih_l...` and `.weight_hh_l...` keys, and optionally appends `_reverse`. It reads both sets of weights into per-layer buffers and copies them into the packed matrix structures.

The code derives a three-gate width (`param_5 * 3`). In the input-weight copy loop, each of those gate rows receives `inputFeatures` values. The recurrent-weight copy loop similarly stores `3 * hiddenFeatures` rows with `hiddenFeatures` values per row. For the bidirectional path, later-layer input width is multiplied by the direction count. These observations establish parameter dimensions and key naming, not the recurrent forward equations.

The parameter reader alone does not establish gate ordering, reset/update placement, bias equations, activation functions, or sequence direction semantics. Calling it a proven GRU implementation would be premature.

## Matrix layout linked to the recurrent layer

The recovered matrix loader `FUN_180064670` provides the exact copy mapping now ported in `libveas5tensor.so`:

- source is indexed as `((axis0 * axis1_count + axis1) * matrix_count + matrix)`;
- each output matrix is indexed as `axis1 * padded_axis0 + axis0`;
- `padded_axis0 = align_up(axis0_count, 8)`;
- descriptor records are 0x18 bytes and 32-byte arena allocations are used.

The source indexing is interleaved across the matrix-bank dimension, rather than a plain row-major matrix dump. The recurrent reader separately copies its per-direction weight matrices row by row.

## Android recurrent parameter preparation

`app/src/main/java/com/kmj/vea/engine/S5RecurrentParameters.kt` now contains a Kotlin parameter-layout and validation layer based on the recovered reader:

- creates `rnn.weight_ih_lN` and `rnn.weight_hh_lN` names, including `_reverse` direction names;
- validates the recovered `[3 * hiddenFeatures][inputFeatures]` and `[3 * hiddenFeatures][hiddenFeatures]` matrix lengths;
- validates the optional pair of `bias_ih` / `bias_hh` vectors;
- accounts for the doubled input width of later layers in a bidirectional stack;
- sends validated matrices to the existing native `packRowMajor` routine, which pads the column dimension to eight floats;
- cross-checks generated parameter names against the existing native C++ name builder when that library is loaded.

The JVM tests cover unidirectional and bidirectional layouts, reverse names, later-layer width, missing tensors, non-finite data, and copy isolation. This stage accepts already-decoded tensor arrays. It does not parse the outer VTB2 object tree or execute the recurrent layer.

## VTB2 payload and tensor-object parsing

The complete archive inventories contain 21 members in `megpoidV7.zip` and 3 members in `AI Model.zip`. Apart from the five model containers below, the Megpoid archive contains artwork, manifest metadata, and 13 small `.vsstyle` presets; there is no additional neural-weight binary hidden elsewhere in these two archives.

All five packaged Megpoid/shared VOCALOID7 resources were decoded offline with the recovered `FUN_18002aa90` transform. Every decoded body is valid UTF-8 JSON:

| Resource | Container | Decoded bytes | JSON objects | Arrays | Strings | Typed tensor strings |
|---|---:|---:|---:|---:|---:|---:|
| `AI_Megpoid.vtb2` | VTBR | 23,541,587 | 258 | 108 | 299 | 283 |
| `AI_Megpoid.vpit` | VPIT | 16,610 | 74 | 1 | 9 | 0 |
| `COMMON7.vpit` | VPIT | 3,781 | 18 | 1 | 2 | 0 |
| `VOCALOID7.vhnv` | VHNV | 5,485,038 | 39 | 14 | 46 | 46 |
| `VOCALOID7.vtmg` | VTMG | 148,798 | 4 | 0 | 18 | 18 |

The S5API layer-configuration property keys recovered from `FUN_180056f40` are present in the Megpoid VTB2 and shared VTMG payloads. The three-gate recurrent names tested against all object keys across the resources (layers 0–31, forward/reverse weights and biases) produced no direct or appended-weight/bias digest matches.

- Typed tensor leaves use `*F32`, `*F16`, and `*S32` tagged strings.
- The same inner tensor-field keys recur under different parent objects, so a flat key-to-array map is ambiguous.
- VTB2 contains 283 typed tensor strings; shared VHN/VTMG contain another 64.

The Android port now contains `S5ModelJsonScanner`, a bounded streaming parser that scans each of the five installed containers one at a time, counts JSON structures and typed tensor strings, and can selectively retain only requested hash-key matches. The render adapter invokes this package-wide scanner before reaching the explicit unsupported-render boundary. A separate `S5ModelPayloadReader` can decode typed fields while preserving their full object paths for later graph mapping.

The parser intentionally does not flatten duplicate inner keys or infer which opaque object path is the RNN's `weight_ih` / `weight_hh` member. That mapping must be recovered from the model-layer construction and lookup call chain. This avoids feeding a valid but unrelated tensor into the recurrent loader.

At render request time, `VocaloidAiEngineAdapter` first scans the Megpoid VTB2 for the two optional feature keys. The recovered RNN enable key is absent, so it stops before enumerating recurrent parameter names. This avoids both needless full-package scans and the earlier incorrect inference that Megpoid must provide the generic RNN tensors.

The call-site disassembly at `FUN_180056f40 -> FUN_18005ebe0` establishes:
- the input-feature width is read from configuration field `+0x68`, which resolves to 418 in the inspected VTB2 object;
- the recurrent hidden-feature width is read from configuration field `+0x6c`, but the hard-coded property key used for that field has not yet been matched to the supplied JSON;
- `param_6=1` sets one recurrent layer;
- `param_7=1` enables the reverse-direction branch;
- `param_8=1` makes the direction loop run twice.

The earlier hidden-width value of 9 was inferred from a different nested model field and is not proven to be the value consumed at `param_5`; that inference is withdrawn. The second configuration field at `+0x70` resolves to 40, but it is consumed by the subsequent `FUN_18005df50` and `FUN_180065510` setup calls, not by the recurrent weight dimensions.

The parent factory `FUN_180056d60` invokes `FUN_180056f40` only when its third boolean argument is nonzero. In the primary export path, that flag is populated by `FUN_18004e1e0(modelObject)`; a separate flag from `FUN_18004e3b0(modelObject)` controls creation of the paired optional objects.

The two flag helpers are key-presence checks, not boolean-value readers. They call the JSON object lookup helper and return whether the requested hashed child exists. The exact keys are:

| S5API helper | Hash key | Factory field | Use |
|---|---|---|---|
| `FUN_18004e3b0` | `69d61aca6f87b9618d4c65b9560cb5be` | `+0xf10` | Gates the paired optional-object setup using `FUN_18004d170` / `FUN_18004d3b0` |
| `FUN_18004e1e0` | `9f174dc2b1ab89f96f4577e17d5e568a` | `+0xf11` | Gates the RNN setup path and the call into `FUN_180056f40` |

Neither key exists anywhere in the decoded AI Megpoid VTB2 JSON object tree. Therefore both optional branches are disabled for the supplied Megpoid package. In particular, the absence of the `FUN_18004e1e0` key means `FUN_180056d60` does not call `FUN_180056f40`; the generic `rnn` parameter loader is not part of this bank's active model graph. This resolves why the recurrent parameter hashes and expected RNN arrays could not be found: looking for them in this Megpoid model was the wrong branch.

The two objects passed to `FUN_180056f40` are selected separately from the parsed model only when that RNN feature key is present. The current Android adapter now reproduces the key-presence check against the installed `AI_Megpoid.vtb2` before attempting any recurrent tensor lookup.

The exact recurrent names generated by the observed `rnn` base (`rnn.weight_ih_l0`, `rnn.weight_hh_l0`, both bias names, and reverse-direction variants) do not match any JSON object keys in the five resources. This is consistent with the feature gate being absent: the supplied Megpoid VTB2 does not instantiate this generic recurrent subgraph. The Kotlin recurrent parameter loader remains a reusable, validated shape/packing component for model packages that do enable this branch; it is not used by the current Megpoid graph.

## Next static-analysis targets

The latest Ghidra pass additionally decompiles:
- `FUN_18005df50`
- `FUN_180065510`
- `FUN_1800656e0`
- `FUN_180060270`
- `FUN_180064cc0`
- CPU dispatch paths for activation, depthwise convolution, layer-normalization statistics, and the DSU helper.

These are used to recover concrete scalar math, operator shapes, and the relationship between the `rnn` layer and its surrounding projection/conditioning operators.

## Implementation boundary

The Android ARM64 library currently implements recovered tensor primitives and recurrent parameter packing, but it does not yet execute the recurrent layer or complete neural graph. The VTB2 object-tree/weight-key reader and the full acoustic rendering path remain incomplete. No AI Megpoid audio render is available at this milestone.
