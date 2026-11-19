# Banked linear projection from S5API FUN_1800c6390

Date: 2026-11-19

## Evidence in the render path

FUN_1800c7ad0, the model execution loop, invokes FUN_1800c6390 for the ordinary branch of its projection stage. The function's call to the generic backend dispatcher has the same eleven SGEMM arguments as the v0_naive table kernel:

- M = param1[1], output feature count
- N = *(int*)(param2 + 0x1c), position count
- K = param1[0], input feature count
- alpha = 1
- A = the weight matrix pointer selected from the per-bank 0x18-byte descriptor list
- lda = K
- B = *param2, input tensor
- ldb = N
- beta = 0
- C = the temporary output buffer
- ldc = N

The outer loop runs param1[2] times and selects a different matrix descriptor each time. After each GEMM, the temporary [outputFeature][position] matrix is scattered into the final [outputFeature][position][bank] layout. When param1[3] is set, the function adds the shared per-output-feature bias across every position and bank.

This directly supports a banked linear projection operation. It is distinct from merely exposing GEMM: it reproduces the repeated-bank scheduling, output interleave and bias broadcast visible in FUN_1800c6390.

## Android native port

VEA adds vea_s5_banked_linear_projection_f32 and a JNI/Kotlin entry point.

- weights: [bank][outputFeature][inputFeature], with explicit weight row and bank strides
- input: [inputFeature][position]
- output: [outputFeature][position][bank]
- optional bias: [outputFeature]

For each bank, the native code invokes the previously ported SGEMM with alpha=1 and beta=0, scatters the temporary result, and then adds the bias vector to the output feature rows.

Tests cover two banks with different weight matrices, interleaved output order, bias broadcast, padded weight rows and banks, and invalid dimensions/stride.

## Remaining integration work

The decompiled Windows object uses 0x18-byte descriptors and runtime-owned pointers. The Android API currently accepts a flattened bank-major weight buffer and explicit strides. The complete V7 loader still needs to map each runtime descriptor to the corresponding packed weight storage and confirm the input tensor's full ownership/lifetime rules. The current function is a verified operator port, not a complete model file loader or singing renderer.
