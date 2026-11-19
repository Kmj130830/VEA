#include "vea_s5_container.h"
#include <cstring>
#include <limits>

static_assert(sizeof(VeaS5ContainerHeader) == VEA_S5_CONTAINER_HEADER_SIZE,
              "Recovered S5 container header must be exactly 136 bytes");

namespace {
uint32_t ReadU32LE(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8u) |
        (static_cast<uint32_t>(bytes[2]) << 16u) |
        (static_cast<uint32_t>(bytes[3]) << 24u);
}
uint32_t IdentifyKind(const uint8_t* magic) {
    if (std::memcmp(magic, "VTBR", 4) == 0) return VEA_S5_CONTAINER_KIND_VTBR;
    if (std::memcmp(magic, "VPIT", 4) == 0) return VEA_S5_CONTAINER_KIND_VPIT;
    if (std::memcmp(magic, "VHNV", 4) == 0) return VEA_S5_CONTAINER_KIND_VHNV;
    if (std::memcmp(magic, "VTMG", 4) == 0) return VEA_S5_CONTAINER_KIND_VTMG;
    return 0;
}
}
extern "C" uint32_t vea_s5_container_header_size(void) {
    return VEA_S5_CONTAINER_HEADER_SIZE;
}
extern "C" VeaS5ContainerStatus vea_s5_parse_container_header(
    const uint8_t* header_bytes,
    uint64_t total_file_size,
    VeaS5ContainerHeader* out_header) {
    if (header_bytes == nullptr || out_header == nullptr) {
        return VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT;
    }
    if (total_file_size < VEA_S5_CONTAINER_HEADER_SIZE) {
        return VEA_S5_CONTAINER_STATUS_INVALID_LENGTH;
    }
    const uint32_t kind = IdentifyKind(header_bytes);
    if (kind == 0) return VEA_S5_CONTAINER_STATUS_INVALID_MAGIC;
    const uint64_t payload_size = total_file_size - VEA_S5_CONTAINER_HEADER_SIZE;
    if (payload_size > std::numeric_limits<uint32_t>::max()) {
        return VEA_S5_CONTAINER_STATUS_INVALID_LENGTH;
    }
    const uint32_t declared_payload_size = ReadU32LE(header_bytes + 4);
    if (declared_payload_size != payload_size) {
        return VEA_S5_CONTAINER_STATUS_INVALID_LENGTH;
    }
    out_header->kind = kind;
    out_header->payload_size = declared_payload_size;
    out_header->format_word = ReadU32LE(header_bytes + 8);
    std::memcpy(out_header->opaque_metadata, header_bytes + 12,
                VEA_S5_CONTAINER_OPAQUE_METADATA_SIZE);
    return VEA_S5_CONTAINER_STATUS_OK;
}
