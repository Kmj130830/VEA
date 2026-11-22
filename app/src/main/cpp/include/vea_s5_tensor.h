#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
  #if defined(VEA_S5_TENSOR_BUILD)
    #define VEA_S5_TENSOR_API __declspec(dllexport)
  #else
    #define VEA_S5_TENSOR_API __declspec(dllimport)
  #endif
#else
  #define VEA_S5_TENSOR_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define VEA_S5_TENSOR_ABI_VERSION 0x00010000u
#define VEA_S5_ARENA_ALIGNMENT 32u
#define VEA_S5_MATRIX_AXIS0_ALIGNMENT 8u
#define VEA_S5_MATRIX_DESCRIPTOR_SIZE 24u

typedef struct VeaS5Arena VeaS5Arena;

/* Stateful chronological block history used by FUN_1800c67e0. */
typedef struct VeaS5HistoryRing VeaS5HistoryRing;

/*
 * Recovered 0x18-byte S5API matrix descriptor.
 * axis0 is stored at +0, axis1 at +4, padded_axis0 at +8,
 * and the float32 data pointer at +0x10.
 *
 * Axis names are intentionally neutral until the surrounding model graph
 * establishes whether they represent input channels, output channels,
 * time, or another semantic dimension.
 */
typedef struct VeaS5MatrixDescriptor {
    uint32_t axis0;
    uint32_t axis1;
    uint32_t padded_axis0;
    uint32_t reserved;
    float* values;
} VeaS5MatrixDescriptor;

typedef enum VeaS5TensorStatus {
    VEA_S5_TENSOR_STATUS_OK = 0,
    VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT = 1,
    VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY = 2,
    VEA_S5_TENSOR_STATUS_ARENA_EXHAUSTED = 3,
    VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW = 4,
    VEA_S5_TENSOR_STATUS_MALFORMED_DATA = 5,
    VEA_S5_TENSOR_STATUS_TYPE_MISMATCH = 6,
    VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL = 7
} VeaS5TensorStatus;

VEA_S5_TENSOR_API uint32_t vea_s5_tensor_abi_version(void);

VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_history_ring_create(
    size_t channels, size_t positions, size_t history_rows,
    VeaS5HistoryRing** out_ring);
VEA_S5_TENSOR_API void vea_s5_history_ring_destroy(VeaS5HistoryRing* ring);
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_history_ring_reset(VeaS5HistoryRing* ring);
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_history_ring_append(
    VeaS5HistoryRing* ring, const float* block, size_t block_count);
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_history_ring_copy_chronological(
    const VeaS5HistoryRing* ring, float* output, size_t output_count);
VEA_S5_TENSOR_API size_t vea_s5_history_ring_block_size(const VeaS5HistoryRing* ring);
VEA_S5_TENSOR_API size_t vea_s5_history_ring_row_count(const VeaS5HistoryRing* ring);

VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_arena_create(
    size_t capacity_bytes,
    VeaS5Arena** out_arena);

VEA_S5_TENSOR_API void vea_s5_arena_destroy(VeaS5Arena* arena);
VEA_S5_TENSOR_API size_t vea_s5_arena_capacity(const VeaS5Arena* arena);
VEA_S5_TENSOR_API size_t vea_s5_arena_used(const VeaS5Arena* arena);
VEA_S5_TENSOR_API void vea_s5_arena_reset(VeaS5Arena* arena);

/* Allocates count float32 values. The returned address is 32-byte aligned. */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_arena_alloc_f32(
    VeaS5Arena* arena,
    size_t count,
    float** out_values);

/* Allocates count 0x18-byte matrix descriptor records. */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_arena_alloc_matrix_descriptors(
    VeaS5Arena* arena,
    size_t count,
    VeaS5MatrixDescriptor** out_descriptors);

/*
 * Converts a typed double-array payload into contiguous float32 values.
 * This corresponds to the recovered node-tag-4 conversion path. The parser
 * that validates the source node/type is deliberately outside this function.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_convert_f64_array_to_f32(
    VeaS5Arena* arena,
    const double* source,
    size_t count,
    float** out_values);

/*
 * Reconstructs the recovered matrix-bank layout from FUN_180064670.
 *
 * Input layout:
 *   source[((axis0_index * axis1_count + axis1_index) * matrix_count) + matrix]
 *
 * Output per matrix:
 *   descriptor[matrix].values[axis1_index * padded_axis0 + axis0_index]
 *
 * padded_axis0 = align_up(axis0_count, 8). Padding is initialized to zero.
 * The descriptor table and every float region are 32-byte aligned allocations.
 * All arena allocations roll back atomically if this function fails.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_pack_matrix_bank_f32(
    VeaS5Arena* arena,
    const float* source,
    uint32_t axis0_count,
    uint32_t axis1_count,
    uint32_t matrix_count,
    VeaS5MatrixDescriptor** out_descriptors);

/*
 * Reconstructs FUN_18005df50: transposes contiguous [axis0][axis1] to
 * [axis1][align_up(axis0, 8)].
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_pack_transposed_f32(
    VeaS5Arena* arena,
    const float* source,
    uint32_t axis0_count,
    uint32_t axis1_count,
    VeaS5MatrixDescriptor** out_descriptor);

/*
 * Reconstructs FUN_180065510: a single contiguous [axis1][axis0] matrix
 * is copied to [axis1][align_up(axis0, 8)]. Unlike pack_matrix_bank_f32,
 * this path does not distribute an interleaved matrix-bank dimension.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_pack_row_major_f32(
    VeaS5Arena* arena,
    const float* source,
    uint32_t axis0_count,
    uint32_t axis1_count,
    VeaS5MatrixDescriptor** out_descriptor);

/* Decode S5API tagged Base64 arrays: float32, float16 and signed int32 types. */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_decode_typed_float_array(
    VeaS5Arena* arena, const char* encoded, size_t encoded_size,
    float** out_values, size_t* out_count);
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_decode_typed_int32_array(
    VeaS5Arena* arena, const char* encoded, size_t encoded_size,
    int32_t** out_values, size_t* out_count);

