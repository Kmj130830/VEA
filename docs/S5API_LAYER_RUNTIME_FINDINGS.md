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

The code also derives a three-gate width (`param_5 * 3`). This is strong evidence for a GRU-shaped recurrent parameter layout, potentially bidirectional. However, the parameter reader alone does not establish the forward equations, gate ordering, reset/update placement, bias handling, activation functions, or sequence direction semantics. Calling it a proven GRU implementation would be premature.

## Matrix layout linked to the recurrent layer

The recovered matrix loader `FUN_180064670` provides the exact copy mapping now ported in `libveas5tensor.so`:

- source is indexed as `((axis0 * axis1_count + axis1) * matrix_count + matrix)`;
- each output matrix is indexed as `axis1 * padded_axis0 + axis0`;
- `padded_axis0 = align_up(axis0_count, 8)`;
- descriptor records are 0x18 bytes and 32-byte arena allocations are used.

The source indexing is interleaved across the matrix-bank dimension, rather than a plain row-major matrix dump.

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

The Android ARM64 library currently implements the recovered aligned arena, 0x18-byte descriptor, numeric double-to-float conversion, and interleaved matrix-bank packing. It does not yet execute the recurrent layer or any neural graph. No AI Megpoid audio render is available at this milestone.
