# S5API MRW_LNSTATS reduction primitive

Static decompilation of FUN_180130d90 shows a vector reduction over param_2 float values. It accumulates element-wise sums and squared sums independently, then writes:

- `*param_3 = sum(input[i])`
- `*param_4 = sum(input[i] * input[i])`

The MRW_LNSTATS_SIMD dispatcher selects this AVX2 routine when available and otherwise selects LAB_180130ce0. The scalar port added here reproduces the mathematical outputs, but not the AVX2 summation tree or its exact rounding order.

Files:
- vea_s5_stats.cpp
- C ABI vea_s5_reduce_sum_sumsq_f32
- JNI/Kotlin NativeS5TensorBridge.reduceSumAndSquares

Tests cover positive/negative inputs and single-element reduction. This is a statistics primitive, not the full layer-normalization formula; epsilon, reciprocal square root, affine scale/bias, and its graph call site remain to be traced.