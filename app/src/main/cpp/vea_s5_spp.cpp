#include "vea_s5_tensor.h"

#include <cmath>
#include <bit>
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

extern "C" VeaS5TensorStatus vea_s5_spp_lookup_table_f64(
    const double* table, size_t table_count, size_t segment_count,
    double scale, double fallback_value, double x, double* out_value) {
    if (out_value == nullptr || !std::isfinite(scale) || scale < 0.0 ||
        !std::isfinite(fallback_value) || !std::isfinite(x)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (segment_count == std::numeric_limits<size_t>::max()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    if (segment_count > 0u && (table == nullptr || table_count < segment_count + 1u)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    const double scaled = std::fabs(x) * scale;
    if (!std::isfinite(scaled)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    if (segment_count == 0u || scaled >= static_cast<double>(segment_count)) {
        *out_value = fallback_value;
        return VEA_S5_TENSOR_STATUS_OK;
    }

    // FUN_180139870's conversion is floor(scaled) for non-negative inputs.
    const size_t index = static_cast<size_t>(std::floor(scaled));
    if (index >= segment_count || index + 1u >= table_count) {
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    }
    const double left = table[index];
    const double right = table[index + 1u];
    if (!std::isfinite(left) || !std::isfinite(right)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    const double fraction = scaled - static_cast<double>(index);
    const double result = (right - left) * fraction + left;
    if (!std::isfinite(result)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_value = result;
    return VEA_S5_TENSOR_STATUS_OK;
}
extern "C" VeaS5TensorStatus vea_s5_spp_fft_log2_floor_u32(
    uint32_t value, int32_t* out_index) {
    if (out_index == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_index = value == 0u
        ? -1
        : static_cast<int32_t>(std::bit_width(value) - 1u);
    return VEA_S5_TENSOR_STATUS_OK;
}
