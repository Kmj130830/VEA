#include "vea_s5_tensor.h"

#include <cstddef>

extern "C" VeaS5TensorStatus vea_s5_residual_affine_add_f32(
    float* dst, const float* residual, size_t count, float scale, float bias) {
    if (dst == nullptr || residual == nullptr || count == 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t i = 0; i < count; ++i) {
        const float value = dst[i];
        const float skip = residual[i];
        dst[i] = (value + bias) * scale + skip;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
