# S5API residual depthwise/MLP block port

Date: 2026-11-19

## Decompiled dataflow in FUN_1800c5d50

The function receives C channels and N positions. Its observed execution order is:

1. FUN_1800c67e0 updates a history ring buffer and computes the 7x7 depthwise result.
2. A selected MRW_LNSTATS implementation produces sum and sum-of-squares over C*N values.
3. The caller calculates mean, variance = sumSquares/(C*N) - mean^2, clamps negative variance to zero, adds epsilon, and calculates sqrt.
4. FUN_1800c3740 executes the expansion linear projection with M = C*expansionFactor, N = positions, K = C.
5. FUN_18012cb50 returns an ISA-selected GELU function. The caller applies that function per expanded feature row with scale=1/std and bias = biasA[f] - (1/std)*mean*biasB[f].
6. FUN_1800c3740 projects expanded features back to C channels.
7. The selected residual implementation performs dst = (dst + bias[c])*scale[c] + residual[c] for each channel row.

## VTB2 descriptor-to-block affine preparation

The stage-block constructor `FUN_1800d4bf0` does not pass the stored 32-element
block vectors directly to GELU. It multiplies both vectors by the
`[expanded_features][channels]` expansion matrix and prepares two
expanded-feature bias arrays:

- `expanded_bias_a = expansion_weights × block_vector_b + expansion_bias`
- `expanded_bias_b = expansion_weights × block_vector_a`

The `FUN_1800c5d50` GELU call then uses
`bias = expanded_bias_a - inverse_std × mean × expanded_bias_b`.
At the output end, the stored projection bias is the affine bias and
`block_vector_c` is the per-channel scale used by the residual update.

VEA now exposes `vea_s5_prepare_c5d50_expansion_bias_f32` for the two
precomputed arrays and `vea_s5_run_vtb2_c5d50_block_f32` to accept the
model's stored tensors directly. The JNI/Kotlin bridge offers
`runVtb2C5d50StageBlock`, so a decoded stage/block descriptor can enter this
native operator without hand-constructed expanded bias arrays.

This is a static reconstruction of the descriptor-building and block call
sequence. It has not yet been matched against a live Yamaha render or a
reference waveform.
## Stateful streaming wrapper and history layout

`FUN_1800c67e0` appends the current `[channel][position]` activation block
to the block-local ring before building the seven-row chronological window.
The ring snapshot is stored as `[history_row][channel][position]`, while
`FUN_1800c5d50` consumes `[channel][history_row][position]`. These are not
interchangeable layouts.

`S5DepthwiseHistoryRing.snapshotChannelMajor()` performs that explicit
transpose, preserving oldest-to-newest row order. The conversion is covered
by `S5DepthwiseHistoryRingLayoutTest` with a two-channel, two-row fixture.

`S5Vtb2C5d50BlockProcessor` owns one ring for one `(stage, block)` parameter
descriptor. `process()` appends the input block, snapshots/transposes history,
and invokes `NativeS5TensorBridge.runVtb2C5d50StageBlock`. `reset()` clears
only that block's history. The default block length is the recovered 320
samples and the builder's epsilon constant `0x358637bd` is `1e-6f`.

This is a stateful single-block execution boundary. It is not yet a complete
four-stage renderer: VTMG stage assignment, block scheduling, recurrent and
other convolution branches, spectral synthesis, and final waveform output
remain separate integration work.

## Android scalar block API

VEA now exposes vea_s5_run_residual_dw_mlp_block_f32 and Kotlin NativeS5TensorBridge.runResidualDepthwiseMlpBlock. The input is an explicit seven-row history tensor per channel, with a separate 7x7 filter per channel. Expansion and projection matrices are row-major. Statistics are global over the depthwise output tensor. The function uses the recovered affine GELU parameter expression and residual update.

The stateless block API accepts history rows explicitly. A separate
`VeaS5HistoryRing` state object now ports the block-cache behavior observed in
`FUN_1800c67e0`:

- storage contains `historyRows * channels * positions` float values;
- each append writes one complete `[channel][position]` block at the next slot;
- the write slot advances and wraps at `historyRows`;
- snapshots start at the next write slot and return the slots in
  oldest-to-newest order, including zero-initialized rows before the cache fills;
- reset clears the storage and resets the write slot.

The C API is exposed to Android through JNI and wrapped by
`S5DepthwiseHistoryRing` (`append`, `snapshotChronological`, `reset`,
`close`). The ring has a fixed position count; a render-state owner must
recreate it when the frame-block position count changes, as the original
renderer resets its cache when this dimension changes.

The source call site also shows that the seven-row optimized depthwise kernel
is selected when the kernel width and history-row count are both seven. The
current ring provides that chronological seven-row input, while VSM timing,
full block scheduling, model-descriptor wiring and the complete renderer
remain separate.

## Verification scope

The native integration test uses a single channel, seven positions, and a center-only depthwise filter so the convolution result is analytically known. It checks global mean/variance/epsilon, expansion weights, GELU formula, projection and residual affine result. This validates the composed scalar math against the recovered dataflow.

The function currently uses the explicitly recovered scalar GELU implementation FUN_18012ca50. The ISA-selected alternatives returned by FUN_18012cb50 are being analyzed separately; final ARM64 numerical matching must compare the chosen Android scalar/NEON backend against the authorized reference.

No real Megpoid V7 voice output has been generated by this milestone.
