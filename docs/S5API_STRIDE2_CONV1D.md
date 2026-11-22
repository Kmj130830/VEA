# S5API stride-2 Conv1D projection

Date: 2026-11-22

## Decompiled contract

`FUN_1800c5440` is a stride-2 temporal projection that lowers its input
window into a matrix and invokes the row-major GEMM wrapper
`FUN_1800c3740`. The recovered dimensions are:

- input channels: `param_1[0]`;
- output channels: `param_1[1]`;
- temporal kernel width: `param_1[2]`;
- input position count: the integer at `param_2 + 0x1c`.

For odd kernel widths, the output count follows the original integer
expression:

```text
half = floor(kernel_width / 2)
output_positions = floor((2 * half - kernel_width + positions) / 2) + 1
```

Thus kernels wider than one produce `ceil(positions / 2)`. The width-one
case follows the literal source expression and produces
`floor(positions / 2) + 1`.

The window matrix is `[input_channel * kernel_tap][output_position]`.
For each output position `p` and kernel tap `t`, the source
position is `2*p + t - floor(kernel_width/2)`. Out-of-range source
positions are zero-filled. The weight matrix is row-major
`[output_channel][input_channel * kernel_width]` and GEMM executes
`M=output_channels, N=output_positions, K=input_channels*kernel_width`.
When the descriptor's bias flag is set, one output-channel bias is added to
every temporal position.

## Android port

`vea_s5_stride2_conv1d_projection_f32` implements the scalar lowering
and reuses the existing row-major GEMM primitive. The JNI entry point and
`NativeS5TensorBridge.applyStride2Conv1dProjection()` expose the same
contract to Kotlin. Native tests cover edge zero-padding, stride-two center
positions, bias broadcast, the width-one length expression, and invalid even
kernel widths.

## Integration boundary

This operator is now independently implemented and tested, but its runtime
VTMG/VTB2 tensor binding and exact place in the nine-layer Megpoid graph are
not yet established. It is not used to claim that the full forward graph or
audio renderer is complete. The adapter must still report synthesis as
unavailable until a real Megpoid render produces and validates PCM/WAV.
