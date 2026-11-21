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
