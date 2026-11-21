# S5API dense projection — FUN_1800c5260

Date: 2026-11-21

## Recovered operator call

The decompilation of FUN_1800c5260 shows one ordinary row-major GEMM followed
by an optional per-output-feature bias broadcast.

The descriptor fields are used as follows:

| Field | Meaning |
|---|---|
| param_1[0] | Input feature count K |
| param_1[1] | Output feature count M |
| byte at param_1 + 0x08 | Bias-enable flag |
| pointer pair at param_1 + 0x10 | Weight matrix descriptor |
| pointer pair at param_1 + 0x20 | Bias vector descriptor |

The input descriptor stores its position count N at byte offset +0x1c.
The GEMM call uses:

- M = output feature count;
- N = position count;
- K = input feature count;
- A = row-major weight matrix [M,K];
- B = input feature tensor [K,N];
- alpha = 1 and beta = 0;
- C = output tensor [M,N].

When the descriptor's bias flag is nonzero, each output-feature bias is added
to every position in that output row.

## Android port

VEA adds S5MegpoidDenseProjection. The object validates the weight matrix,
optional bias and input dimensions, then calls the common S5MegpoidGemmKernel
interface. The production backend reuses NativeS5TensorBridge.gemmRowMajor;
the scalar reference backend allows unit tests to verify row/column ordering
without JNI.

Tests cover a two-output-feature projection with bias, a projection without
bias, and invalid matrix/bias/input lengths.

## Integration boundary

This establishes the generic operator contract and provides a reusable
Android implementation. It does not identify which named VTMG layer or
runtime property supplies the descriptor in the AI Megpoid graph. No model
weights are automatically assigned to this operator, and this primitive does
not by itself produce audio.
