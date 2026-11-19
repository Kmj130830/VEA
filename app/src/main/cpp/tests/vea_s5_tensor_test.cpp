#include "vea_s5_tensor.h"

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

    vea_s5_arena_reset(arena);
    assert(vea_s5_arena_used(arena) == 0);
    vea_s5_arena_destroy(arena);
    return 0;
}
