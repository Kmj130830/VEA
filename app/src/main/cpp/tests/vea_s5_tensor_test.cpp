#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <cassert>
#include <cstdint>
#include <limits>

namespace {

bool IsAligned(const void* pointer, uintptr_t alignment) {
    return pointer != nullptr &&
        (reinterpret_cast<uintptr_t>(pointer) & (alignment - 1u)) == 0;
}

}  // namespace

int main() {
    assert(vea_s5_tensor_abi_version() == VEA_S5_TENSOR_ABI_VERSION);

    VeaS5Arena* arena = nullptr;
    assert(vea_s5_arena_create(8192, &arena) == VEA_S5_TENSOR_STATUS_OK);
    assert(arena != nullptr);

    // Typed-array conversion: the S5API node reader reads doubles then casts to float32.
    const double input_doubles[] = {1.25, -2.5, 3.125, 0.0};
    float* converted = nullptr;
    assert(vea_s5_convert_f64_array_to_f32(
        arena, input_doubles, 4, &converted) == VEA_S5_TENSOR_STATUS_OK);
    assert(IsAligned(converted, VEA_S5_ARENA_ALIGNMENT));
    assert(converted[0] == 1.25f);
    assert(converted[1] == -2.5f);
    assert(converted[2] == 3.125f);
    assert(converted[3] == 0.0f);

    // Two matrices, 3 x 2 logical dimensions; axis0 is padded to 8.
    // Source layout: [axis0][axis1][matrix].
    const float interleaved[] = {
        10.0f, 100.0f, 11.0f, 101.0f,
        20.0f, 200.0f, 21.0f, 201.0f,
        30.0f, 300.0f, 31.0f, 301.0f
    };
    VeaS5MatrixDescriptor* descriptors = nullptr;
    assert(vea_s5_pack_matrix_bank_f32(
        arena, interleaved, 3, 2, 2, &descriptors) == VEA_S5_TENSOR_STATUS_OK);
    assert(IsAligned(descriptors, VEA_S5_ARENA_ALIGNMENT));
    assert(IsAligned(descriptors[0].values, VEA_S5_ARENA_ALIGNMENT));
    assert(IsAligned(descriptors[1].values, VEA_S5_ARENA_ALIGNMENT));

    assert(descriptors[0].axis0 == 3);
    assert(descriptors[0].axis1 == 2);
    assert(descriptors[0].padded_axis0 == 8);
    assert(descriptors[0].reserved == 0);
    assert(descriptors[1].axis0 == 3);
    assert(descriptors[1].axis1 == 2);
    assert(descriptors[1].padded_axis0 == 8);

    // Matrix 0 (rows are axis1, columns are axis0).
    assert(descriptors[0].values[0] == 10.0f);
    assert(descriptors[0].values[1] == 20.0f);
    assert(descriptors[0].values[2] == 30.0f);
    assert(descriptors[0].values[8] == 11.0f);
    assert(descriptors[0].values[9] == 21.0f);
    assert(descriptors[0].values[10] == 31.0f);
    // Matrix 1.
    assert(descriptors[1].values[0] == 100.0f);
    assert(descriptors[1].values[1] == 200.0f);
    assert(descriptors[1].values[2] == 300.0f);
    assert(descriptors[1].values[8] == 101.0f);
    assert(descriptors[1].values[9] == 201.0f);
    assert(descriptors[1].values[10] == 301.0f);

    // Padding is deterministic and safe for wide SIMD loads.
    for (uint32_t row = 0; row < 2; ++row) {
        for (uint32_t col = 3; col < 8; ++col) {
            assert(descriptors[0].values[row * 8u + col] == 0.0f);
            assert(descriptors[1].values[row * 8u + col] == 0.0f);
        }
    }

    // Failure is atomic: arena used count is unchanged on exhaustion.
    VeaS5Arena* tiny = nullptr;
    assert(vea_s5_arena_create(64, &tiny) == VEA_S5_TENSOR_STATUS_OK);
    const size_t before = vea_s5_arena_used(tiny);
    VeaS5MatrixDescriptor* failed = nullptr;
    assert(vea_s5_pack_matrix_bank_f32(
        tiny, interleaved, 3, 2, 2, &failed) == VEA_S5_TENSOR_STATUS_ARENA_EXHAUSTED);
    assert(failed == nullptr);
    assert(vea_s5_arena_used(tiny) == before);
    vea_s5_arena_destroy(tiny);

    // Invalid input is rejected before mutating the arena.
    const size_t checkpoint = vea_s5_arena_used(arena);
    assert(vea_s5_pack_matrix_bank_f32(
        arena, nullptr, 3, 2, 2, &descriptors) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_arena_used(arena) == checkpoint);


    // Fixed 136-byte outer header observed in all five supplied V7 model files.
    uint8_t raw_header[VEA_S5_CONTAINER_HEADER_SIZE] = {};
    raw_header[4] = 100u;
    raw_header[8] = 16u;
    for (size_t i = 12; i < sizeof(raw_header); ++i) {
        raw_header[i] = static_cast<uint8_t>(i ^ 0x5au);
    }
    const char* magics[] = {"VTBR", "VPIT", "VHNV", "VTMG"};
    const uint32_t kinds[] = {
        VEA_S5_CONTAINER_KIND_VTBR, VEA_S5_CONTAINER_KIND_VPIT,
        VEA_S5_CONTAINER_KIND_VHNV, VEA_S5_CONTAINER_KIND_VTMG
    };
    assert(vea_s5_container_header_size() == 136u);
    for (size_t i = 0; i < 4; ++i) {
        VeaS5ContainerHeader parsed{};
        for (size_t b = 0; b < 4; ++b) raw_header[b] = static_cast<uint8_t>(magics[i][b]);
        assert(vea_s5_parse_container_header(
            raw_header, sizeof(raw_header) + 100u, &parsed) == VEA_S5_CONTAINER_STATUS_OK);
        assert(parsed.kind == kinds[i]);
        assert(parsed.payload_size == 100u);
        assert(parsed.format_word == 16u);
        assert(parsed.opaque_metadata[0] == raw_header[12]);
        assert(parsed.opaque_metadata[123] == raw_header[135]);
    }
    VeaS5ContainerHeader invalid_header{};
    assert(vea_s5_parse_container_header(raw_header, sizeof(raw_header) + 99u, &invalid_header)
        == VEA_S5_CONTAINER_STATUS_INVALID_LENGTH);
    const uint8_t invalid_magic[VEA_S5_CONTAINER_HEADER_SIZE] = {};
    assert(vea_s5_parse_container_header(invalid_magic, sizeof(invalid_magic), &invalid_header)
        == VEA_S5_CONTAINER_STATUS_INVALID_MAGIC);
    assert(vea_s5_parse_container_header(raw_header, 135u, &invalid_header)
        == VEA_S5_CONTAINER_STATUS_INVALID_LENGTH);

    vea_s5_arena_reset(arena);
    assert(vea_s5_arena_used(arena) == 0);
    vea_s5_arena_destroy(arena);
    return 0;
}
