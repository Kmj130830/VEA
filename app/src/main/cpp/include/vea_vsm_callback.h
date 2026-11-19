#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
  #if defined(VEA_VSM_BUILD)
    #define VEA_VSM_API __declspec(dllexport)
  #else
    #define VEA_VSM_API __declspec(dllimport)
  #endif
#else
  #define VEA_VSM_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define VEA_VSM_CALLBACK_ABI_VERSION 0x00010000u
#define VEA_VSM_DEFAULT_SAMPLE_RATE 44100u
#define VEA_VSM_MAX_BLOCK_SAMPLES (1u << 20)

typedef struct VeaVsmCapture VeaVsmCapture;

typedef enum VeaVsmStatus {
    VEA_VSM_STATUS_OK = 0,
    VEA_VSM_STATUS_INVALID_ARGUMENT = 1,
    VEA_VSM_STATUS_OUT_OF_MEMORY = 2,
    VEA_VSM_STATUS_IO_ERROR = 3,
    VEA_VSM_STATUS_RIFF_LIMIT_EXCEEDED = 4
} VeaVsmStatus;

/*
 * Recovered callback parameter types from the VSM renderPartiallyAsync
 * std::function signature:
 *   void(const uint32_t&, const double&, const uint64_t&,
 *        const float*, const float*)
 *
 * The names below deliberately describe only the ABI types. Semantics of
 * the scalar metadata and the two float planes are still under analysis.
 */
typedef struct VeaVsmRenderBlockView {
    uint32_t metadata_u32;
    double metadata_f64;
    uint64_t sample_count;
    const float* plane_a;
    const float* plane_b;
} VeaVsmRenderBlockView;

VEA_VSM_API uint32_t vea_vsm_callback_abi_version(void);
VEA_VSM_API uint32_t vea_vsm_default_sample_rate(void);

VEA_VSM_API VeaVsmStatus vea_vsm_capture_create(
    uint32_t sample_rate,
    VeaVsmCapture** out_capture);

VEA_VSM_API VeaVsmStatus vea_vsm_capture_append(
    VeaVsmCapture* capture,
    const VeaVsmRenderBlockView* block);

VEA_VSM_API uint64_t vea_vsm_capture_sample_count(const VeaVsmCapture* capture);
VEA_VSM_API uint64_t vea_vsm_capture_block_count(const VeaVsmCapture* capture);

/*
 * Writes the two recovered float planes as two channels in a 32-bit IEEE
 * float WAV file. This is a transport/export adapter, not a synthesizer.
 * The plane-to-channel interpretation is intentionally kept configurable
 * at the integration boundary until the VSM audio-buffer mapping is proven.
 */
VEA_VSM_API VeaVsmStatus vea_vsm_capture_write_wav_f32(
    const VeaVsmCapture* capture,
    const char* utf8_path);

VEA_VSM_API void vea_vsm_capture_destroy(VeaVsmCapture* capture);

#ifdef __cplusplus
}
#endif
