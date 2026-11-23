#include "vea_s5_tensor.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

extern "C" VeaS5TensorStatus vea_s5_spp_rotate_circular_f32(
    float* values, size_t count, int32_t shift) {
    if (count == 0u) return VEA_S5_TENSOR_STATUS_OK;
    if (values == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    if (count > static_cast<size_t>(std::numeric_limits<int64_t>::max())) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    for (size_t index = 0; index < count; ++index) {
        if (!std::isfinite(values[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    if (count == 1u) return VEA_S5_TENSOR_STATUS_OK;
    const int64_t length = static_cast<int64_t>(count);
    int64_t left_shift = static_cast<int64_t>(shift) % length;
    if (left_shift < 0) left_shift += length;
    if (left_shift == 0) return VEA_S5_TENSOR_STATUS_OK;

    try {
        std::vector<float> rotated(count);
        const size_t offset = static_cast<size_t>(left_shift);
        const size_t first_count = count - offset;
        for (size_t index = 0; index < first_count; ++index) {
            rotated[index] = values[index + offset];
        }
        for (size_t index = 0; index < offset; ++index) {
            rotated[first_count + index] = values[index];
        }
        for (size_t index = 0; index < count; ++index) {
            values[index] = rotated[index];
        }
        return VEA_S5_TENSOR_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    }
}

extern "C" VeaS5TensorStatus vea_s5_spp_scale_buffer_f32(
    float* values, size_t count, float scale) {
    if (count == 0u) return VEA_S5_TENSOR_STATUS_OK;
    if (values == nullptr || !std::isfinite(scale)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    // Validate the entire operation before mutating the input buffer.
    for (size_t index = 0; index < count; ++index) {
        if (!std::isfinite(values[index]) || !std::isfinite(values[index] * scale)) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < count; ++index) {
        values[index] *= scale;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
