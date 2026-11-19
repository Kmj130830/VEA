#include "vea_s5_tensor.h"

#include <cstddef>
#include <cstdint>
#include <limits>

namespace {

uint32_t ReflectS5Index(int64_t index, uint32_t width) {
    if (index < 0) return static_cast<uint32_t>(-index);
    if (index >= static_cast<int64_t>(width)) {
        return static_cast<uint32_t>(static_cast<int64_t>(width) * 2 - index - 2);
    }
    return static_cast<uint32_t>(index);
}

}  // namespace

extern "C" VeaS5TensorStatus vea_s5_depthwise_conv7x7_reflect_f32(
    const float* const input_rows[7],
    const float* weights_7x7,
    uint32_t width,
    float* output) {
    if (input_rows == nullptr || weights_7x7 == nullptr || output == nullptr || width < 7u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (width > static_cast<uint32_t>(std::numeric_limits<int32_t>::max())) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    for (size_t row = 0; row < 7u; ++row) {
        if (input_rows[row] == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    // This intentionally keeps the recovered row-major accumulation order.
    // No SIMD or fast-math reassociation is applied in this scalar baseline.
    for (uint32_t x = 0; x < width; ++x) {
        float sum = 0.0f;
        for (uint32_t ky = 0; ky < 7u; ++ky) {
            const float* row = input_rows[ky];
            const float* kernel_row = weights_7x7 + static_cast<size_t>(ky) * 7u;
            for (int32_t kx = 0; kx < 7; ++kx) {
                const int64_t source_x = static_cast<int64_t>(x) + kx - 3;
                const uint32_t reflected_x = ReflectS5Index(source_x, width);
                sum += row[reflected_x] * kernel_row[kx];
            }
        }
        output[x] = sum;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
