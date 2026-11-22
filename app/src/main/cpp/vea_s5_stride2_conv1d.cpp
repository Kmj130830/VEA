#include "vea_s5_tensor.h"

#include <cstddef>
#include <cstdint>
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

extern "C" VeaS5TensorStatus vea_s5_stride2_conv1d_projection_f32(
    const float* input,
    const float* weights,
    const float* bias,
    float* output,
    size_t input_channels,
    size_t output_channels,
    size_t positions,
    size_t kernel_width) {
    if (input == nullptr || weights == nullptr || output == nullptr ||
        input_channels == 0u || output_channels == 0u || positions == 0u ||
        kernel_width == 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    // Match FUN_1800c5440's output-length expression:
    // ((floor(kernel/2)*2 - kernel + positions) / 2) + 1.
    // Exact integer form of ((2*floor(kernel/2)-kernel+positions)/2)+1.
    // Even K: floor(positions/2)+1; odd K: floor((positions-1)/2)+1.
    const size_t output_positions =
        (positions - (kernel_width & 1u)) / 2u + 1u;
    const size_t radius = kernel_width / 2u;
    const uint64_t signed_max = static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    if (kernel_width > signed_max ||
        output_positions - 1u > (signed_max - (kernel_width - 1u)) / 2u) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    size_t input_count = 0u;
    size_t inner_features = 0u;
    size_t weight_count = 0u;
    size_t window_count = 0u;
    size_t output_count = 0u;
    if (MultiplyOverflow(input_channels, positions, &input_count) ||
        MultiplyOverflow(input_channels, kernel_width, &inner_features) ||
        MultiplyOverflow(output_channels, inner_features, &weight_count) ||
        MultiplyOverflow(inner_features, output_positions, &window_count) ||
        MultiplyOverflow(output_channels, output_positions, &output_count)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    (void)input_count;
    (void)weight_count;

    // The renderer builds [inputChannel * kernelTap][outputPosition] with
    // a two-sample stride and explicit zero padding outside the input range.
    try {
        std::vector<float> windows(window_count, 0.0f);
        for (size_t channel = 0; channel < input_channels; ++channel) {
            for (size_t tap = 0; tap < kernel_width; ++tap) {
                const size_t row = channel * kernel_width + tap;
                for (size_t out_pos = 0; out_pos < output_positions; ++out_pos) {
                    const int64_t input_pos =
                        static_cast<int64_t>(out_pos * 2u) +
                        static_cast<int64_t>(tap) -
                        static_cast<int64_t>(radius);
                    if (input_pos >= 0 && static_cast<uint64_t>(input_pos) < positions) {
                        windows[row * output_positions + out_pos] =
                            input[channel * positions + static_cast<size_t>(input_pos)];
                    }
                }
            }
        }

        const VeaS5TensorStatus gemm_status = vea_s5_gemm_row_major_f32(
            weights,
            windows.data(),
            output,
            output_channels,
            output_positions,
            inner_features,
            1.0f,
            inner_features,
            output_positions,
            0.0f,
            output_positions);
        if (gemm_status != VEA_S5_TENSOR_STATUS_OK) return gemm_status;

        if (bias != nullptr) {
            for (size_t channel = 0; channel < output_channels; ++channel) {
                float* row = output + channel * output_positions;
                for (size_t position = 0; position < output_positions; ++position) {
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
