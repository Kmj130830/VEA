#include "veag2p.h"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

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

    assert(Normalize("Hangul: \xEA\xB0\x80\xEB\x82\x98") ==
           "Hangul: \xEA\xB0\x80\xEB\x82\x98");
    assert(Normalize("\xE3\x82\xAB\xE3\x83\xBC") == "\xE3\x82\xAB-");
    assert(Normalize("minus\xE2\x88\x92sign") == "minus-sign");
    assert(Normalize("keep\xE2\x80\x94" "dash") == "keep\xE2\x80\x94" "dash");

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
