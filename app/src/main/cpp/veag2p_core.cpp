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

// The Korean DLL's 11,172-entry Hangul table is a packed composition
// table. Its fields are determined by the modern Hangul L/V/T indices:
// onset = L + 2; vowel and coda use the DLL's sparse index spaces.
constexpr uint8_t kKoreanVowelIndices[21] = {
    3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15,
    18, 19, 20, 21, 22, 23, 26, 27, 28, 29,
};

constexpr uint8_t kKoreanCodaIndices[28] = {
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
    15, 16, 17, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
};

constexpr const char* kKoreanOnsetPhones[32] = {
    "", "", "g", "g'", "n", "d", "d'", "l",
    "m", "b", "b'", "s", "s'", "N", "c", "c'",
    "ch", "k", "t", "p", "h", "", "", "",
    "", "", "sh", "N", "r", "sh'", "", "",
};

constexpr const char* kKoreanVowelPhones[32] = {
    "", "", "", "a", "e", "ja", "je", "7",
    "", "", "e", "j7", "je", "o", "oa", "ue",
    "", "", "ue", "jo", "u", "u7", "ue", "ui",
    "", "", "ju", "M", "Mi", "i", "", "",
};

constexpr const char* kKoreanCodaPhones[32] = {
    "", "", "gp", "gp", "gp", "np", "np", "np",
    "dp", "rp", "gp", "mp", "mp", "dp", "dp", "mp",
    "rp", "mp", "", "bp", "bp", "dp", "dp", "Np",
    "dp", "dp", "gp", "dp", "bp", "dp", "", "",
};



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

extern "C" VeaG2pStatus vea_g2p_korean_hangul_packed_indices(
    uint32_t codepoint,
    uint16_t* packed_indices) {
    if (packed_indices == nullptr || codepoint < 0xAC00U || codepoint > 0xD7A3U) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    const uint32_t syllable_index = codepoint - 0xAC00U;
    const uint32_t initial_index = syllable_index / 588U;
    const uint32_t medial_index = (syllable_index % 588U) / 28U;
    const uint32_t final_index = syllable_index % 28U;

    const uint32_t onset = initial_index + 2U;
    const uint32_t vowel = kKoreanVowelIndices[medial_index];
    const uint32_t coda = kKoreanCodaIndices[final_index];

    *packed_indices = static_cast<uint16_t>(
        0x8000U | (onset << 10U) | (vowel << 5U) | coda);
    return VEA_G2P_STATUS_OK;
}

extern "C" VeaG2pStatus vea_g2p_korean_hangul_from_packed_indices(
    uint16_t packed_indices,
    uint32_t* codepoint) {
    if (codepoint == nullptr) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    const uint32_t onset_index = (packed_indices >> 10U) & 0x1FU;
    const uint32_t vowel_index = (packed_indices >> 5U) & 0x1FU;
    uint32_t coda_index = packed_indices & 0x1FU;

    if ((packed_indices & 0x8000U) == 0U) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }
    if (coda_index == 0U) {
        // FUN_180008660 treats table coda indices 1, 30 and 31 as the
        // empty-coda variant when the requested low five bits are zero.
        // The modern-Hangul table has coda index 1 for the empty-coda row.
        coda_index = 1U;
    }

    if (onset_index < 2U || onset_index > 20U) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    uint32_t medial_index = 0U;
    bool found_vowel = false;
    for (uint32_t index = 0; index < 21U; ++index) {
        if (kKoreanVowelIndices[index] == vowel_index) {
            medial_index = index;
            found_vowel = true;
            break;
        }
    }
    if (!found_vowel) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    uint32_t final_index = 0U;
    bool found_coda = false;
    for (uint32_t index = 0; index < 28U; ++index) {
        if (kKoreanCodaIndices[index] == coda_index) {
            final_index = index;
            found_coda = true;
            break;
        }
    }
    if (!found_coda) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    const uint32_t initial_index = onset_index - 2U;
    *codepoint = 0xAC00U + initial_index * 588U + medial_index * 28U + final_index;
    if (*codepoint > 0xD7A3U) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }
    return VEA_G2P_STATUS_OK;
}

extern "C" VeaG2pStatus vea_g2p_korean_apply_single_syllable_rules(
    uint32_t codepoint,
    uint32_t* transformed_codepoint) {
    if (transformed_codepoint == nullptr) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    uint16_t packed = 0;
    const VeaG2pStatus status =
        vea_g2p_korean_hangul_packed_indices(codepoint, &packed);
    if (status != VEA_G2P_STATUS_OK) {
        return status;
    }

    const uint32_t onset_index = (packed >> 10U) & 0x1FU;
    const uint32_t vowel_index = (packed >> 5U) & 0x1FU;

    // First per-character pass in FUN_180008730:
    // onset != 7,13 and packed vowel 12 -> packed vowel 10.
    if (onset_index != 7U && onset_index != 13U && vowel_index == 12U) {
        const uint16_t target = static_cast<uint16_t>(
            (packed & 0xFC00U) | 0x0140U | (packed & 0x001FU));
        return vea_g2p_korean_hangul_from_packed_indices(
            target, transformed_codepoint);
    }

    // The next character-pair pass also applies this local rewrite to its
    // current syllable: onset != 13 and packed vowel 28 -> packed vowel 29.
    if (onset_index != 13U && vowel_index == 28U) {
        const uint16_t target = static_cast<uint16_t>(
            (packed & 0xFC00U) | 0x03A0U | (packed & 0x001FU));
        return vea_g2p_korean_hangul_from_packed_indices(
            target, transformed_codepoint);
    }

    *transformed_codepoint = codepoint;
    return VEA_G2P_STATUS_OK;
}

extern "C" VeaG2pStatus vea_g2p_korean_syllable_components(
    uint32_t codepoint,
    VeaKoreanPhonemeComponents* components) {
    if (components == nullptr) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    uint16_t packed = 0;
    const VeaG2pStatus status =
        vea_g2p_korean_hangul_packed_indices(codepoint, &packed);
    if (status != VEA_G2P_STATUS_OK) {
        return status;
    }

    const uint32_t onset_index = (packed >> 10U) & 0x1FU;
    const uint32_t vowel_index = (packed >> 5U) & 0x1FU;
    const uint32_t coda_index = packed & 0x1FU;

    const char* onset = kKoreanOnsetPhones[onset_index];
    const char* vowel = kKoreanVowelPhones[vowel_index];
    const char* coda = kKoreanCodaPhones[coda_index];

    if (std::strlen(onset) >= sizeof(components->onset) ||
        std::strlen(vowel) >= sizeof(components->vowel) ||
        std::strlen(coda) >= sizeof(components->coda)) {
        return VEA_G2P_STATUS_INVALID_ARGUMENT;
    }

    std::memset(components, 0, sizeof(*components));
    std::memcpy(components->onset, onset, std::strlen(onset));
    std::memcpy(components->vowel, vowel, std::strlen(vowel));
    std::memcpy(components->coda, coda, std::strlen(coda));
    return VEA_G2P_STATUS_OK;
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
