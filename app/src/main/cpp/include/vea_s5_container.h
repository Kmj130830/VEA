#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define VEA_S5_CONTAINER_HEADER_SIZE 136u
#define VEA_S5_CONTAINER_RAW_REMAINDER_SIZE 124u
typedef enum VeaS5ContainerKind {
    VEA_S5_CONTAINER_KIND_VTBR = 1,
    VEA_S5_CONTAINER_KIND_VPIT = 2,
    VEA_S5_CONTAINER_KIND_VHNV = 3,
    VEA_S5_CONTAINER_KIND_VTMG = 4
} VeaS5ContainerKind;
typedef enum VeaS5ContainerStatus {
    VEA_S5_CONTAINER_STATUS_OK = 0,
    VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT = 1,
    VEA_S5_CONTAINER_STATUS_INVALID_MAGIC = 2,
    VEA_S5_CONTAINER_STATUS_INVALID_LENGTH = 3,
    VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL = 4
} VeaS5ContainerStatus;
/*
 * Recovered common 136-byte prefix:
 * +0x00: magic (VTBR, VPIT, VHNV, VTMG)
 * +0x04: little-endian payload byte count (file_size - 136)
 * +0x08: opaque format/version word (observed values 3, 6, 16)
 * +0x0c: raw 124-byte remainder; its first 4 bytes are the payload seed,
 *        followed by encoded payload bytes. The model decoder interprets them.
 */
typedef struct VeaS5ContainerHeader {
    uint32_t kind;
    uint32_t payload_size;
    uint32_t format_word;
    uint8_t raw_bytes_after_format_word[VEA_S5_CONTAINER_RAW_REMAINDER_SIZE];
} VeaS5ContainerHeader;
uint32_t vea_s5_container_header_size(void);
VeaS5ContainerStatus vea_s5_parse_container_header(
    const uint8_t* header_bytes,
    uint64_t total_file_size,
    VeaS5ContainerHeader* out_header);
/*
 * Reproduces S5API FUN_18002aa90 for model JSON payload preparation.
 * Recognized magics: VTMB, VTBR, VTMG, VPIT, VPNV, VHNV.
 *
 * The function returns the required output length in out_output_size. Pass
 * output=nullptr/output_capacity=0 to query the required length; a non-empty
 * result returns BUFFER_TOO_SMALL. Unknown 16-byte-or-longer inputs are copied
 * unchanged, matching the native fallback path. Inputs shorter than 16 bytes
 * produce an empty result.
 */
VeaS5ContainerStatus vea_s5_decode_model_payload(
    const uint8_t* input,
    size_t input_size,
    uint8_t* output,
    size_t output_capacity,
    size_t* out_output_size);
#ifdef __cplusplus
}
#endif
