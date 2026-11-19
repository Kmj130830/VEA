#include "veag2p.h"

#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

namespace {

bool DecodeUtf8(const char* input, size_t size, std::vector<char32_t>* codepoints) {
    if (codepoints == nullptr || (input == nullptr && size != 0)) {
        return false;
    }

    codepoints->clear();
    codepoints->reserve(size);

    size_t index = 0;
    while (index < size) {
        const auto first = static_cast<uint8_t>(input[index]);
        char32_t value = 0;
        size_t count = 0;
        char32_t minimum = 0;

        if (first <= 0x7F) {
            value = first;
            count = 1;
            minimum = 0;
        } else if ((first & 0xE0U) == 0xC0U) {
            value = first & 0x1FU;
            count = 2;
            minimum = 0x80;
        } else if ((first & 0xF0U) == 0xE0U) {
            value = first & 0x0FU;
            count = 3;
            minimum = 0x800;
        } else if ((first & 0xF8U) == 0xF0U) {
            value = first & 0x07U;
            count = 4;
            minimum = 0x10000;
        } else {
            return false;
        }

        if (count > size - index) {
            return false;
        }

        for (size_t offset = 1; offset < count; ++offset) {
            const auto continuation = static_cast<uint8_t>(input[index + offset]);
            if ((continuation & 0xC0U) != 0x80U) {
                return false;
            }
            value = (value << 6U) | (continuation & 0x3FU);
        }

        if (value < minimum || value > 0x10FFFF ||
            (value >= 0xD800 && value <= 0xDFFF)) {
            return false;
        }

        codepoints->push_back(value);
        index += count;
    }

    return true;
}

size_t EncodedSize(char32_t value) {
    if (value <= 0x7F) return 1;
    if (value <= 0x7FF) return 2;
    if (value <= 0xFFFF) return 3;
    return 4;
}

void EncodeUtf8(char32_t value, char* output, size_t* offset) {
    if (value <= 0x7F) {
        output[(*offset)++] = static_cast<char>(value);
    } else if (value <= 0x7FF) {
        output[(*offset)++] = static_cast<char>(0xC0U | (value >> 6U));
        output[(*offset)++] = static_cast<char>(0x80U | (value & 0x3FU));
    } else if (value <= 0xFFFF) {
        output[(*offset)++] = static_cast<char>(0xE0U | (value >> 12U));
        output[(*offset)++] = static_cast<char>(0x80U | ((value >> 6U) & 0x3FU));
        output[(*offset)++] = static_cast<char>(0x80U | (value & 0x3FU));
    } else {
        output[(*offset)++] = static_cast<char>(0xF0U | (value >> 18U));
        output[(*offset)++] = static_cast<char>(0x80U | ((value >> 12U) & 0x3FU));
        output[(*offset)++] = static_cast<char>(0x80U | ((value >> 6U) & 0x3FU));
        output[(*offset)++] = static_cast<char>(0x80U | (value & 0x3FU));
    }
}

}  // namespace

extern "C" uint32_t vea_g2p_bridge_abi_version(void) {
    return VEA_G2P_ABI_VERSION;
}

extern "C" uint32_t vea_g2p_supported_language_mask(void) {
    // No Yamaha-compatible language phonemizer is implemented yet.
    return 0;
}

extern "C" VeaG2pStatus vea_g2p_normalize_korean_input_utf8(
    const char* input,
    size_t input_size,
    char* output,
    size_t* output_size) {
    if (output_size == nullptr || (input == nullptr && input_size != 0)) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    std::vector<char32_t> codepoints;
    if (!DecodeUtf8(input, input_size, &codepoints)) {
        return VEA_G2P_STATUS_INVALID_UTF8;
    }

    size_t required = 0;
    for (char32_t codepoint : codepoints) {
        if (codepoint == 0x30FC || codepoint == 0x2212) {
            codepoint = 0x002D;
        }
        const size_t next = EncodedSize(codepoint);
        if (next > std::numeric_limits<size_t>::max() - required) {
            return VEA_G2P_STATUS_INVALID_ARGUMENT;
        }
        required += next;
    }

    const size_t capacity = *output_size;
    *output_size = required;
    if (required != 0 && (output == nullptr || capacity < required)) {
        return VEA_G2P_STATUS_BUFFER_TOO_SMALL;
    }

    size_t offset = 0;
    for (char32_t codepoint : codepoints) {
        if (codepoint == 0x30FC || codepoint == 0x2212) {
            codepoint = 0x002D;
        }
        EncodeUtf8(codepoint, output, &offset);
    }
    *output_size = offset;
    return VEA_G2P_STATUS_OK;
}
