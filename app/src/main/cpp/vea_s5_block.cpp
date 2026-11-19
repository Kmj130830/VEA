#include "vea_s5_tensor.h"

#include <cmath>
#include <cstddef>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

namespace {
bool MulOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}
bool FiniteVector(const float* values, size_t count) {
    if (values == nullptr) return false;
    for (size_t i = 0; i < count; ++i) if (!std::isfinite(values[i])) return false;
    return true;
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_run_residual_dw_mlp_block_f32(
    const float* history_rows, const float* depthwise_weights,
    const float* expand_weights, const float* expanded_bias_a,
    const float* expanded_bias_b, const float* project_weights,
    const float* residual_input, const float* residual_scale,
    const float* residual_bias, float* output,
    size_t channels, size_t positions, size_t expanded_features, float epsilon) {
    if (history_rows == nullptr || depthwise_weights == nullptr || expand_weights == nullptr ||
        expanded_bias_a == nullptr || expanded_bias_b == nullptr || project_weights == nullptr ||
        residual_input == nullptr || residual_scale == nullptr || output == nullptr ||
        channels == 0u || positions < 7u || positions > std::numeric_limits<uint32_t>::max() || expanded_features == 0u ||
        !std::isfinite(epsilon) || epsilon < 0.0f) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t input_count=0, history_count=0, depth_weight_count=0, expanded_count=0,
           expand_weight_count=0, project_weight_count=0;
    if (MulOverflow(channels, positions, &input_count) ||
        MulOverflow(input_count, 7u, &history_count) ||
        MulOverflow(channels, 49u, &depth_weight_count) ||
        MulOverflow(expanded_features, positions, &expanded_count) ||
        MulOverflow(expanded_features, channels, &expand_weight_count) ||
        MulOverflow(channels, expanded_features, &project_weight_count)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    (void)history_count;
    (void)depth_weight_count;
    (void)expand_weight_count;
    (void)project_weight_count;

    if (!FiniteVector(history_rows, history_count) ||
        !FiniteVector(depthwise_weights, depth_weight_count) ||
        !FiniteVector(expand_weights, expand_weight_count) ||
        !FiniteVector(expanded_bias_a, expanded_features) ||
        !FiniteVector(expanded_bias_b, expanded_features) ||
        !FiniteVector(project_weights, project_weight_count) ||
        !FiniteVector(residual_input, input_count) ||
        !FiniteVector(residual_scale, channels) ||
        (residual_bias != nullptr && !FiniteVector(residual_bias, channels))) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::vector<float> depthwise(input_count, 0.0f);
        std::vector<float> expanded(expanded_count, 0.0f);
        // Preserve the residual source even when caller buffers alias or partially overlap.
        std::vector<float> residual_copy(residual_input, residual_input + input_count);
        for (size_t channel = 0; channel < channels; ++channel) {
            const float* rows[7];
            for (size_t row = 0; row < 7u; ++row) {
                rows[row] = history_rows + (channel * 7u + row) * positions;
            }
            const VeaS5TensorStatus conv_status = vea_s5_depthwise_conv7x7_reflect_f32(
                rows, depthwise_weights + channel * 49u, static_cast<uint32_t>(positions),
                depthwise.data() + channel * positions);
            if (conv_status != VEA_S5_TENSOR_STATUS_OK) return conv_status;
        }

        float sum=0.0f, sum_squares=0.0f, mean=0.0f, inverse_std=0.0f;
        VeaS5TensorStatus status = vea_s5_reduce_sum_sumsq_f32(
            depthwise.data(), input_count, &sum, &sum_squares);
        if (status != VEA_S5_TENSOR_STATUS_OK) return status;
        status = vea_s5_compute_mean_invstd_f32(
            sum, sum_squares, input_count, epsilon, &mean, &inverse_std);
        if (status != VEA_S5_TENSOR_STATUS_OK) return status;

        status = vea_s5_gemm_row_major_f32(
            expand_weights, depthwise.data(), expanded.data(),
            expanded_features, positions, channels,
            1.0f, channels, positions, 0.0f, positions);
        if (status != VEA_S5_TENSOR_STATUS_OK) return status;

        for (size_t feature = 0; feature < expanded_features; ++feature) {
            const float affine_bias = expanded_bias_a[feature] -
                inverse_std * mean * expanded_bias_b[feature];
            status = vea_s5_apply_gelu_f32(
                expanded.data() + feature * positions, positions, inverse_std, affine_bias);
            if (status != VEA_S5_TENSOR_STATUS_OK) return status;
        }

        status = vea_s5_gemm_row_major_f32(
            project_weights, expanded.data(), output,
            channels, positions, expanded_features,
            1.0f, expanded_features, positions, 0.0f, positions);
        if (status != VEA_S5_TENSOR_STATUS_OK) return status;

        for (size_t channel = 0; channel < channels; ++channel) {
            status = vea_s5_residual_affine_add_f32(
                output + channel * positions, residual_copy.data() + channel * positions,
                positions, residual_scale[channel],
                residual_bias == nullptr ? 0.0f : residual_bias[channel]);
            if (status != VEA_S5_TENSOR_STATUS_OK) return status;
        }
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (const std::length_error&) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
