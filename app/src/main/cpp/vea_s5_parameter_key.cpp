#include "vea_s5_tensor.h"

#include <array>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace {

constexpr std::array<uint8_t, 24> kS5ParameterKeySuffix = {
    0x69, 0x23, 0x33, 0x65, 0x2e, 0x63, 0x5a, 0x54,
    0x25, 0x35, 0x6b, 0x39, 0x6a, 0x32, 0x64, 0x2c,
    0x32, 0x5a, 0x72, 0x53, 0x2f, 0x2b, 0x2f, 0x32
};

constexpr std::array<uint32_t, 64> kMd5Constants = {
    0xd76aa478u, 0xe8c7b756u, 0x242070dbu, 0xc1bdceeeu,
    0xf57c0fafu, 0x4787c62au, 0xa8304613u, 0xfd469501u,
    0x698098d8u, 0x8b44f7afu, 0xffff5bb1u, 0x895cd7beu,
    0x6b901122u, 0xfd987193u, 0xa679438eu, 0x49b40821u,
    0xf61e2562u, 0xc040b340u, 0x265e5a51u, 0xe9b6c7aau,
    0xd62f105du, 0x02441453u, 0xd8a1e681u, 0xe7d3fbc8u,
    0x21e1cde6u, 0xc33707d6u, 0xf4d50d87u, 0x455a14edu,
    0xa9e3e905u, 0xfcefa3f8u, 0x676f02d9u, 0x8d2a4c8au,
    0xfffa3942u, 0x8771f681u, 0x6d9d6122u, 0xfde5380cu,
    0xa4beea44u, 0x4bdecfa9u, 0xf6bb4b60u, 0xbebfbc70u,
    0x289b7ec6u, 0xeaa127fau, 0xd4ef3085u, 0x04881d05u,
    0xd9d4d039u, 0xe6db99e5u, 0x1fa27cf8u, 0xc4ac5665u,
    0xf4292244u, 0x432aff97u, 0xab9423a7u, 0xfc93a039u,
    0x655b59c3u, 0x8f0ccc92u, 0xffeff47du, 0x85845dd1u,
    0x6fa87e4fu, 0xfe2ce6e0u, 0xa3014314u, 0x4e0811a1u,
    0xf7537e82u, 0xbd3af235u, 0x2ad7d2bbu, 0xeb86d391u
};

constexpr std::array<uint32_t, 64> kMd5Shifts = {
    7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
    5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
    4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
    6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21
};

uint32_t RotateLeft(uint32_t value, uint32_t shift) {
    return (value << shift) | (value >> (32u - shift));
}

uint32_t ReadU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8u) |
        (static_cast<uint32_t>(p[2]) << 16u) |
        (static_cast<uint32_t>(p[3]) << 24u);
}

void Md5Block(const uint8_t* block, std::array<uint32_t, 4>* state) {
    std::array<uint32_t, 16> words{};
    for (size_t i = 0; i < words.size(); ++i)
        words[i] = ReadU32LE(block + i * 4u);

    uint32_t a = (*state)[0];
    uint32_t b = (*state)[1];
    uint32_t c = (*state)[2];
    uint32_t d = (*state)[3];

    for (uint32_t i = 0; i < 64; ++i) {
        uint32_t f = 0;
        uint32_t g = 0;
        if (i < 16u) {
            f = (b & c) | (~b & d);
            g = i;
        } else if (i < 32u) {
            f = (d & b) | (~d & c);
            g = (5u * i + 1u) & 15u;
        } else if (i < 48u) {
            f = b ^ c ^ d;
            g = (3u * i + 5u) & 15u;
        } else {
            f = c ^ (b | ~d);
            g = (7u * i) & 15u;
        }

        const uint32_t next_d = c;
        const uint32_t next_c = b;
        const uint32_t sum = a + f + kMd5Constants[i] + words[g];
        const uint32_t next_b = b + RotateLeft(sum, kMd5Shifts[i]);
        a = d;
        b = next_b;
        c = next_c;
        d = next_d;
    }

    (*state)[0] += a;
    (*state)[1] += b;
    (*state)[2] += c;
    (*state)[3] += d;
}

VeaS5TensorStatus ComputeS5KeyWords(
    const uint8_t* name_bytes,
    size_t name_size,
    std::array<uint32_t, 4>* out_state) {
    if (out_state == nullptr || (name_size != 0 && name_bytes == nullptr))
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;

    constexpr size_t kSuffixSize = kS5ParameterKeySuffix.size();
    if (name_size > std::numeric_limits<size_t>::max() - kSuffixSize)
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    const size_t message_size = name_size + kSuffixSize;
    if (message_size > std::numeric_limits<uint64_t>::max() / 8u)
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    if (message_size > std::numeric_limits<size_t>::max() - 72u)
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    const size_t padded_size = ((message_size + 9u + 63u) / 64u) * 64u;

    try {
        std::vector<uint8_t> message(padded_size, 0);
        if (name_size != 0) std::memcpy(message.data(), name_bytes, name_size);
        std::memcpy(message.data() + name_size,
                    kS5ParameterKeySuffix.data(), kSuffixSize);
        message[message_size] = 0x80u;

        const uint64_t bit_length = static_cast<uint64_t>(message_size) * 8u;
        for (size_t i = 0; i < 8u; ++i)
            message[padded_size - 8u + i] =
                static_cast<uint8_t>((bit_length >> (i * 8u)) & 0xffu);

        std::array<uint32_t, 4> state = {
            0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u
        };
        for (size_t offset = 0; offset < padded_size; offset += 64u)
            Md5Block(message.data() + offset, &state);
        *out_state = state;
        return VEA_S5_TENSOR_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    }
}

}  // namespace

extern "C" VeaS5TensorStatus vea_s5_hash_parameter_key(
    const uint8_t* name_bytes,
    size_t name_size,
    char* out_hex,
    size_t out_capacity,
    size_t* out_required) {
    if (out_required == nullptr || (name_size != 0 && name_bytes == nullptr))
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;

    constexpr size_t kRequired = 33u;
    *out_required = kRequired;
    if (out_hex == nullptr || out_capacity < kRequired)
        return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;

    std::array<uint32_t, 4> words{};
    const VeaS5TensorStatus status = ComputeS5KeyWords(name_bytes, name_size, &words);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    // The model JSON stores the MD5 digest as its 16 digest bytes in
    // canonical hex order. Each MD5 state word is serialized little-endian.
    // Writing each word as an integer ("%08x") reverses the four bytes and
    // does not match keys such as VOCALOID7.vtmg's pre1.weight entry.
    constexpr char kHex[] = "0123456789abcdef";
    size_t cursor = 0;
    for (const uint32_t word : words) {
        for (uint32_t byte_index = 0; byte_index < 4u; ++byte_index) {
            const uint8_t byte = static_cast<uint8_t>(word >> (byte_index * 8u));
            out_hex[cursor++] = kHex[byte >> 4u];
            out_hex[cursor++] = kHex[byte & 0x0fu];
        }
    }
    out_hex[cursor] = '\\0';
    return VEA_S5_TENSOR_STATUS_OK;
}
