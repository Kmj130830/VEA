#include "vea_s5_tensor.h"

#include <bit>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

namespace {
int Base64Value(unsigned char ch) {
    if (ch >= 'A' && ch <= 'Z') return static_cast<int>(ch - 'A');
    if (ch >= 'a' && ch <= 'z') return static_cast<int>(ch - 'a') + 26;
    if (ch >= '0' && ch <= '9') return static_cast<int>(ch - '0') + 52;
    if (ch == '+') return 62;
    if (ch == '/') return 63;
    return -1;
}

bool DecodeBase64(const char* text, size_t length, std::vector<uint8_t>* output) {
    if (output == nullptr || (length != 0 && text == nullptr)) return false;
    output->clear();

    size_t padding = 0;
    if (length != 0 && text[length - 1] == '=') {
        padding = 1;
        if (length >= 2 && text[length - 2] == '=') padding = 2;
    }
    if (padding != 0 && (length % 4u != 0u)) return false;
    const size_t remainder = length % 4u;
    if (padding == 0 && remainder == 1u) return false;

    for (size_t i = 0; i < length - padding; ++i) {
        if (Base64Value(static_cast<unsigned char>(text[i])) < 0) return false;
    }
    for (size_t i = length - padding; i < length; ++i) {
        if (text[i] != '=') return false;
    }

    const size_t full_groups = length / 4u;
    if (full_groups > std::numeric_limits<size_t>::max() / 3u) return false;
    size_t output_size = full_groups * 3u;
    if (padding > output_size) return false;
    output_size -= padding;
    if (padding == 0) {
        if (remainder == 2u) ++output_size;
        else if (remainder == 3u) {
            if (output_size > std::numeric_limits<size_t>::max() - 2u) return false;
            output_size += 2u;
        }
    }

    try {
        output->reserve(output_size);
        size_t cursor = 0;
        while (cursor + 4u <= length - padding) {
            const uint32_t a = static_cast<uint32_t>(Base64Value(static_cast<unsigned char>(text[cursor])));
            const uint32_t b = static_cast<uint32_t>(Base64Value(static_cast<unsigned char>(text[cursor + 1])));
            const uint32_t c = static_cast<uint32_t>(Base64Value(static_cast<unsigned char>(text[cursor + 2])));
            const uint32_t d = static_cast<uint32_t>(Base64Value(static_cast<unsigned char>(text[cursor + 3])));
            const uint32_t packed = (a << 18u) | (b << 12u) | (c << 6u) | d;
            output->push_back(static_cast<uint8_t>((packed >> 16u) & 0xffu));
            output->push_back(static_cast<uint8_t>((packed >> 8u) & 0xffu));
            output->push_back(static_cast<uint8_t>(packed & 0xffu));
            cursor += 4u;
        }

        if (padding == 1u) {
            if (cursor + 4u != length) return false;
            const int a = Base64Value(static_cast<unsigned char>(text[cursor]));
            const int b = Base64Value(static_cast<unsigned char>(text[cursor + 1]));
            const int c = Base64Value(static_cast<unsigned char>(text[cursor + 2]));
            if (a < 0 || b < 0 || c < 0 || text[cursor + 3] != '=') return false;
            output->push_back(static_cast<uint8_t>((a << 2) | (b >> 4)));
            output->push_back(static_cast<uint8_t>((b << 4) | (c >> 2)));
        } else if (padding == 2u) {
            if (cursor + 4u != length) return false;
            const int a = Base64Value(static_cast<unsigned char>(text[cursor]));
            const int b = Base64Value(static_cast<unsigned char>(text[cursor + 1]));
            if (a < 0 || b < 0 || text[cursor + 2] != '=' || text[cursor + 3] != '=') return false;
            output->push_back(static_cast<uint8_t>((a << 2) | (b >> 4)));
        } else if (remainder == 2u) {
            const int a = Base64Value(static_cast<unsigned char>(text[cursor]));
            const int b = Base64Value(static_cast<unsigned char>(text[cursor + 1]));
            if (a < 0 || b < 0) return false;
            output->push_back(static_cast<uint8_t>((a << 2) | (b >> 4)));
        } else if (remainder == 3u) {
            const int a = Base64Value(static_cast<unsigned char>(text[cursor]));
            const int b = Base64Value(static_cast<unsigned char>(text[cursor + 1]));
            const int c = Base64Value(static_cast<unsigned char>(text[cursor + 2]));
            if (a < 0 || b < 0 || c < 0) return false;
            output->push_back(static_cast<uint8_t>((a << 2) | (b >> 4)));
            output->push_back(static_cast<uint8_t>((b << 4) | (c >> 2)));
        }
    } catch (...) {
        return false;
    }
    return output->size() == output_size;
}

uint32_t ReadU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8u) |
        (static_cast<uint32_t>(p[2]) << 16u) |
        (static_cast<uint32_t>(p[3]) << 24u);
}
uint16_t ReadU16LE(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) |
        static_cast<uint16_t>(static_cast<uint16_t>(p[1]) << 8u);
}
float HalfToFloat(uint16_t half) {
    const uint32_t sign = static_cast<uint32_t>(half & 0x8000u) << 16u;
    const uint32_t exponent = (half >> 10u) & 0x1fu;
    uint32_t mantissa = half & 0x03ffu;
    uint32_t bits = sign;
    if (exponent == 0u) {
        if (mantissa != 0u) {
            int adjusted_exponent = -14;
            while ((mantissa & 0x0400u) == 0u) {
                mantissa <<= 1u;
                --adjusted_exponent;
            }
            mantissa &= 0x03ffu;
            bits |= static_cast<uint32_t>(adjusted_exponent + 127) << 23u;
            bits |= mantissa << 13u;
        }
    } else if (exponent == 0x1fu) {
        bits |= 0x7f800000u | (mantissa << 13u);
        if (mantissa != 0u) bits |= 0x00400000u;
    } else {
        bits |= (exponent + 112u) << 23u;
        bits |= mantissa << 13u;
    }
    return std::bit_cast<float>(bits);
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_decode_typed_float_array_with_f16_callback(
    VeaS5Arena* arena, const char* encoded, size_t encoded_size,
    VeaS5F16ToFloatCallback f16_callback, void* callback_context,
    float** out_values, size_t* out_count) {
    if (arena == nullptr || encoded == nullptr || out_values == nullptr || out_count == nullptr ||
        encoded_size < 4u) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_values = nullptr;
    *out_count = 0;

    const bool is_f32 = encoded[0] == '*' && encoded[1] == 'F' &&
        encoded[2] == '3' && encoded[3] == '2';
    const bool is_f16 = encoded[0] == '*' && encoded[1] == 'F' &&
        encoded[2] == '1' && encoded[3] == '6';
    if (!is_f32 && !is_f16) return VEA_S5_TENSOR_STATUS_TYPE_MISMATCH;

    std::vector<uint8_t> decoded;
    if (!DecodeBase64(encoded + 4, encoded_size - 4, &decoded))
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;

    const size_t scalar_bytes = is_f32 ? 4u : 2u;
    if (decoded.size() % scalar_bytes != 0u)
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    const size_t count = decoded.size() / scalar_bytes;

    // Run model-specific conversion before touching the arena. A callback error
    // therefore leaves the caller's arena unchanged and the outputs empty.
    std::vector<float> callback_values;
    if (is_f16 && f16_callback != nullptr) {
        try {
            callback_values.resize(count);
        } catch (...) {
            return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
        }
        for (size_t i = 0; i < count; ++i) {
            const uint16_t raw = ReadU16LE(decoded.data() + i * 2u);
            const VeaS5TensorStatus callback_status =
                f16_callback(raw, callback_context, &callback_values[i]);
            if (callback_status != VEA_S5_TENSOR_STATUS_OK) return callback_status;
        }
    }

    float* values = nullptr;
    const VeaS5TensorStatus status = vea_s5_arena_alloc_f32(arena, count, &values);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    if (is_f32) {
        for (size_t i = 0; i < count; ++i)
            values[i] = std::bit_cast<float>(ReadU32LE(decoded.data() + i * 4u));
    } else if (f16_callback != nullptr) {
        for (size_t i = 0; i < count; ++i) values[i] = callback_values[i];
    } else {
        for (size_t i = 0; i < count; ++i)
            values[i] = HalfToFloat(ReadU16LE(decoded.data() + i * 2u));
    }
    *out_values = values;
    *out_count = count;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_decode_typed_float_array(
    VeaS5Arena* arena, const char* encoded, size_t encoded_size,
    float** out_values, size_t* out_count) {
    return vea_s5_decode_typed_float_array_with_f16_callback(
        arena, encoded, encoded_size, nullptr, nullptr, out_values, out_count);
}

extern "C" VeaS5TensorStatus vea_s5_decode_typed_int32_array(
    VeaS5Arena* arena, const char* encoded, size_t encoded_size,
    int32_t** out_values, size_t* out_count) {
    if (arena == nullptr || encoded == nullptr || out_values == nullptr || out_count == nullptr ||
        encoded_size < 4u) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_values = nullptr;
    *out_count = 0;
    if (encoded[0] != '*' || encoded[1] != 'S' ||
        encoded[2] != '3' || encoded[3] != '2')
        return VEA_S5_TENSOR_STATUS_TYPE_MISMATCH;

    std::vector<uint8_t> decoded;
    if (!DecodeBase64(encoded + 4, encoded_size - 4, &decoded))
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    if (decoded.size() % 4u != 0u)
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;

    const size_t count = decoded.size() / 4u;
    float* raw = nullptr;
    const VeaS5TensorStatus status = vea_s5_arena_alloc_f32(arena, count, &raw);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;
    auto* values = reinterpret_cast<int32_t*>(raw);
    for (size_t i = 0; i < count; ++i)
        values[i] = std::bit_cast<int32_t>(ReadU32LE(decoded.data() + i * 4u));
    *out_values = values;
    *out_count = count;
    return VEA_S5_TENSOR_STATUS_OK;
}
