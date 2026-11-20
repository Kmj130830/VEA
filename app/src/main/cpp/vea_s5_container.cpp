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

namespace {
bool HasS5PayloadTransformMagic(const uint8_t* input) {
    return std::memcmp(input, "VTMB", 4) == 0 ||
        std::memcmp(input, "VTBR", 4) == 0 ||
        std::memcmp(input, "VTMG", 4) == 0 ||
        std::memcmp(input, "VPIT", 4) == 0 ||
        std::memcmp(input, "VPNV", 4) == 0 ||
        std::memcmp(input, "VHNV", 4) == 0;
}

uint32_t StepS5PayloadSeed(uint32_t value) {
    return (value << 11u) ^ value;
}
}  // namespace

extern "C" VeaS5ContainerStatus vea_s5_decode_model_payload(
    const uint8_t* input,
    size_t input_size,
    uint8_t* output,
    size_t output_capacity,
    size_t* out_output_size) {
    if (out_output_size == nullptr || (input == nullptr && input_size != 0u)) {
        return VEA_S5_CONTAINER_STATUS_INVALID_ARGUMENT;
    }
    *out_output_size = 0u;

    // FUN_18002aa90 returns an empty byte range for inputs shorter than 16.
    if (input_size < 16u) return VEA_S5_CONTAINER_STATUS_OK;

    // The non-magic branch is a byte-for-byte copy, not an error.
    if (!HasS5PayloadTransformMagic(input)) {
        *out_output_size = input_size;
        if (input_size != 0u && (output == nullptr || output_capacity < input_size)) {
            return VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL;
        }
        if (input_size != 0u) std::memmove(output, input, input_size);
        return VEA_S5_CONTAINER_STATUS_OK;
    }

    uint64_t end_offset = static_cast<uint64_t>(ReadU32LE(input + 4)) + 8u;
    if (end_offset < 16u || static_cast<uint64_t>(input_size) < end_offset) {
        end_offset = static_cast<uint64_t>(input_size);
    }
    const size_t output_size = static_cast<size_t>(end_offset - 16u);
    *out_output_size = output_size;
    if (output_size != 0u && (output == nullptr || output_capacity < output_size)) {
        return VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL;
    }

    uint32_t seed = ReadU32LE(input + 12);
    uint32_t state_a = 0x159a55e5u;
    uint32_t state_b = 0x1f123bb5u;
    uint32_t state_c = 0x05491333u;
    uint32_t last_seed_source = seed;

    const size_t word_count = output_size / 4u;
    const size_t tail_count = output_size % 4u;
    for (size_t index = 0; index < word_count; ++index) {
        last_seed_source = state_a;
        const uint32_t previous_c = state_c;
        const uint32_t stepped_seed = StepS5PayloadSeed(seed);
        state_c = previous_c ^ (((previous_c >> 11u) ^ stepped_seed) >> 8u) ^ stepped_seed;

        const uint32_t cipher_word = ReadU32LE(input + 16u + index * 4u);
        const uint32_t decoded_word = state_c ^ cipher_word;
        output[index * 4u + 0u] = static_cast<uint8_t>(decoded_word & 0xffu);
        output[index * 4u + 1u] = static_cast<uint8_t>((decoded_word >> 8u) & 0xffu);
        output[index * 4u + 2u] = static_cast<uint8_t>((decoded_word >> 16u) & 0xffu);
        output[index * 4u + 3u] = static_cast<uint8_t>((decoded_word >> 24u) & 0xffu);

        seed = state_a;
        state_a = state_b;
        state_b = previous_c;
    }

    if (tail_count != 0u) {
        const size_t tail_input_offset = 16u + word_count * 4u;
        uint32_t packed_tail = 0u;
        for (size_t index = 0; index < tail_count; ++index) {
            packed_tail |= static_cast<uint32_t>(input[tail_input_offset + index])
                << static_cast<uint32_t>(index * 8u);
        }

        const uint32_t tail_state = StepS5PayloadSeed(last_seed_source);
        const uint32_t mixed = (((((state_c >> 11u) ^ tail_state) >> 8u) ^
            tail_state ^ state_c ^ packed_tail));
        const size_t tail_output_offset = word_count * 4u;
        for (size_t index = 0; index < tail_count; ++index) {
            output[tail_output_offset + index] = static_cast<uint8_t>(
                (mixed >> static_cast<uint32_t>(index * 8u)) & 0xffu);
        }
    }

    return VEA_S5_CONTAINER_STATUS_OK;
}

