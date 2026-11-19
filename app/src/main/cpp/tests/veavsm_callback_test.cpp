#include "vea_vsm_callback.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

uint16_t ReadU16(const std::vector<unsigned char>& bytes, size_t offset) {
    return static_cast<uint16_t>(bytes[offset]) |
        static_cast<uint16_t>(static_cast<uint16_t>(bytes[offset + 1]) << 8u);
}

uint32_t ReadU32(const std::vector<unsigned char>& bytes, size_t offset) {
    return static_cast<uint32_t>(bytes[offset]) |
        (static_cast<uint32_t>(bytes[offset + 1]) << 8u) |
        (static_cast<uint32_t>(bytes[offset + 2]) << 16u) |
        (static_cast<uint32_t>(bytes[offset + 3]) << 24u);
}

float ReadF32(const std::vector<unsigned char>& bytes, size_t offset) {
    float value = 0.0f;
    std::memcpy(&value, bytes.data() + offset, sizeof(value));
    return value;
}

}  // namespace

int main() {
    assert(vea_vsm_callback_abi_version() == VEA_VSM_CALLBACK_ABI_VERSION);
    assert(vea_vsm_default_sample_rate() == 44100u);

    VeaVsmCapture* capture = nullptr;
    assert(vea_vsm_capture_create(44100u, &capture) == VEA_VSM_STATUS_OK);
    assert(capture != nullptr);

    const float a1[] = {0.25f, -0.5f};
    const float b1[] = {0.75f, -1.0f};
    VeaVsmRenderBlockView first{4u, 0.0, 2u, a1, b1};
    assert(vea_vsm_capture_append(capture, &first) == VEA_VSM_STATUS_OK);

    const float a2[] = {0.125f};
    const float b2[] = {-0.25f};
    VeaVsmRenderBlockView second{5u, 0.01, 1u, a2, b2};
    assert(vea_vsm_capture_append(capture, &second) == VEA_VSM_STATUS_OK);

    assert(vea_vsm_capture_sample_count(capture) == 3u);
    assert(vea_vsm_capture_block_count(capture) == 2u);

    VeaVsmRenderBlockView invalid{6u, 0.02, 1u, nullptr, b2};
    assert(vea_vsm_capture_append(capture, &invalid) == VEA_VSM_STATUS_INVALID_ARGUMENT);

    const std::string path = "veavsm_capture_test.wav";
    assert(vea_vsm_capture_write_wav_f32(capture, path.c_str()) == VEA_VSM_STATUS_OK);

    std::ifstream stream(path, std::ios::binary);
    std::vector<unsigned char> bytes(
        (std::istreambuf_iterator<char>(stream)),
        std::istreambuf_iterator<char>());
    assert(bytes.size() == 44u + 3u * 8u);
    assert(std::memcmp(bytes.data(), "RIFF", 4) == 0);
    assert(std::memcmp(bytes.data() + 8, "WAVE", 4) == 0);
    assert(std::memcmp(bytes.data() + 12, "fmt ", 4) == 0);
    assert(ReadU32(bytes, 16) == 16u);
    assert(ReadU16(bytes, 20) == 3u);  // IEEE float WAV
    assert(ReadU16(bytes, 22) == 2u);
    assert(ReadU32(bytes, 24) == 44100u);
    assert(ReadU32(bytes, 28) == 44100u * 8u);
    assert(ReadU16(bytes, 34) == 32u);
    assert(std::memcmp(bytes.data() + 36, "data", 4) == 0);
    assert(ReadU32(bytes, 40) == 24u);

    assert(ReadF32(bytes, 44) == 0.25f);
    assert(ReadF32(bytes, 48) == 0.75f);
    assert(ReadF32(bytes, 52) == -0.5f);
    assert(ReadF32(bytes, 56) == -1.0f);
    assert(ReadF32(bytes, 60) == 0.125f);
    assert(ReadF32(bytes, 64) == -0.25f);

    std::remove(path.c_str());
    vea_vsm_capture_destroy(capture);
    return 0;
}
