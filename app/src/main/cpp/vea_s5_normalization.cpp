#include "vea_s5_tensor.h"

#include <cmath>
#include <cstddef>

extern "C" VeaS5TensorStatus vea_s5_compute_mean_invstd_f32(
    float sum, float sum_squares, size_t count, float epsilon,
    float* out_mean, float* out_inv_std) {
    if (count == 0u || out_mean == nullptr || out_inv_std == nullptr ||
        !std::isfinite(sum) || !std::isfinite(sum_squares) ||
        !std::isfinite(epsilon) || epsilon < 0.0f) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    // Keep the same float32 operation order observed in FUN_1800c5d50.
    const float count_f = static_cast<float>(count);
    const float mean = sum / count_f;
    float variance = sum_squares / count_f - mean * mean;
    if (variance <= 0.0f) variance = 0.0f;
    variance += epsilon;
    const float standard_deviation = std::sqrt(variance);
    const float inverse_standard_deviation = 1.0f / standard_deviation;

    *out_mean = mean;
    *out_inv_std = inverse_standard_deviation;
    return VEA_S5_TENSOR_STATUS_OK;
}
