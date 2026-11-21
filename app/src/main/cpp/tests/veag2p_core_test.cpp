#include "veag2p.h"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

static std::string Utf8(std::u8string_view value) {
    return std::string(reinterpret_cast<const char*>(value.data()), value.size());
}

static std::string Normalize(const std::string& input) {
    size_t required = 0;
    VeaG2pStatus status = vea_g2p_normalize_korean_input_utf8(
        input.data(), input.size(), nullptr, &required);
    assert(status == VEA_G2P_STATUS_BUFFER_TOO_SMALL || status == VEA_G2P_STATUS_OK);

    std::vector<char> output(required);
    size_t capacity = output.size();
    status = vea_g2p_normalize_korean_input_utf8(
        input.data(), input.size(), output.data(), &capacity);
    assert(status == VEA_G2P_STATUS_OK);
    return std::string(output.data(), capacity);
}

int main() {
    assert(vea_g2p_bridge_abi_version() == VEA_G2P_ABI_VERSION);
    assert(vea_g2p_supported_language_mask() == 0);

    // Spot-check packed fields against the Korean DLL's Hangul index table.
    uint16_t packed = 0;
    assert(vea_g2p_korean_hangul_packed_indices(0xAC00U, &packed) == VEA_G2P_STATUS_OK);
    assert(packed == 0x8861U);  // 가
    assert(vea_g2p_korean_hangul_packed_indices(0xAC01U, &packed) == VEA_G2P_STATUS_OK);
    assert(packed == 0x8862U);  // 각
    assert(vea_g2p_korean_hangul_packed_indices(0xAC04U, &packed) == VEA_G2P_STATUS_OK);
    assert(packed == 0x8865U);  // 간
    assert(vea_g2p_korean_hangul_packed_indices(0xAC11U, &packed) == VEA_G2P_STATUS_OK);
    assert(packed == 0x8873U);  // 갑 (sparse coda index)
    assert(vea_g2p_korean_hangul_packed_indices(0xD7A3U, &packed) == VEA_G2P_STATUS_OK);
    assert(packed == 0xD3BDU);  // 힣
    assert(vea_g2p_korean_hangul_packed_indices(0x0041U, &packed) ==
           VEA_G2P_STATUS_INVALID_ARGUMENT);
    assert(vea_g2p_korean_hangul_packed_indices(0xAC00U, nullptr) ==
           VEA_G2P_STATUS_INVALID_ARGUMENT);

    // FNV-1a over the packed values for all 11,172 modern Hangul syllables.
    // Reference hash was calculated from the 6-byte records in the supplied
    // G2PAManager_KOR.dll .rdata section (little-endian packed ushort stream).
    uint64_t packed_table_hash = 14695981039346656037ULL;
    for (uint32_t codepoint = 0xAC00U; codepoint <= 0xD7A3U; ++codepoint) {
        packed = 0;
        assert(vea_g2p_korean_hangul_packed_indices(codepoint, &packed) ==
               VEA_G2P_STATUS_OK);
        const uint8_t bytes[] = {
            static_cast<uint8_t>(packed & 0xFFU),
            static_cast<uint8_t>((packed >> 8U) & 0xFFU),
        };
        for (uint8_t byte : bytes) {
            packed_table_hash ^= byte;
            packed_table_hash *= 1099511628211ULL;
        }
    }
    assert(packed_table_hash == 0x7AE6C4656323F3C6ULL);

    assert(Normalize("Hangul: \xEA\xB0\x80\xEB\x82\x98") ==
           "Hangul: \xEA\xB0\x80\xEB\x82\x98");
    assert(Normalize("\xE3\x82\xAB\xE3\x83\xBC") == "\xE3\x82\xAB-");
    assert(Normalize("minus\xE2\x88\x92sign") == "minus-sign");
    assert(Normalize("keep\xE2\x80\x94" "dash") == "keep\xE2\x80\x94" "dash");

    const std::vector<std::pair<std::string, std::string>> korean_replacements = {
        {Utf8(u8"\uB514\uADFF\uC774"), Utf8(u8"\uB514\uADF8\uC2DC")},
        {Utf8(u8"\uB514\uADFF\uC744"), Utf8(u8"\uB514\uADF8\uC2AC")},
        {Utf8(u8"\uB514\uADFF\uC5D0"), Utf8(u8"\uB514\uADF8\uC138")},
        {Utf8(u8"\uC9C0\uC752\uC774"), Utf8(u8"\uC9C0\uC73C\uC2DC")},
        {Utf8(u8"\uC9C0\uC752\uC744"), Utf8(u8"\uC9C0\uC73C\uC2AC")},
        {Utf8(u8"\uC9C0\uC752\uC5D0"), Utf8(u8"\uC9C0\uC73C\uC138")},
        {Utf8(u8"\uCE58\uC753\uC774"), Utf8(u8"\uCE58\uC73C\uC2DC")},
        {Utf8(u8"\uCE58\uC753\uC744"), Utf8(u8"\uCE58\uC73C\uC2AC")},
        {Utf8(u8"\uCE58\uC753\uC5D0"), Utf8(u8"\uCE58\uC73C\uC138")},
        {Utf8(u8"\uD0A4\uC754\uC774"), Utf8(u8"\uD0A4\uC73C\uAE30")},
        {Utf8(u8"\uD0A4\uC754\uC744"), Utf8(u8"\uD0A4\uC73C\uAE00")},
        {Utf8(u8"\uD0A4\uC754\uC5D0"), Utf8(u8"\uD0A4\uC73C\uAC8C")},
        {Utf8(u8"\uD2F0\uC755\uC774"), Utf8(u8"\uD2F0\uC73C\uC2DC")},
        {Utf8(u8"\uD2F0\uC755\uC744"), Utf8(u8"\uD2F0\uC73C\uC2AC")},
        {Utf8(u8"\uD2F0\uC755\uC5D0"), Utf8(u8"\uD2F0\uC73C\uC138")},
        {Utf8(u8"\uD53C\uC756\uC774"), Utf8(u8"\uD53C\uC73C\uBE44")},
        {Utf8(u8"\uD53C\uC756\uC744"), Utf8(u8"\uD53C\uC73C\uBE14")},
        {Utf8(u8"\uD53C\uC756\uC5D0"), Utf8(u8"\uD53C\uC73C\uBCA0")},
        {Utf8(u8"\uD788\uC757\uC774"), Utf8(u8"\uD788\uC73C\uC2DC")},
        {Utf8(u8"\uD788\uC757\uC744"), Utf8(u8"\uD788\uC73C\uC2AC")},
        {Utf8(u8"\uD788\uC757\uC5D0"), Utf8(u8"\uD788\uC73C\uC138")},
        {Utf8(u8"\uC758\uACAC\uB780"), Utf8(u8"\uC758\uACAC\uB09C")},
        {Utf8(u8"\uC784\uC9C4\uB780"), Utf8(u8"\uC784\uC9C4\uB09C")},
        {Utf8(u8"\uC0DD\uC0B0\uB7C9"), Utf8(u8"\uC0DD\uC0B0\uB0E5")},
        {Utf8(u8"\uACB0\uB2E8\uB825"), Utf8(u8"\uACB0\uB534\uB141")},
        {Utf8(u8"\uACF5\uAD8C\uB825"), Utf8(u8"\uACF5\uAFD8\uB141")},
        {Utf8(u8"\uB3D9\uC6D0\uB839"), Utf8(u8"\uB3D9\uC6D0\uB155")},
        {Utf8(u8"\uC0C1\uACAC\uB840"), Utf8(u8"\uC0C1\uACAC\uB15C")},
        {Utf8(u8"\uD6A1\uB2E8\uB85C"), Utf8(u8"\uD6A1\uB2E8\uB178")},
        {Utf8(u8"\uC774\uC6D0\uB860"), Utf8(u8"\uC774\uC6D0\uB17C")},
        {Utf8(u8"\uC785\uC6D0\uB8CC"), Utf8(u8"\uC774\uBDA0\uB1E8")},
        {Utf8(u8"\uAD6C\uADFC\uB958"), Utf8(u8"\uAD6C\uADFC\uB274")},
    };
    for (const auto& rule : korean_replacements) {
        assert(Normalize(rule.first) == rule.second);
    }
    assert(Normalize(Utf8(u8"\uB514\uADFF\uC774 / \uC758\uACAC\uB780 / \uB514\uADFF\uC774")) ==
           Utf8(u8"\uB514\uADF8\uC2DC / \uC758\uACAC\uB09C / \uB514\uADF8\uC2DC"));

    const char invalid_utf8[] = {static_cast<char>(0xC0), static_cast<char>(0xAF)};
    size_t out_size = 0;
    assert(vea_g2p_normalize_korean_input_utf8(
        invalid_utf8, sizeof(invalid_utf8), nullptr, &out_size) ==
        VEA_G2P_STATUS_INVALID_UTF8);

    const std::string input = "\xE3\x82\xAB\xE3\x83\xBC";
    char too_small[2] = {};
    out_size = sizeof(too_small);
    assert(vea_g2p_normalize_korean_input_utf8(
        input.data(), input.size(), too_small, &out_size) ==
        VEA_G2P_STATUS_BUFFER_TOO_SMALL);
    assert(out_size == 4);

    return 0;
}
