#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <cassert>
#include <cstring>
#include <bit>
#include <cstdint>
#include <cmath>
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



    // FUN_180065510: contiguous [axis1][axis0] copy into an 8-column padded matrix.
    const float row_major_input[] = {
        1.0f, 2.0f, 3.0f,
        4.0f, 5.0f, 6.0f
    };
    VeaS5MatrixDescriptor* row_major_descriptor = nullptr;
    assert(vea_s5_pack_row_major_f32(
        arena, row_major_input, 3, 2, &row_major_descriptor) == VEA_S5_TENSOR_STATUS_OK);
    assert(row_major_descriptor != nullptr);
    assert(IsAligned(row_major_descriptor, VEA_S5_ARENA_ALIGNMENT));
    assert(IsAligned(row_major_descriptor->values, VEA_S5_ARENA_ALIGNMENT));
    assert(row_major_descriptor->axis0 == 3);
    assert(row_major_descriptor->axis1 == 2);
    assert(row_major_descriptor->padded_axis0 == 8);
    assert(row_major_descriptor->values[0] == 1.0f);
    assert(row_major_descriptor->values[1] == 2.0f);
    assert(row_major_descriptor->values[2] == 3.0f);
    assert(row_major_descriptor->values[8] == 4.0f);
    assert(row_major_descriptor->values[9] == 5.0f);
    assert(row_major_descriptor->values[10] == 6.0f);
    for (uint32_t row = 0; row < 2; ++row) {
        for (uint32_t col = 3; col < 8; ++col) {
            assert(row_major_descriptor->values[row * 8u + col] == 0.0f);
        }
    }

    // When axis0 is already a multiple of eight, the copy contains no padding.
    const float aligned_input[] = {
        1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f,
        9.0f, 10.0f, 11.0f, 12.0f, 13.0f, 14.0f, 15.0f, 16.0f
    };
    VeaS5MatrixDescriptor* aligned_descriptor = nullptr;
    assert(vea_s5_pack_row_major_f32(
        arena, aligned_input, 8, 2, &aligned_descriptor) == VEA_S5_TENSOR_STATUS_OK);
    assert(aligned_descriptor->padded_axis0 == 8);
    for (size_t i = 0; i < 16; ++i) {
        assert(aligned_descriptor->values[i] == aligned_input[i]);
    }

    VeaS5Arena* row_tiny = nullptr;
    assert(vea_s5_arena_create(32, &row_tiny) == VEA_S5_TENSOR_STATUS_OK);
    const size_t row_before = vea_s5_arena_used(row_tiny);
    VeaS5MatrixDescriptor* row_failed = nullptr;
    assert(vea_s5_pack_row_major_f32(
        row_tiny, row_major_input, 3, 2, &row_failed) == VEA_S5_TENSOR_STATUS_ARENA_EXHAUSTED);
    assert(row_failed == nullptr);
    assert(vea_s5_arena_used(row_tiny) == row_before);
    vea_s5_arena_destroy(row_tiny);



    // FUN_18005df50: transpose [axis0][axis1] to [axis1][padded_axis0].
    const float transposed_input[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
    VeaS5MatrixDescriptor* transposed_descriptor = nullptr;
    assert(vea_s5_pack_transposed_f32(
        arena, transposed_input, 3, 2, &transposed_descriptor) == VEA_S5_TENSOR_STATUS_OK);
    assert(transposed_descriptor->axis0 == 3);
    assert(transposed_descriptor->axis1 == 2);
    assert(transposed_descriptor->padded_axis0 == 8);
    assert(transposed_descriptor->values[0] == 1.0f);
    assert(transposed_descriptor->values[1] == 3.0f);
    assert(transposed_descriptor->values[2] == 5.0f);
    assert(transposed_descriptor->values[8] == 2.0f);
    assert(transposed_descriptor->values[9] == 4.0f);
    assert(transposed_descriptor->values[10] == 6.0f);
    for (uint32_t row = 0; row < 2; ++row) {
        for (uint32_t col = 3; col < 8; ++col) {
            assert(transposed_descriptor->values[row * 8u + col] == 0.0f);
        }
    }


    // FUN_18012d460 gated activation with the FUN_18003be50 approximate exp.
    float act_mode0[] = {1.0f, 0.0f, 0.0f, 0.0f};
    assert(vea_s5_apply_gated_activation_f32(act_mode0, 2, 0) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::bit_cast<uint32_t>(act_mode0[0]) == 0x3ec2f88cu);
    assert(act_mode0[1] == 0.0f);

    float act_mode1[] = {0.0f, 0.0f, 1.0f, 0.0f};
    assert(vea_s5_apply_gated_activation_f32(act_mode1, 2, 1) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::bit_cast<uint32_t>(act_mode1[0]) == 0x3ec2f88cu);
    assert(act_mode1[1] == 0.0f);

    // Both planes are clamped in place before activation.
    float act_clamp[] = {100.0f, -100.0f, 100.0f, -100.0f};
    assert(vea_s5_apply_gated_activation_f32(act_clamp, 2, 0) == VEA_S5_TENSOR_STATUS_OK);
    assert(act_clamp[2] == 8.0f && act_clamp[3] == -8.0f);
    assert(act_clamp[0] > 0.99f);
    assert(act_clamp[1] < 0.0f && act_clamp[1] > -0.001f);

    float act_invalid[] = {0.0f, 0.0f};
    assert(vea_s5_apply_gated_activation_f32(nullptr, 1, 0) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_apply_gated_activation_f32(act_invalid, 0, 0) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);


    // FUN_18012f280: 7-row, 7x7 depthwise convolution with reflect-without-edge-repeat borders.
    float dw_rows_storage[7][7] = {};
    const float* dw_rows[7] = {};
    for (uint32_t row = 0; row < 7; ++row) {
        dw_rows[row] = dw_rows_storage[row];
        for (uint32_t col = 0; col < 7; ++col) {
            dw_rows_storage[row][col] = static_cast<float>(row * 10u + col);
        }
    }
    float dw_weights[49];
    for (float& weight : dw_weights) weight = 1.0f;
    float dw_output[7] = {};
    assert(vea_s5_depthwise_conv7x7_reflect_f32(
        dw_rows, dw_weights, 7, dw_output) == VEA_S5_TENSOR_STATUS_OK);
    const float dw_expected[7] = {1554.0f, 1561.0f, 1582.0f, 1617.0f, 1652.0f, 1673.0f, 1680.0f};
    for (size_t i = 0; i < 7; ++i) assert(dw_output[i] == dw_expected[i]);

    // An impulse at the first kernel element exercises the left/right reflection indices.
    for (float& weight : dw_weights) weight = 0.0f;
    dw_weights[0] = 1.0f;
    assert(vea_s5_depthwise_conv7x7_reflect_f32(
        dw_rows, dw_weights, 7, dw_output) == VEA_S5_TENSOR_STATUS_OK);
    const float dw_impulse_expected[7] = {3.0f, 2.0f, 1.0f, 0.0f, 1.0f, 2.0f, 3.0f};
    for (size_t i = 0; i < 7; ++i) assert(dw_output[i] == dw_impulse_expected[i]);
    assert(vea_s5_depthwise_conv7x7_reflect_f32(
        dw_rows, dw_weights, 6, dw_output) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_depthwise_conv7x7_reflect_f32(
        nullptr, dw_weights, 7, dw_output) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_180024a90: form RNN parameter keys with layer index and direction suffix.
    char recurrent_name[128] = {};
    size_t recurrent_required = 0;
    assert(vea_s5_build_recurrent_parameter_name(
        "rnn", ".weight_ih_l", 0, 0, recurrent_name, sizeof(recurrent_name),
        &recurrent_required) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(recurrent_name, "rnn.weight_ih_l0") == 0);
    assert(recurrent_required == std::strlen("rnn.weight_ih_l0") + 1u);
    assert(vea_s5_build_recurrent_parameter_name(
        "rnn", ".weight_hh_l", 2, 1, recurrent_name, sizeof(recurrent_name),
        &recurrent_required) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(recurrent_name, "rnn.weight_hh_l2_reverse") == 0);
    assert(vea_s5_build_recurrent_parameter_name(
        "rnn", ".bias_ih_l", 12, 0, recurrent_name, sizeof(recurrent_name),
        &recurrent_required) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(recurrent_name, "rnn.bias_ih_l12") == 0);
    char too_small[8] = "stable";
    assert(vea_s5_build_recurrent_parameter_name(
        "rnn", ".weight_ih_l", 0, 1, too_small, sizeof(too_small),
        &recurrent_required) == VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);
    assert(recurrent_required > sizeof(too_small));
    assert(std::strcmp(too_small, "stable") == 0);
    assert(vea_s5_build_recurrent_parameter_name(
        nullptr, ".weight_ih_l", 0, 0, recurrent_name, sizeof(recurrent_name),
        &recurrent_required) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_1800d8720: residual affine merge dst = (dst + bias) * scale + residual.
    float residual_dst[7] = {1.0f, 2.0f, -1.0f, 0.0f, 4.0f, -3.0f, 0.5f};
    const float residual_src[7] = {10.0f, 20.0f, 30.0f, 40.0f, -2.0f, 3.0f, 0.5f};
    assert(vea_s5_residual_affine_add_f32(
        residual_dst, residual_src, 7, 2.0f, 0.5f) == VEA_S5_TENSOR_STATUS_OK);
    const float residual_expected[7] = {13.0f, 25.0f, 29.0f, 41.0f, 7.0f, -2.0f, 2.5f};
    for (size_t i = 0; i < 7; ++i) assert(residual_dst[i] == residual_expected[i]);
    float residual_alias[3] = {1.0f, 2.0f, 3.0f};
    assert(vea_s5_residual_affine_add_f32(
        residual_alias, residual_alias, 3, 0.5f, 1.0f) == VEA_S5_TENSOR_STATUS_OK);
    assert(residual_alias[0] == 2.0f && residual_alias[1] == 3.5f && residual_alias[2] == 5.0f);
    assert(vea_s5_residual_affine_add_f32(
        nullptr, residual_src, 7, 1.0f, 0.0f) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_residual_affine_add_f32(
        residual_dst, residual_src, 0, 1.0f, 0.0f) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_18012ca50: S5API scalar tanh-approximation GELU kernel.
    float gelu_values[] = {-3.0f, -1.0f, 0.0f, 0.5f, 1.0f, 2.0f, 3.0f};
    const float gelu_original[] = {-3.0f, -1.0f, 0.0f, 0.5f, 1.0f, 2.0f, 3.0f};
    assert(vea_s5_apply_gelu_f32(gelu_values, 7, 1.0f, 0.0f) == VEA_S5_TENSOR_STATUS_OK);
    for (size_t i = 0; i < 7; ++i) {
        const float x = gelu_original[i];
        const float reference = x / (1.0f + std::exp(-((x * x * x * 0.044715f + x) * 1.5957692f)));
        assert(std::fabs(gelu_values[i] - reference) <= 1.0e-6f);
    }
    assert(gelu_values[2] == 0.0f);
    float gelu_affine[] = {-1.0f, 0.0f, 1.0f};
    assert(vea_s5_apply_gelu_f32(gelu_affine, 3, 2.0f, -0.5f) == VEA_S5_TENSOR_STATUS_OK);
    for (size_t i = 0; i < 3; ++i) {
        const float x = 2.0f * (static_cast<float>(i) - 1.0f) - 0.5f;
        const float reference = x / (1.0f + std::exp(-((x * x * x * 0.044715f + x) * 1.5957692f)));
        assert(std::fabs(gelu_affine[i] - reference) <= 1.0e-6f);
    }
    assert(vea_s5_apply_gelu_f32(nullptr, 1, 1.0f, 0.0f) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_apply_gelu_f32(gelu_affine, 0, 1.0f, 0.0f) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // MRW_LNSTATS: raw sum and sum-of-squares reduction.
    const float stats_input[] = {1.0f, -2.0f, 3.0f, -4.0f};
    float stats_sum = 0.0f;
    float stats_square_sum = 0.0f;
    assert(vea_s5_reduce_sum_sumsq_f32(
        stats_input, 4, &stats_sum, &stats_square_sum) == VEA_S5_TENSOR_STATUS_OK);
    assert(stats_sum == -2.0f);
    assert(stats_square_sum == 30.0f);

    const float stats_single[] = {-3.0f};
    assert(vea_s5_reduce_sum_sumsq_f32(
        stats_single, 1, &stats_sum, &stats_square_sum) == VEA_S5_TENSOR_STATUS_OK);
    assert(stats_sum == -3.0f && stats_square_sum == 9.0f);
    assert(vea_s5_reduce_sum_sumsq_f32(
        nullptr, 1, &stats_sum, &stats_square_sum) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // S5API tagged binary numeric arrays: *F32, *F16, and *S32 + Base64.
    const char f32_encoded[] = "*F32AACAPwAAAEA=";  // [1.0f, 2.0f]
    float* decoded_f32 = nullptr;
    size_t decoded_f32_count = 0;
    assert(vea_s5_decode_typed_float_array(
        arena, f32_encoded, sizeof(f32_encoded) - 1,
        &decoded_f32, &decoded_f32_count) == VEA_S5_TENSOR_STATUS_OK);
    assert(decoded_f32_count == 2);
    assert(decoded_f32[0] == 1.0f && decoded_f32[1] == 2.0f);

    const char f16_encoded[] = "*F16ADwAwA==";  // binary16 [1.0f, -2.0f]
    float* decoded_f16 = nullptr;
    size_t decoded_f16_count = 0;
    assert(vea_s5_decode_typed_float_array(
        arena, f16_encoded, sizeof(f16_encoded) - 1,
        &decoded_f16, &decoded_f16_count) == VEA_S5_TENSOR_STATUS_OK);
    assert(decoded_f16_count == 2);
    assert(decoded_f16[0] == 1.0f && decoded_f16[1] == -2.0f);

    const char f16_subnormal[] = "*F16AQA=";
    float* decoded_subnormal = nullptr;
    size_t decoded_subnormal_count = 0;
    assert(vea_s5_decode_typed_float_array(
        arena, f16_subnormal, sizeof(f16_subnormal) - 1,
        &decoded_subnormal, &decoded_subnormal_count) == VEA_S5_TENSOR_STATUS_OK);
    assert(decoded_subnormal_count == 1);
    assert(decoded_subnormal[0] > 0.0f && decoded_subnormal[0] < 1.0e-6f);

    const char s32_encoded[] = "*S32wB3+/yoAAAA="; // [-123456, 42]
    int32_t* decoded_s32 = nullptr;
    size_t decoded_s32_count = 0;
    assert(vea_s5_decode_typed_int32_array(
        arena, s32_encoded, sizeof(s32_encoded) - 1,
        &decoded_s32, &decoded_s32_count) == VEA_S5_TENSOR_STATUS_OK);
    assert(decoded_s32_count == 2);
    assert(decoded_s32[0] == -123456 && decoded_s32[1] == 42);

    const char unpadded_f32[] = "*F32AACAPwAAAEA";
    float* decoded_unpadded = nullptr;
    size_t decoded_unpadded_count = 0;
    assert(vea_s5_decode_typed_float_array(
        arena, unpadded_f32, sizeof(unpadded_f32) - 1,
        &decoded_unpadded, &decoded_unpadded_count) == VEA_S5_TENSOR_STATUS_OK);
    assert(decoded_unpadded_count == 2 && decoded_unpadded[1] == 2.0f);

    const char malformed_f32[] = "*F32AQI=";
    assert(vea_s5_decode_typed_float_array(
        arena, malformed_f32, sizeof(malformed_f32) - 1,
        &decoded_unpadded, &decoded_unpadded_count) ==
        VEA_S5_TENSOR_STATUS_MALFORMED_DATA);
    const char wrong_type[] = "*S32AAAA";
    assert(vea_s5_decode_typed_float_array(
        arena, wrong_type, sizeof(wrong_type) - 1,
        &decoded_unpadded, &decoded_unpadded_count) ==
        VEA_S5_TENSOR_STATUS_TYPE_MISMATCH);
    const char invalid_base64[] = "*F32****";
    assert(vea_s5_decode_typed_float_array(
        arena, invalid_base64, sizeof(invalid_base64) - 1,
        &decoded_unpadded, &decoded_unpadded_count) ==
        VEA_S5_TENSOR_STATUS_MALFORMED_DATA);

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
