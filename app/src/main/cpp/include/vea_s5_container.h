#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define VEA_S5_CONTAINER_HEADER_SIZE 136u
#define VEA_S5_CONTAINER_OPAQUE_METADATA_SIZE 124u
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
    VEA_S5_CONTAINER_STATUS_INVALID_LENGTH = 3
} VeaS5ContainerStatus;
/*
 * Recovered common 136-byte prefix:
 * +0x00: magic (VTBR, VPIT, VHNV, VTMG)
 * +0x04: little-endian payload byte count (file_size - 136)
 * +0x08: opaque format/version word (observed values 3, 6, 16)
 * +0x0c: 124 opaque bytes, preserved without interpretation.
 */
typedef struct VeaS5ContainerHeader {
    uint32_t kind;
    uint32_t payload_size;
    uint32_t format_word;
    uint8_t opaque_metadata[VEA_S5_CONTAINER_OPAQUE_METADATA_SIZE];
} VeaS5ContainerHeader;
uint32_t vea_s5_container_header_size(void);
VeaS5ContainerStatus vea_s5_parse_container_header(
    const uint8_t* header_bytes,
    uint64_t total_file_size,
    VeaS5ContainerHeader* out_header);
#ifdef __cplusplus
}
#endif
