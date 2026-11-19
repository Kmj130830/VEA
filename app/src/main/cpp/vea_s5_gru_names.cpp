#include "vea_s5_tensor.h"

#include <cstdio>
#include <cstring>

extern "C" VeaS5TensorStatus vea_s5_build_recurrent_parameter_name(
    const char* base,
    const char* member_suffix,
    int32_t layer_index,
    int reverse_direction,
    char* out,
    size_t out_capacity,
    size_t* out_required) {
    if (out_required == nullptr || base == nullptr || member_suffix == nullptr ||
        (reverse_direction != 0 && reverse_direction != 1)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    *out_required = 0;
    char index_text[16] = {};
    const int index_length = std::snprintf(index_text, sizeof(index_text), "%d", layer_index);
    if (index_length < 0 || static_cast<size_t>(index_length) >= sizeof(index_text)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    const size_t base_length = std::strlen(base);
    const size_t suffix_length = std::strlen(member_suffix);
    const size_t reverse_length = reverse_direction != 0 ? 8u : 0u;
    if (base_length > static_cast<size_t>(-1) - suffix_length ||
        base_length + suffix_length > static_cast<size_t>(-1) - static_cast<size_t>(index_length) ||
        base_length + suffix_length + static_cast<size_t>(index_length) >
            static_cast<size_t>(-1) - reverse_length - 1u) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    const size_t name_length = base_length + suffix_length +
        static_cast<size_t>(index_length) + reverse_length;
    *out_required = name_length + 1u;
    if (out == nullptr || out_capacity < *out_required) {
        return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;
    }

    size_t cursor = 0;
    std::memcpy(out + cursor, base, base_length);
    cursor += base_length;
    std::memcpy(out + cursor, member_suffix, suffix_length);
    cursor += suffix_length;
    std::memcpy(out + cursor, index_text, static_cast<size_t>(index_length));
    cursor += static_cast<size_t>(index_length);
    if (reverse_direction != 0) {
        std::memcpy(out + cursor, "_reverse", 8u);
        cursor += 8u;
    }
    out[cursor] = '\0';
    return VEA_S5_TENSOR_STATUS_OK;
}
