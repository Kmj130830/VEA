#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define VEA_S5_CONTAINER_PREFIX_SIZE 16u

typedef enum VeaS5ContainerKind {
    VEA_S5_CONTAINER_KIND_VTMB = 1,
    VEA_S5_CONTAINER_KIND_VTBR = 2,
    VEA_S5_CONTAINER_KIND_VTMG = 3,
    VEA_S5_CONTAINER_KIND_VPIT = 4,
    VEA_S5_CONTAINER_KIND_VNPV = 5,
    VEA_S5_CONTAINER_KIND_VHNV = 6
} VeaS5ContainerKind;

typedef enum VeaS5ContainerStatus {
    VEA_S5_CONTAINER_STATUS_OK = 0,
    VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT = 1,
    VEA_S5_CONTAINER_STATUS_INVALID_MAGIC = 2,
    VEA_S5_CONTAINER_STATUS_INVALID_LENGTH = 3,
    VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL = 4
} VeaS5ContainerStatus;

/*
 * Recovered 16-byte prefix consumed by S5API FUN_18002aa90:
 * +0x00: magic (VTMB, VTBR, VTMG, VPIT, VNPV, VHNV)
 * +0x04: little-endian declared span; the decoder adds 8 before clamping
 *        against the actual file size.
 * +0x08: format/version word (preserved; not used by this transform).
 * +0x0c: little-endian 32-bit transform seed.
 *
 * Checked AI Megpoid/VOCALOID7 assets have declared_span == file_size - 136.
 * The transform then emits file_size - 144 bytes, leaving a 128-byte trailer
 * after the 16-byte prefix untouched. The generic decoder follows the
 * recovered clamp rule instead of hard-coding that catalog-specific trailer.
 */
typedef struct VeaS5ContainerHeader {
    uint32_t kind;
    uint32_t declared_span;
    uint32_t format_word;
    uint32_t transform_seed;
    uint64_t decoded_payload_size;
} VeaS5ContainerHeader;

VeaS5ContainerStatus vea_s5_parse_container_header(
    const uint8_t* header_bytes,
    uint64_t total_file_size,
    VeaS5ContainerHeader* out_header);

/*
 * Applies the byte transform recovered from S5API FUN_18002aa90.
 * Output is the transformed body only; the 16-byte prefix and any trailing
 * bytes outside the decoder's declared/clamped range are not copied.
 */
VeaS5ContainerStatus vea_s5_decode_container_payload(
    const uint8_t* file_bytes,
    uint64_t total_file_size,
    uint8_t* output,
    uint64_t output_capacity,
    uint64_t* out_payload_size);

#ifdef __cplusplus
}
#endif
