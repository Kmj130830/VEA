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
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_history_ring_copy_channel_major(
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

/*
 * Reconstructs the stride-2 Conv1D projection in S5API FUN_1800c5440.
 *
 * input: [input_channel][input_position]
 * weights: [output_channel][input_channel * kernel_tap], row-major
 * bias: optional [output_channel]
 * output: [output_channel][output_position]
 *
 * Odd kernel widths only. For kernel>=3, output_positions=ceil(positions/2).
 * The lowered window matrix uses [input_channel * kernel_tap][output_position],
 * samples input at 2*output_position + kernel_tap - floor(kernel_width/2),
 * and inserts zero for out-of-range input positions.
 */
/*
 * Reconstructs the 2x interpolation + three-tap projection in S5API
 * FUN_1800c72d0.
 *
 * input: [input_channel][position]
 * weights: [output_channel][input_channel * 3], row-major
 * bias: optional [output_channel]
 * output: [output_channel][2 * positions]
 *
 * Each source sample emits:
 *   y[2*p]   = 0.75*x[p] + 0.25*x[max(p-1, 0)]
 *   y[2*p+1] = 0.75*x[p] + 0.25*x[min(p+1, positions-1)]
 * The following 3-tap windows zero-pad outside this upsampled sequence.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_upsample2_conv3_projection_f32(
    const float* input, const float* weights, const float* bias, float* output,
    size_t input_channels, size_t output_channels, size_t positions);

VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_stride2_conv1d_projection_f32(
    const float* input, const float* weights, const float* bias, float* output,
    size_t input_channels, size_t output_channels, size_t positions,
    size_t kernel_width);

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

/*
 * Precomputes the expanded GELU affine vectors built by FUN_1800d4bf0.
 * expansion_weights: [expanded_features][channels]
 * block_vector_a/b: [channels]
 * expansion_bias and outputs: [expanded_features]
 *
 * Recovered mapping:
 *   expanded_bias_a = expansion_weights * block_vector_b + expansion_bias
 *   expanded_bias_b = expansion_weights * block_vector_a
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_prepare_c5d50_expansion_bias_f32(
    const float* expansion_weights, const float* block_vector_a,
    const float* block_vector_b, const float* expansion_bias,
    float* expanded_bias_a, float* expanded_bias_b,
    size_t channels, size_t expanded_features);

/*
 * Executes one VTB2 c5d50 block using the tensors as stored in the model.
 * This entry point derives the two expanded GELU affine vectors from
 * block_vector_a/b and expansion_bias before executing the block.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_run_vtb2_c5d50_block_f32(
    const float* history_rows, const float* depthwise_weights,
    const float* block_vector_a, const float* block_vector_b,
    const float* expansion_weights, const float* expansion_bias,
    const float* projection_weights, const float* projection_bias,
    const float* block_vector_c, const float* residual_input,
    float* output, size_t channels, size_t positions,
    size_t expanded_features, float epsilon);

/**
 * Ports the in-place circular buffer rotation used by FUN_180133fd0.
 * Positive shifts rotate left; negative shifts rotate right. Shifts are
 * normalized modulo count by this portable adapter.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_rotate_circular_f32(
    float* values, size_t count, int32_t shift);

/**
 * Ports FUN_180136150's periodic frequency-domain peak-weight function.
 * config[0..4] = {period, lowEdge, center, highEdge, amplitude}.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_peak_weight_f32(
    const float config[5], float coordinate, float* out_weight);

/**
 * Shared weighted argmax used by FUN_180135b90 (stride 1) and
 * FUN_180135670 (stride 2, magnitude in the first lane).
 * The first maximum wins ties, matching the original forward scan.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_find_weighted_peak_f32(
    const float* values, size_t value_count, size_t value_stride,
    size_t start_index, size_t end_index, float bin_spacing, float denominator,
    const float weight_config[5], size_t* out_peak_index, float* out_peak_score);

/**
 * Parabolic interpolation for one spectral peak from interleaved
 * [magnitude,phase] bins, following FUN_1801343c0.
 * Non-interior or non-local-maximum bins are returned without interpolation.
 * The phase interpolation unwraps the adjacent phase delta to [-pi,pi].
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_interpolate_peak_f32(
    const float* magnitude_phase, size_t bin_count, size_t peak_index,
    float* out_magnitude, float* out_phase);

/**
 * Ports FUN_180135490: deterministic xorshift phase assignment for SPP bins.
 * output_complex is interleaved [real,imag] and must contain count*2 floats.
 * state is four mutable uint32 words; edge_mode bit 0 selects whether the
 * final bin is emitted as a real-only boundary bin.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_generate_phase_noise_f32(
    const float* magnitudes, size_t count, uint8_t edge_mode,
    uint32_t state[4], float* output_complex, size_t output_float_count);

/**
 * Executes the recovered N=4 or N=8 leaf butterfly from FUN_18007d700 /
 * FUN_18007fca0. The buffer contains N interleaved float lanes representing
 * N/2 complex values. direction<0 selects FUN_18007d700; otherwise the
 * FUN_18007fca0 sign/order variant is used.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_small_leaf_f32(
    uint32_t fft_size, int32_t direction, float* interleaved, size_t value_count);

/**
 * Executes one recovered S5 complex FFT leaf for real FFT sizes 4, 8, 16 or 32.
 * The input buffer contains fft_size floats, i.e. fft_size/2 interleaved
 * complex samples. Sizes 16 and 32 consume the corresponding generated plan
 * coefficients beginning at coefficient[1]. Direction < 0 selects the
 * FUN_18007d700 output order; non-negative selects FUN_18007fca0.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_leaf_f32(
    uint32_t fft_size, int32_t direction,
    float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count);

/**
 * Executes the recovered mixed radix stage used by FUN_18007fff0.
 * fft_size is the interleaved float-lane count: 64 or 128. The stage
 * processes four contiguous 16- or 32-float subblocks, respectively.
 * The coefficient buffer is the size-indexed S5 plan's N/2 float table.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_radix_stage_f32(
    uint32_t fft_size, float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count);

/**
 * Executes the recovered 256/512-lane large radix stage from FUN_1800800c0.
 * fft_size counts float lanes in the interleaved complex buffer. mode is the
 * raw renderer direction byte normalized to 0/1; the final group changes
 * between the two source branches.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_large_radix_stage_f32(
    uint32_t fft_size, uint8_t mode, float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count);

/**
 * Portable real-FFT wrapper for SPP frame buffers.
 * direction < 0: real samples -> packed [DC, Nyquist, Re1, Im1, ...]
 * direction >= 0: packed spectrum -> N real samples, scaled by N/2.
 * Uses a radix-2 complex transform internally and preserves the source
 * wrapper's unnormalized inverse convention. This is mathematically
 * equivalent at the real-spectrum boundary, not a bit-exact port of the
 * vendor's specialized butterfly schedule.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_real_fft_f32(
    uint32_t fft_size, int32_t direction, float* frame, size_t frame_count);

/**
 * Builds one 0x18-byte S5 real-FFT plan record's two backing arrays.
 * fft_size must be a power of two >= 4. split_table stores the compact
 * stage/decomposition schedule; coefficients stores the recovered float
 * recurrence and terminal twiddle factors. Capacities are measured in items.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_build_plan_f32(
    uint32_t fft_size, uint32_t* split_table, size_t split_table_count,
    float* coefficients, size_t coefficient_count);

/**
 * Cubic segment interpolation used by the SPP pitch/phase curve sampler
 * FUN_1801314f0 through FUN_1801360c0. controls are [p0,p1,p2,p3].
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_cubic_segment_f32(
    float position, const float controls[4], float* out_value);

/**
 * Returns floor(log2(value)) for non-zero uint32 values, and -1 for zero.
 * This is the size-index helper FUN_18007a000 used by the real FFT wrapper.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_fft_log2_floor_u32(
    uint32_t value, int32_t* out_index);

/**
 * Table lookup/interpolation used by FUN_180139870.
 * scaled = abs(x) * scale; index = floor(scaled) for non-negative inputs.
 * If index < segment_count, interpolate table[index]..table[index+1].
 * Otherwise return fallback_value. table_count must cover segment_count+1.
 */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_lookup_table_f64(
    const double* table, size_t table_count, size_t segment_count,
    double scale, double fallback_value, double x, double* out_value);

/** Ports the frame-wide scalar normalization in FUN_1801397d0. */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_spp_scale_buffer_f32(
    float* values, size_t count, float scale);

/* Ports the MRW_LNSTATS reduction output: sum(input) and sum(input * input). */
VEA_S5_TENSOR_API VeaS5TensorStatus vea_s5_reduce_sum_sumsq_f32(
    const float* input, size_t count, float* out_sum, float* out_sum_squares);

#ifdef __cplusplus
}
#endif
