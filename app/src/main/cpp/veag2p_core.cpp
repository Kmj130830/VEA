#include "veag2p.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string_view>
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

struct KoreanReplacementRule {
    std::u32string_view source;
    std::u32string_view target;
};

// Ordered source/target pairs recovered from the Korean manager's 32
// sequential wcsstr/memcpy replacement passes. Keep order: later rules see
// the output of earlier rules, matching the observed desktop control flow.
constexpr KoreanReplacementRule kKoreanReplacementRules[] = {
    {U"\uB514\uADFF\uC774", U"\uB514\uADF8\uC2DC"},
    {U"\uB514\uADFF\uC744", U"\uB514\uADF8\uC2AC"},
    {U"\uB514\uADFF\uC5D0", U"\uB514\uADF8\uC138"},
    {U"\uC9C0\uC752\uC774", U"\uC9C0\uC73C\uC2DC"},
    {U"\uC9C0\uC752\uC744", U"\uC9C0\uC73C\uC2AC"},
    {U"\uC9C0\uC752\uC5D0", U"\uC9C0\uC73C\uC138"},
    {U"\uCE58\uC753\uC774", U"\uCE58\uC73C\uC2DC"},
    {U"\uCE58\uC753\uC744", U"\uCE58\uC73C\uC2AC"},
    {U"\uCE58\uC753\uC5D0", U"\uCE58\uC73C\uC138"},
    {U"\uD0A4\uC754\uC774", U"\uD0A4\uC73C\uAE30"},
    {U"\uD0A4\uC754\uC744", U"\uD0A4\uC73C\uAE00"},
    {U"\uD0A4\uC754\uC5D0", U"\uD0A4\uC73C\uAC8C"},
    {U"\uD2F0\uC755\uC774", U"\uD2F0\uC73C\uC2DC"},
    {U"\uD2F0\uC755\uC744", U"\uD2F0\uC73C\uC2AC"},
    {U"\uD2F0\uC755\uC5D0", U"\uD2F0\uC73C\uC138"},
    {U"\uD53C\uC756\uC774", U"\uD53C\uC73C\uBE44"},
    {U"\uD53C\uC756\uC744", U"\uD53C\uC73C\uBE14"},
    {U"\uD53C\uC756\uC5D0", U"\uD53C\uC73C\uBCA0"},
    {U"\uD788\uC757\uC774", U"\uD788\uC73C\uC2DC"},
    {U"\uD788\uC757\uC744", U"\uD788\uC73C\uC2AC"},
    {U"\uD788\uC757\uC5D0", U"\uD788\uC73C\uC138"},
    {U"\uC758\uACAC\uB780", U"\uC758\uACAC\uB09C"},
    {U"\uC784\uC9C4\uB780", U"\uC784\uC9C4\uB09C"},
    {U"\uC0DD\uC0B0\uB7C9", U"\uC0DD\uC0B0\uB0E5"},
    {U"\uACB0\uB2E8\uB825", U"\uACB0\uB534\uB141"},
    {U"\uACF5\uAD8C\uB825", U"\uACF5\uAFD8\uB141"},
    {U"\uB3D9\uC6D0\uB839", U"\uB3D9\uC6D0\uB155"},
    {U"\uC0C1\uACAC\uB840", U"\uC0C1\uACAC\uB15C"},
    {U"\uD6A1\uB2E8\uB85C", U"\uD6A1\uB2E8\uB178"},
    {U"\uC774\uC6D0\uB860", U"\uC774\uC6D0\uB17C"},
    {U"\uC785\uC6D0\uB8CC", U"\uC774\uBDA0\uB1E8"},
    {U"\uAD6C\uADFC\uB958", U"\uAD6C\uADFC\uB274"},
};

void ApplyKoreanReplacementRules(std::vector<char32_t>* codepoints) {
    if (codepoints == nullptr || codepoints->empty()) return;

    for (const KoreanReplacementRule& rule : kKoreanReplacementRules) {
        if (rule.source.empty() || rule.source.size() != rule.target.size()) continue;

        // The desktop loop searches from the start again after each match.
        // Repeating std::search from begin preserves that behavior, including
        // any overlapping occurrence exposed by an earlier replacement.
        while (true) {
            const auto match = std::search(
                codepoints->begin(), codepoints->end(),
                rule.source.begin(), rule.source.end());
            if (match == codepoints->end()) break;
            std::copy(rule.target.begin(), rule.target.end(), match);
        }
    }
}

void NormalizeKoreanCodepoints(std::vector<char32_t>* codepoints) {
    for (char32_t& codepoint : *codepoints) {
        if (codepoint == 0x30FC || codepoint == 0x2212) {
            codepoint = 0x002D;
        }
    }
    ApplyKoreanReplacementRules(codepoints);
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

    NormalizeKoreanCodepoints(&codepoints);

    size_t required = 0;
    for (char32_t codepoint : codepoints) {
        const size_t encoded_size = EncodedSize(codepoint);
        if (encoded_size > std::numeric_limits<size_t>::max() - required) {
            return VEA_G2P_STATUS_INVALID_ARGUMENT;
        }
        required += encoded_size;
    }

    const size_t capacity = *output_size;
    *output_size = required;
    if (required != 0 && (output == nullptr || capacity < required)) {
        return VEA_G2P_STATUS_BUFFER_TOO_SMALL;
    }

    size_t offset = 0;
    for (char32_t codepoint : codepoints) {
        EncodeUtf8(codepoint, output, &offset);
    }
    *output_size = offset;
    return VEA_G2P_STATUS_OK;
}
