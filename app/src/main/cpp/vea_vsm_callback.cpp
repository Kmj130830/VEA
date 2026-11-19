#include "vea_vsm_callback.h"

#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <vector>

struct VeaVsmCapture {
    explicit VeaVsmCapture(uint32_t rate) : sample_rate(rate) {}

    uint32_t sample_rate;
    mutable std::mutex mutex;
    std::vector<float> plane_a;
    std::vector<float> plane_b;
    uint64_t block_count = 0;
};

namespace {

void WriteU16(FILE* file, uint16_t value) {
    const unsigned char bytes[2] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu)
    };
    (void)std::fwrite(bytes, 1, sizeof(bytes), file);
}

void WriteU32(FILE* file, uint32_t value) {
    const unsigned char bytes[4] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu),
        static_cast<unsigned char>((value >> 16u) & 0xffu),
        static_cast<unsigned char>((value >> 24u) & 0xffu)
    };
    (void)std::fwrite(bytes, 1, sizeof(bytes), file);
}

bool WriteBytes(FILE* file, const void* data, size_t size) {
    return size == 0 || std::fwrite(data, 1, size, file) == size;
}

bool ValidSampleRate(uint32_t rate) {
    return rate >= 8000u && rate <= 384000u;
}

}  // namespace

extern "C" uint32_t vea_vsm_callback_abi_version(void) {
    return VEA_VSM_CALLBACK_ABI_VERSION;
}

extern "C" uint32_t vea_vsm_default_sample_rate(void) {
    return VEA_VSM_DEFAULT_SAMPLE_RATE;
}

extern "C" VeaVsmStatus vea_vsm_capture_create(
    uint32_t sample_rate,
    VeaVsmCapture** out_capture) {
    if (out_capture == nullptr || !ValidSampleRate(sample_rate)) {
        return VEA_VSM_STATUS_INVALID_ARGUMENT;
    }

    *out_capture = nullptr;
    try {
        *out_capture = new VeaVsmCapture(sample_rate);
        return VEA_VSM_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_VSM_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return VEA_VSM_STATUS_OUT_OF_MEMORY;
    }
}

extern "C" VeaVsmStatus vea_vsm_capture_append(
    VeaVsmCapture* capture,
    const VeaVsmRenderBlockView* block) {
    if (capture == nullptr || block == nullptr ||
        block->sample_count == 0 ||
        block->sample_count > VEA_VSM_MAX_BLOCK_SAMPLES ||
        block->sample_count > static_cast<uint64_t>(std::numeric_limits<size_t>::max()) ||
        block->plane_a == nullptr || block->plane_b == nullptr) {
        return VEA_VSM_STATUS_INVALID_ARGUMENT;
    }

    const size_t count = static_cast<size_t>(block->sample_count);
    std::lock_guard<std::mutex> lock(capture->mutex);

    if (count > capture->plane_a.max_size() - capture->plane_a.size() ||
        count > capture->plane_b.max_size() - capture->plane_b.size()) {
        return VEA_VSM_STATUS_RIFF_LIMIT_EXCEEDED;
    }

    const uint64_t next_samples = static_cast<uint64_t>(capture->plane_a.size()) + block->sample_count;
    // Stereo IEEE float32 needs 8 bytes per sample frame. A classic RIFF
    // chunk stores a 32-bit size, so reject an export that cannot be represented.
    if (next_samples > (static_cast<uint64_t>(UINT32_MAX) - 36u) / 8u) {
        return VEA_VSM_STATUS_RIFF_LIMIT_EXCEEDED;
    }

    try {
        capture->plane_a.insert(capture->plane_a.end(), block->plane_a, block->plane_a + count);
        capture->plane_b.insert(capture->plane_b.end(), block->plane_b, block->plane_b + count);
        ++capture->block_count;
        return VEA_VSM_STATUS_OK;
    } catch (const std::bad_alloc&) {
        // Keep both planes aligned after a partial allocation failure.
        const size_t common = capture->plane_a.size() < capture->plane_b.size()
            ? capture->plane_a.size() : capture->plane_b.size();
        capture->plane_a.resize(common);
        capture->plane_b.resize(common);
        return VEA_VSM_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        const size_t common = capture->plane_a.size() < capture->plane_b.size()
            ? capture->plane_a.size() : capture->plane_b.size();
        capture->plane_a.resize(common);
        capture->plane_b.resize(common);
        return VEA_VSM_STATUS_OUT_OF_MEMORY;
    }
}

extern "C" uint64_t vea_vsm_capture_sample_count(const VeaVsmCapture* capture) {
    if (capture == nullptr) return 0;
    std::lock_guard<std::mutex> lock(capture->mutex);
    return static_cast<uint64_t>(capture->plane_a.size());
}

extern "C" uint64_t vea_vsm_capture_block_count(const VeaVsmCapture* capture) {
    if (capture == nullptr) return 0;
    std::lock_guard<std::mutex> lock(capture->mutex);
    return capture->block_count;
}

extern "C" VeaVsmStatus vea_vsm_capture_write_wav_f32(
    const VeaVsmCapture* capture,
    const char* utf8_path) {
    if (capture == nullptr || utf8_path == nullptr || utf8_path[0] == '\0') {
        return VEA_VSM_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(capture->mutex);
    const uint64_t frames = static_cast<uint64_t>(capture->plane_a.size());
    if (frames != capture->plane_b.size()) {
        return VEA_VSM_STATUS_INVALID_ARGUMENT;
    }
    if (frames > (static_cast<uint64_t>(UINT32_MAX) - 36u) / 8u) {
        return VEA_VSM_STATUS_RIFF_LIMIT_EXCEEDED;
    }

    const uint32_t data_bytes = static_cast<uint32_t>(frames * 8u);
    const uint32_t byte_rate = capture->sample_rate * 8u;
    FILE* file = std::fopen(utf8_path, "wb");
    if (file == nullptr) return VEA_VSM_STATUS_IO_ERROR;

    bool ok = true;
    ok = ok && WriteBytes(file, "RIFF", 4);
    WriteU32(file, 36u + data_bytes);
    ok = ok && WriteBytes(file, "WAVE", 4);
    ok = ok && WriteBytes(file, "fmt ", 4);
    WriteU32(file, 16u);
    WriteU16(file, 3u);  // WAVE_FORMAT_IEEE_FLOAT
    WriteU16(file, 2u);
    WriteU32(file, capture->sample_rate);
    WriteU32(file, byte_rate);
    WriteU16(file, 8u);
    WriteU16(file, 32u);
    ok = ok && WriteBytes(file, "data", 4);
    WriteU32(file, data_bytes);

    // Write frame-interleaved channel data without changing sample values.
    for (size_t i = 0; ok && i < capture->plane_a.size(); ++i) {
        ok = WriteBytes(file, &capture->plane_a[i], sizeof(float));
        ok = ok && WriteBytes(file, &capture->plane_b[i], sizeof(float));
    }

    if (std::fclose(file) != 0) ok = false;
    return ok ? VEA_VSM_STATUS_OK : VEA_VSM_STATUS_IO_ERROR;
}

extern "C" void vea_vsm_capture_destroy(VeaVsmCapture* capture) {
    delete capture;
}
