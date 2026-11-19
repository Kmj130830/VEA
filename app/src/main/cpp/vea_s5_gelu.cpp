#include "vea_s5_tensor.h"

#include <cmath>
#include <cstddef>

extern "C" VeaS5TensorStatus vea_s5_apply_gelu_f32(
    float* input, size_t count, float scale, float bias) {
    if (input == nullptr || count == 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t i = 0; i < count; ++i) {
        const float x = scale * input[i] + bias;
        const float cubic = x * x * x;
        const float polynomial = cubic * 0.044715f + x;
        const float exponent = -(polynomial * 1.5957692f);
        const float exp_value = std::exp(exponent);
        input[i] = (1.0f / (exp_value + 1.0f)) * x;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
