#include "vea_s5_tensor.h"

#include <cstddef>
#include <cstdint>
#include <limits>

extern "C" VeaS5TensorStatus vea_s5_reduce_sum_sumsq_f32(
    const float* input, size_t count, float* out_sum, float* out_sum_squares) {
    if (input == nullptr || out_sum == nullptr || out_sum_squares == nullptr || count == 0) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (count > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    float sum = 0.0f;
    float sum_squares = 0.0f;
    for (size_t i = 0; i < count; ++i) {
        const float value = input[i];
        sum += value;
        sum_squares += value * value;
    }
    *out_sum = sum;
    *out_sum_squares = sum_squares;
    return VEA_S5_TENSOR_STATUS_OK;
}
