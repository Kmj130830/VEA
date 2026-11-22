# S5API 2x interpolation and three-tap projection

Date: 2026-11-22

## Decompiled contract

`FUN_1800c72d0` expands each input channel from N positions to 2N
positions. For source position p, it emits two values:

```text
y[2*p]   = 0.75 * x[p] + 0.25 * x[max(p - 1, 0)]
y[2*p+1] = 0.75 * x[p] + 0.25 * x[min(p + 1, N - 1)]
```

This clamps the interpolation neighbor at the first and last source positions.
The function then prepares three temporal rows per input channel from the
expanded sequence: previous, center, and next. The previous row is zero at
the first expanded position; the next row is zero at the last expanded
position.

The GEMM dimensions are:

- M = output channels (`param_1[1]`);
- N = `2 * positions`;
- K = `3 * input_channels`.

The weight matrix is row-major `[output_channel][input_channel * 3]`,
the lowered sequence is `[input_channel * 3][2 * positions]`, and the
output is `[output_channel][2 * positions]`. One bias is added per output
channel across the full expanded sequence.

## Android port

`vea_s5_upsample2_conv3_projection_f32` implements the temporal
interpolation, zero-padded three-tap lowering, row-major GEMM, and bias
broadcast. The operation is exposed through JNI and
`NativeS5TensorBridge.applyUpsample2Conv3Projection()`.
The native test checks both endpoint interpolation and the three temporal
window rows using hand-calculated values.

## Integration boundary

The operator contract is independently ported; its exact parameter object
mapping and position in the Megpoid shared graph are not yet connected.
This change does not complete VTMG/VTB2 graph execution, Yamaha G2P, or the
spectral/waveform decoder. Singing synthesis must remain unavailable until
a real Megpoid PCM/WAV render is produced and validated.
