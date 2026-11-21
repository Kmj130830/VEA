#include "vea_s5_container.h"

#include <cstring>
#include <limits>

namespace {

uint32_t ReadU32LE(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8u) |
        (static_cast<uint32_t>(bytes[2]) << 16u) |
        (static_cast<uint32_t>(bytes[3]) << 24u);
}

void WriteU32LE(uint8_t* bytes, uint32_t value) {
    bytes[0] = static_cast<uint8_t>(value);
    bytes[1] = static_cast<uint8_t>(value >> 8u);
    bytes[2] = static_cast<uint8_t>(value >> 16u);
    bytes[3] = static_cast<uint8_t>(value >> 24u);
}

uint32_t IdentifyKind(const uint8_t* magic) {
    if (std::memcmp(magic, "VTMB", 4) == 0) return VEA_S5_CONTAINER_KIND_VTMB;
    if (std::memcmp(magic, "VTBR", 4) == 0) return VEA_S5_CONTAINER_KIND_VTBR;
    if (std::memcmp(magic, "VTMG", 4) == 0) return VEA_S5_CONTAINER_KIND_VTMG;
    if (std::memcmp(magic, "VPIT", 4) == 0) return VEA_S5_CONTAINER_KIND_VPIT;
    if (std::memcmp(magic, "VNPV", 4) == 0) return VEA_S5_CONTAINER_KIND_VNPV;
    if (std::memcmp(magic, "VHNV", 4) == 0) return VEA_S5_CONTAINER_KIND_VHNV;
    return 0;
}

uint64_t DecodedPayloadSize(uint32_t declared_span, uint64_t total_file_size) {
    uint64_t end = static_cast<uint64_t>(declared_span) + 8u;
    if (end < VEA_S5_CONTAINER_PREFIX_SIZE || total_file_size < end) {
        end = total_file_size;
    }
    return end - VEA_S5_CONTAINER_PREFIX_SIZE;
}

}  // namespace

extern "C" VeaS5ContainerStatus vea_s5_parse_container_header(
    const uint8_t* header_bytes,
    uint64_t total_file_size,
    VeaS5ContainerHeader* out_header) {
    if (header_bytes == nullptr || out_header == nullptr) {
        return VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT;
    }
    if (total_file_size < VEA_S5_CONTAINER_PREFIX_SIZE) {
        return VEA_S5_CONTAINER_STATUS_INVALID_LENGTH;
    }

    const uint32_t kind = IdentifyKind(header_bytes);
    if (kind == 0) return VEA_S5_CONTAINER_STATUS_INVALID_MAGIC;

    const uint32_t declared_span = ReadU32LE(header_bytes + 4);
    out_header->kind = kind;
    out_header->declared_span = declared_span;
    out_header->format_word = ReadU32LE(header_bytes + 8);
    out_header->transform_seed = ReadU32LE(header_bytes + 12);
    out_header->decoded_payload_size = DecodedPayloadSize(declared_span, total_file_size);
    return VEA_S5_CONTAINER_STATUS_OK;
}

extern "C" VeaS5ContainerStatus vea_s5_decode_container_payload(
    const uint8_t* file_bytes,
    uint64_t total_file_size,
    uint8_t* output,
    uint64_t output_capacity,
    uint64_t* out_payload_size) {
    if (file_bytes == nullptr || out_payload_size == nullptr) {
        return VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT;
    }
    *out_payload_size = 0;

    VeaS5ContainerHeader header{};
    const VeaS5ContainerStatus parse_status =
        vea_s5_parse_container_header(file_bytes, total_file_size, &header);
    if (parse_status != VEA_S5_CONTAINER_STATUS_OK) return parse_status;

    const uint64_t payload_size = header.decoded_payload_size;
    if (payload_size > output_capacity || (payload_size != 0 && output == nullptr)) {
        return VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL;
    }
    if (payload_size == 0) {
        *out_payload_size = 0;
        return VEA_S5_CONTAINER_STATUS_OK;
    }

    const uint64_t word_count = payload_size / 4u;
    const uint32_t remainder = static_cast<uint32_t>(payload_size & 3u);
    if (word_count == 0 && remainder != 0) {
        // In the recovered tail path uVar5 is assigned by the full-word loop.
        // The vendor implementation has no defined seed value for a sub-word
        // body, so reject rather than invent a fallback.
        return VEA_S5_CONTAINER_STATUS_INVALID_LENGTH;
    }

    uint32_t state = header.transform_seed;
    uint32_t stream_word = 0x05491333u;
    uint32_t state_a = 0x159a55e5u;
    uint32_t state_b = 0x1f123bb5u;

    for (uint64_t word = 0; word < word_count; ++word) {
        const uint32_t previous_stream = stream_word;
        const uint32_t previous_state = stream_word;
        state = (state << 11u) ^ state;
        stream_word = previous_state ^
            ((((previous_state >> 11u) ^ state) >> 8u)) ^ state;

        const uint32_t cipher = ReadU32LE(
            file_bytes + VEA_S5_CONTAINER_PREFIX_SIZE + word * 4u);
        WriteU32LE(output + word * 4u, cipher ^ stream_word);

        state = state_a;
        state_a = state_b;
        state_b = previous_state;
        (void)previous_stream;
    }

    if (remainder != 0) {
        uint32_t tail_word = 0;
        const uint64_t tail_offset =
            VEA_S5_CONTAINER_PREFIX_SIZE + word_count * 4u;
        for (uint32_t i = 0; i < remainder; ++i) {
            tail_word |= static_cast<uint32_t>(file_bytes[tail_offset + i]) << (i * 8u);
        }

        // The final partial group uses the vendor's distinct folded state.
        uint32_t folded_state = (state_a << 11u) ^ state_a;
        const uint32_t mixed =
            (((stream_word >> 11u) ^ folded_state) >> 8u) ^
            folded_state ^ stream_word ^ tail_word;
        for (uint32_t i = 0; i < remainder; ++i) {
            output[word_count * 4u + i] =
                static_cast<uint8_t>(mixed >> (i * 8u));
        }
    }

    *out_payload_size = payload_size;
    return VEA_S5_CONTAINER_STATUS_OK;
}
