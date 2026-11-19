# S5API v0_naive matrix kernel port

Date: 2026-11-19

## Static evidence

The name-to-function table at image address 0x1801a09e0 maps v0_naive to wrapper FUN_180139990. That wrapper forwards eleven arguments to FUN_18013aa50.

The recovered pointer arithmetic and scalar accumulation match row-major SGEMM:

    M, N, K, alpha, A, lda, B, ldb, beta, C, ldc
    C[i,j] = alpha * sum(A[i,k] * B[k,j]) + beta * C[i,j]

A row starts at A + i*lda. The decompiled unrolled load uses offsets -2,-1,0,+1 from an intermediate pointer at A+2, so the actual four read addresses begin at A[0] with no header bytes. B is addressed as B[k*ldb+j]. C is addressed as C[i*ldc+j]. The scalar worker handles groups of four K elements and then a scalar tail. Its final update is beta*C + alpha*accumulator.

Other table labels (v1_tiling, v2_neon_unroll, v5_neon_simd, v8_tile_8x8, v14_packed_neon and AVX/SSE variants) map to distinct workers. Their memory/packing contracts are not assumed to be identical until checked.

## Android implementation

VEA implements the scalar baseline in vea_s5_gemm.cpp as vea_s5_gemm_row_major_f32, with JNI and Kotlin method NativeS5TensorBridge.gemmRowMajor.

- All strides count float elements.
- lda >= K, ldb >= N, ldc >= N.
- M and N must be nonzero; K=0 is supported as beta-only scaling.
- Address arithmetic and JNI array lengths are checked.
- The implementation is a scalar baseline, not an ARM64 NEON or cache-tiled kernel.

Native tests cover a strided 2x3 by 3x2 multiplication with nonzero alpha/beta and output padding, K tails from 1 through 9, K=0, and invalid arguments.

## Status

This is the first matrix-compute primitive ported from the named model-kernel table. It is not yet wired to the pre1/pre2, conv/conditioning, linear or recurrent model layers. No Megpoid V7 waveform has yet been generated.
