#include "vea_s5_tensor.h"

#include <cstddef>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

namespace {
bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_upsample2_conv3_projection_f32(
    const float* input,
    const float* weights,
    const float* bias,
    float* output,
    size_t input_channels,
    size_t output_channels,
    size_t positions) {
    if (input == nullptr || weights == nullptr || output == nullptr ||
        input_channels == 0u || output_channels == 0u || positions == 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t input_count = 0u;
    size_t upsampled_positions = 0u;
    size_t upsampled_count = 0u;
    size_t inner_features = 0u;
    size_t window_count = 0u;
    size_t weight_count = 0u;
    size_t output_count = 0u;
    if (MultiplyOverflow(input_channels, positions, &input_count) ||
        MultiplyOverflow(positions, 2u, &upsampled_positions) ||
        MultiplyOverflow(input_channels, upsampled_positions, &upsampled_count) ||
        MultiplyOverflow(input_channels, 3u, &inner_features) ||
        MultiplyOverflow(inner_features, upsampled_positions, &window_count) ||
        MultiplyOverflow(output_channels, inner_features, &weight_count) ||
        MultiplyOverflow(output_channels, upsampled_positions, &output_count)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    (void)input_count;
    (void)weight_count;

    // FUN_1800c72d0 first makes two 0.75/0.25 interpolates for each source
    // sample, clamping the neighbor at either endpoint to the endpoint value.
    // It then lowers a three-tap, stride-one projection into a GEMM B matrix.
    try {
        std::vector<float> upsampled(upsampled_count, 0.0f);
        std::vector<float> windows(window_count, 0.0f);

        for (size_t channel = 0; channel < input_channels; ++channel) {
            const float* source = input + channel * positions;
            float* destination = upsampled.data() + channel * upsampled_positions;
            for (size_t position = 0; position < positions; ++position) {
                const float current = source[position];
                const float left = source[position == 0u ? 0u : position - 1u];
                const float right = source[position + 1u < positions ? position + 1u : positions - 1u];
                destination[position * 2u] = current * 0.75f + left * 0.25f;
                destination[position * 2u + 1u] = current * 0.75f + right * 0.25f;
            }

            // The original builds [channel][previous, center, next][2*N].
            // Missing previous/next samples at the two ends are zero-filled.
            const size_t channel_window_base = channel * 3u * upsampled_positions;
            for (size_t position = 0; position < upsampled_positions; ++position) {
                windows[channel_window_base + position] =
                    position == 0u ? 0.0f : destination[position - 1u];
                windows[channel_window_base + upsampled_positions + position] = destination[position];
                windows[channel_window_base + 2u * upsampled_positions + position] =
                    position + 1u < upsampled_positions ? destination[position + 1u] : 0.0f;
            }
        }

        const VeaS5TensorStatus gemm_status = vea_s5_gemm_row_major_f32(
            weights, windows.data(), output,
            output_channels, upsampled_positions, inner_features,
            1.0f, inner_features, upsampled_positions, 0.0f, upsampled_positions);
        if (gemm_status != VEA_S5_TENSOR_STATUS_OK) return gemm_status;

        if (bias != nullptr) {
            for (size_t channel = 0; channel < output_channels; ++channel) {
                float* row = output + channel * upsampled_positions;
                for (size_t position = 0; position < upsampled_positions; ++position) {
                    row[position] += bias[channel];
                }
            }
        }
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (const std::length_error&) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