/*
 * Ports the S5API ACT operator body in FUN_18012d460.
 * planes contains count floats followed by count gate floats; both halves
 * are clamped to [-8, 8], and the first half is replaced in place.
 * mode == 0: sigmoid(second) * tanh(first); mode != 0: tanh(second) * sigmoid(first).
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_apply_gated_activation_f32(
    float* planes, size_t count, int mode);


/*
 * Ports the S5API scalar GELU kernel FUN_18012ca50.
 * x = scale * input[i] + bias
 * input[i] = x / (1 + exp(-((x^3 * 0.044715 + x) * 1.5957692)))
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_apply_gelu_f32(
    float* input, size_t count, float scale, float bias);

/*
 * Portable scalar equivalent of S5API FUN_18012f280 (MRW_DWCONV_SIMD fallback).
 * Seven input rows are convolved with a row-major 7x7 kernel; output width
 * equals input width. Horizontal borders use reflect-without-edge-repeat
 * indexing: -1 -> 1 and width -> width - 2.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_depthwise_conv7x7_reflect_f32(
    const float* const input_rows[7],
    const float* weights_7x7,
    uint32_t width,
    float* output);


/*
 * Builds the recurrent parameter name used by FUN_180024a90:
 * base + member_suffix + decimal layer index + optional "_reverse".
 * member_suffix is normally ".weight_ih_l", ".weight_hh_l",
 * ".bias_ih_l", or ".bias_hh_l".
 * out_required receives required byte count including the NUL terminator.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_build_recurrent_parameter_name(
    const char* base,
    const char* member_suffix,
    int32_t layer_index,
    int reverse_direction,
    char* out,
    size_t out_capacity,
    size_t* out_required);


/*
 * Ports the scalar residual affine operator FUN_1800d8720.
 * In-place result: dst[i] = (dst[i] + bias) * scale + residual[i].
 * dst and residual may be identical; partial overlap is unsupported.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_residual_affine_add_f32(
    float* dst, const float* residual, size_t count, float scale, float bias);

/* Computes the recovered S5API normalization statistics from sum and sumsq.
 * variance = max(sum_squares/count - mean*mean, 0) + epsilon.
 * Returns mean and reciprocal standard deviation. */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_compute_mean_invstd_f32(
    float sum, float sum_squares, size_t count, float epsilon,
    float* out_mean, float* out_inv_std);

/*
 * Scalar row-major SGEMM equivalent of S5API v0_naive:
 * M,N,K,alpha,A,lda,B,ldb,beta,C,ldc.
 * A=[M][K], B=[K][N], C=[M][N]; strides count float elements.
 * lda>=K, ldb>=N, ldc>=N; M,N are non-zero; K may be zero.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_gemm_row_major_f32(
    const float* a, const float* b, float* c,
    size_t m, size_t n, size_t k,
    float alpha, size_t lda, size_t ldb, float beta, size_t ldc);

/*
 * Banked linear projection matching the observable dataflow of FUN_1800c6390.
 * weights are [bank][out_feature][input_feature] with explicit row/bank strides.
 * input is [input_feature][position].
 * output is [out_feature][position][bank] (bank-interleaved positions).
 * bias is optional and contains one value per output feature.
 */
/*
 * VEA-side adapter: converts VTMG [bank][input][paddedOutput] storage into
 * the row-major [bank][output][input] A layout consumed by v0_naive GEMM.
 * This is not a claim that Yamaha performs this conversion at this boundary.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_vtmg_bank_to_v0_naive_a_f32(
    const float* packed_vtmg, size_t packed_count,
    size_t output_features, size_t input_features,
    size_t padded_output_features, size_t banks,
    float* gemm_a, size_t gemm_a_count);

VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_banked_linear_projection_f32(
    const float* weights, size_t weight_row_stride, size_t weight_bank_stride,
    const float* input, const float* bias, float* output,
    size_t input_features, size_t output_features, size_t positions, size_t banks);

/*
 * Scalar S5API residual depthwise/MLP block from FUN_1800c5d50.
 *
 * history_rows: [channels][7][positions]
 * depthwise_weights: [channels][7][7]
 * expand_weights: [expanded_features][channels]
 * expanded_bias_a/b: [expanded_features]
 * project_weights: [channels][expanded_features]
 * residual_input: [channels][positions]
 * residual_scale/bias: [channels]; residual_bias may be null.
 * output: [channels][positions]
 *
 * Statistics are global across the depthwise [channels][positions] tensor.
 * The GELU affine stage is invStd*x + biasA[f] - invStd*mean*biasB[f].
 * The final residual update is (projected + residualBias[c])*scale[c] + residualInput.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_run_residual_dw_mlp_block_f32(
    const float* history_rows, const float* depthwise_weights,
    const float* expand_weights, const float* expanded_bias_a,
    const float* expanded_bias_b, const float* project_weights,
    const float* residual_input, const float* residual_scale,
    const float* residual_bias, float* output,
    size_t channels, size_t positions, size_t expanded_features,
    float epsilon);

/* Ports the MRW_LNSTATS reduction output: sum(input) and sum(input * input). */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_reduce_sum_sumsq_f32(
    const float* input, size_t count, float* out_sum, float* out_sum_squares);

#ifdef __cplusplus
}
#endif
