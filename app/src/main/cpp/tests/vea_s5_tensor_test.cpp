#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <cassert>
#include <cstring>
#include <bit>
#include <cstdint>
#include <cmath>
#include <limits>
#include <array>
#include <vector>

namespace {

bool IsAligned(const void* pointer, uintptr_t alignment) {
    return pointer != nullptr &&
        (reinterpret_cast<uintptr_t>(pointer) & (alignment - 1u)) == 0;
}

}  // namespace

int main() {
    assert(vea_s5_tensor_abi_version() == VEA_S5_TENSOR_ABI_VERSION);

    // S5API digest key: salted MD5 words formatted as 4 x 8 lowercase hex digits.
    static constexpr uint8_t key_abc[] = {'a', 'b', 'c'};
    char hashed_key[33] = {};
    size_t hashed_key_required = 0;
    assert(vea_s5_hash_parameter_key(
        key_abc, sizeof(key_abc), hashed_key, sizeof(hashed_key),
        &hashed_key_required) == VEA_S5_TENSOR_STATUS_OK);
    assert(hashed_key_required == 33);
    assert(std::strcmp(hashed_key, "de6c737db70b0f13e4db2c5fd5015e9c") == 0);

    // A 48-byte key forces MD5 to process two 64-byte blocks after the salt.
    static constexpr char long_key[] =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL";
    static_assert(sizeof(long_key) - 1u == 48u);
    assert(vea_s5_hash_parameter_key(
        reinterpret_cast<const uint8_t*>(long_key), sizeof(long_key) - 1u,
        hashed_key, sizeof(hashed_key), &hashed_key_required) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(hashed_key, "c70b98ec2a90d909a5a0977f6d8cd948") == 0);

    // These names are present as typed-tensor keys in the supplied
    // VOCALOID7.vtmg model member. Their JSON keys are canonical MD5 byte hex.
    static constexpr char pre1_weight[] = "pre1.weight";
    static constexpr char conv1_bias[] = "conv1.bias";
    assert(vea_s5_hash_parameter_key(
        reinterpret_cast<const uint8_t*>(pre1_weight), sizeof(pre1_weight) - 1u,
        hashed_key, sizeof(hashed_key), &hashed_key_required) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(hashed_key, "f8b15a70e9fe50b828e5803435580c63") == 0);
    assert(vea_s5_hash_parameter_key(
        reinterpret_cast<const uint8_t*>(conv1_bias), sizeof(conv1_bias) - 1u,
        hashed_key, sizeof(hashed_key), &hashed_key_required) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::strcmp(hashed_key, "bf1fb08e8ffbab205dbb90120465e856") == 0);

    char small_hashed_key[8] = "stable";
    assert(vea_s5_hash_parameter_key(
        key_abc, sizeof(key_abc), small_hashed_key, sizeof(small_hashed_key),
        &hashed_key_required) == VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);
    assert(hashed_key_required == 33);
    assert(std::strcmp(small_hashed_key, "stable") == 0);
    assert(vea_s5_hash_parameter_key(
        nullptr, 1, hashed_key, sizeof(hashed_key), &hashed_key_required) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // Build the persistent 9-layer descriptor-pair table at the same
    // object offsets used by FUN_180068190 (0x20 through 0xa0).
    constexpr size_t kLayerCount = VEA_S5_NAMED_LAYER_COUNT;
    const std::array<uint32_t, kLayerCount> layer_axis0 = {
        2u, 2u, 4u, 4u, 4u, 4u, 4u, 4u, 1u
    };
    const std::array<uint32_t, kLayerCount> layer_axis1 = {
        3u, 2u, 2u, 3u, 2u, 3u, 2u, 3u, 2u
    };
    const std::array<uint32_t, kLayerCount> layer_banks = {
        1u, 1u, 3u, 1u, 3u, 1u, 3u, 1u, 1u
    };
    std::array<uint32_t, kLayerCount> layer_offsets{};
    std::array<std::vector<float>, kLayerCount> layer_weights;
    std::array<std::vector<float>, kLayerCount> layer_biases;
    std::array<const float*, kLayerCount> layer_weight_ptrs{};
    std::array<const float*, kLayerCount> layer_bias_ptrs{};
    std::array<size_t, kLayerCount> layer_weight_counts{};
    std::array<size_t, kLayerCount> layer_bias_counts{};
    for (size_t layer = 0; layer < kLayerCount; ++layer) {
        layer_offsets[layer] = 0x20u + static_cast<uint32_t>(layer) * 0x10u;
        const size_t weight_count =
            static_cast<size_t>(layer_axis0[layer]) * layer_axis1[layer] * layer_banks[layer];
        layer_weights[layer].resize(weight_count);
        layer_biases[layer].resize(layer_axis0[layer]);
        for (size_t index = 0; index < weight_count; ++index) {
            layer_weights[layer][index] =
                static_cast<float>(1000u * layer + index + 1u);
        }
        for (size_t index = 0; index < layer_biases[layer].size(); ++index) {
            layer_biases[layer][index] =
                static_cast<float>(100u * layer + index + 1u);
        }
        layer_weight_ptrs[layer] = layer_weights[layer].data();
        layer_bias_ptrs[layer] = layer_biases[layer].data();
        layer_weight_counts[layer] = layer_weights[layer].size();
        layer_bias_counts[layer] = layer_biases[layer].size();
    }

    VeaS5NamedLayerParameterSet* named_layer_set = nullptr;
    assert(vea_s5_named_layer_parameter_set_create(
        layer_weight_ptrs.data(), layer_weight_counts.data(),
        layer_bias_ptrs.data(), layer_bias_counts.data(),
        layer_axis0.data(), layer_axis1.data(), layer_banks.data(),
        layer_offsets.data(), kLayerCount, &named_layer_set) == VEA_S5_TENSOR_STATUS_OK);
    assert(named_layer_set != nullptr);

    for (size_t layer = 0; layer < kLayerCount; ++layer) {
        const VeaS5MatrixDescriptor* weight_descriptors = nullptr;
        const VeaS5MatrixDescriptor* bias_descriptor = nullptr;
        size_t weight_descriptor_count = 0;
        uint32_t axis0 = 0, axis1 = 0, padded_axis0 = 0;
        assert(vea_s5_named_layer_parameter_set_get_layer(
            named_layer_set, layer, &weight_descriptors, &weight_descriptor_count,
            &bias_descriptor, &axis0, &axis1, &padded_axis0) ==
            VEA_S5_TENSOR_STATUS_OK);
        assert(weight_descriptors != nullptr);
        assert(bias_descriptor != nullptr);
        assert(weight_descriptor_count == layer_banks[layer]);
        assert(axis0 == layer_axis0[layer]);
        assert(axis1 == layer_axis1[layer]);
        assert(padded_axis0 == ((axis0 + 7u) & ~7u));
        assert(weight_descriptors[0].axis0 == axis0);
        assert(weight_descriptors[0].axis1 == axis1);
        assert(weight_descriptors[0].padded_axis0 == padded_axis0);
        assert(bias_descriptor->axis0 == axis0);
        assert(bias_descriptor->axis1 == 1u);
        assert(bias_descriptor->padded_axis0 == padded_axis0);

        const size_t source_index =
            (static_cast<size_t>(axis0 - 1u) * axis1 + (axis1 - 1u)) *
            layer_banks[layer];
        const size_t output_index =
            static_cast<size_t>(axis1 - 1u) * padded_axis0 + (axis0 - 1u);
        for (size_t bank = 0; bank < weight_descriptor_count; ++bank) {
            assert(weight_descriptors[bank].values[output_index] ==
                layer_weights[layer][source_index + bank]);
        }
        assert(bias_descriptor->values[axis0 - 1u] == layer_biases[layer].back());
        for (uint32_t padding = axis0; padding < padded_axis0; ++padding) {
            assert(bias_descriptor->values[padding] == 0.0f);
        }
    }

    // Invalid table offsets and incomplete source vectors are rejected atomically.
    auto invalid_offsets = layer_offsets;
    invalid_offsets[4] += 8u;
    VeaS5NamedLayerParameterSet* rejected_set = nullptr;
    assert(vea_s5_named_layer_parameter_set_create(
        layer_weight_ptrs.data(), layer_weight_counts.data(),
        layer_bias_ptrs.data(), layer_bias_counts.data(),
        layer_axis0.data(), layer_axis1.data(), layer_banks.data(),
        invalid_offsets.data(), kLayerCount, &rejected_set) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(rejected_set == nullptr);

    auto invalid_weight_counts = layer_weight_counts;
    invalid_weight_counts[2] -= 1u;
    assert(vea_s5_named_layer_parameter_set_create(
        layer_weight_ptrs.data(), invalid_weight_counts.data(),
        layer_bias_ptrs.data(), layer_bias_counts.data(),
        layer_axis0.data(), layer_axis1.data(), layer_banks.data(),
        layer_offsets.data(), kLayerCount, &rejected_set) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(rejected_set == nullptr);

    vea_s5_named_layer_parameter_set_destroy(named_layer_set);

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

    // Model-constructor mode confirmed at FUN_180057459: one layer, bias, bidirectional.
    VeaS5RnnDirectionLayout rnn_model_forward{};
    assert(vea_s5_rnn_direction_layout(80, 128, 1, 1, 1, 0, 0, &rnn_model_forward) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(rnn_model_forward.layer_index == 0 && rnn_model_forward.direction_index == 0);
    assert(rnn_model_forward.input_features == 80);
    assert(rnn_model_forward.gate_features == 384);
    assert(rnn_model_forward.direction_count == 2);
    assert(rnn_model_forward.weight_ih_elements == 30720);
    assert(rnn_model_forward.weight_hh_elements == 49152);
    assert(vea_s5_rnn_direction_layout(80, 128, 1, 1, 1, 0, 1, &rnn_model_forward) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(rnn_model_forward.direction_index == 1);
    assert(rnn_model_forward.input_features == 80);

    // Recovered recurrent parameter sizes: 3 gate blocks and stacked directions.
    VeaS5RnnDirectionLayout rnn_layout{};
    assert(vea_s5_rnn_direction_layout(80, 128, 3, 1, 1, 0, 0, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(rnn_layout.layer_index == 0 && rnn_layout.direction_index == 0);
    assert(rnn_layout.input_features == 80 && rnn_layout.hidden_features == 128);
    assert(rnn_layout.gate_features == 384 && rnn_layout.direction_count == 2);
    assert(rnn_layout.weight_ih_elements == 30720);
    assert(rnn_layout.weight_hh_elements == 49152);
    assert(rnn_layout.bias_ih_elements == 384 && rnn_layout.bias_hh_elements == 384);

    assert(vea_s5_rnn_direction_layout(80, 128, 3, 1, 1, 1, 1, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(rnn_layout.input_features == 256);
    assert(rnn_layout.weight_ih_elements == 98304);
    assert(rnn_layout.weight_hh_elements == 49152);

    assert(vea_s5_rnn_direction_layout(80, 128, 3, 0, 0, 2, 0, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(rnn_layout.input_features == 128 && rnn_layout.direction_count == 1);
    assert(rnn_layout.weight_ih_elements == 49152);
    assert(rnn_layout.bias_ih_elements == 0 && rnn_layout.bias_hh_elements == 0);

    assert(vea_s5_rnn_direction_layout(80, 128, 3, 1, 1, 0, 2, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_rnn_direction_layout(80, 128, 3, 2, 0, 0, 0, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_rnn_direction_layout(80, 128, 0, 1, 0, 0, 0, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_rnn_direction_layout(1, UINT32_MAX, 1, 1, 0, 0, 0, &rnn_layout) ==
        VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW);

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

    // FUN_1800c5d50: mean, variance clamp, epsilon, sqrt and reciprocal std.
    float norm_mean = 0.0f;
    float norm_inv_std = 0.0f;
    assert(vea_s5_compute_mean_invstd_f32(
        10.0f, 30.0f, 4, 0.001f, &norm_mean, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(norm_mean - 2.5f) < 1.0e-7f);
    assert(std::fabs(norm_inv_std - (1.0f / std::sqrt(1.251f))) < 1.0e-6f);

    // A negative round-off variance is clamped to zero before epsilon.
    assert(vea_s5_compute_mean_invstd_f32(
        4.0f, 3.0f, 4, 0.25f, &norm_mean, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(norm_mean == 1.0f);
    assert(std::fabs(norm_inv_std - 2.0f) < 1.0e-6f);

    // Constant signal uses epsilon as the variance floor.
    assert(vea_s5_compute_mean_invstd_f32(
        4.0f, 4.0f, 4, 0.01f, &norm_mean, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(norm_mean == 1.0f);
    assert(std::fabs(norm_inv_std - 10.0f) < 1.0e-5f);

    assert(vea_s5_compute_mean_invstd_f32(
        1.0f, 1.0f, 0, 0.01f, &norm_mean, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_compute_mean_invstd_f32(
        1.0f, 1.0f, 1, -0.01f, &norm_mean, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_compute_mean_invstd_f32(
        1.0f, 1.0f, 1, 0.01f, nullptr, &norm_inv_std) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // S5API named-kernel table v0_naive -> FUN_18013aa50.
    // Confirmed SGEMM order: M,N,K,alpha,A,lda,B,ldb,beta,C,ldc.
    const float gemm_a[] = {
        1.0f, 2.0f, 3.0f, 99.0f,
        4.0f, 5.0f, 6.0f, 99.0f
    };
    const float gemm_b[] = {
        7.0f, 8.0f, 99.0f,
        9.0f, 10.0f, 99.0f,
        11.0f, 12.0f, 99.0f
    };
    float gemm_c[] = {
        100.0f, 200.0f, 77.0f,
        300.0f, 400.0f, 88.0f
    };
    assert(vea_s5_gemm_row_major_f32(
        gemm_a, gemm_b, gemm_c, 2, 2, 3, 2.0f, 4, 3, 0.5f, 3) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float gemm_expected[] = {166.0f, 228.0f, 77.0f, 428.0f, 508.0f, 88.0f};
    for (size_t i = 0; i < 6; ++i) assert(gemm_c[i] == gemm_expected[i]);

    // Exercise scalar K tails around the vendor's four-wide unrolled body.
    for (size_t gemm_k = 1; gemm_k <= 9; ++gemm_k) {
        float a_values[9] = {};
        float b_values[9] = {};
        for (size_t i = 0; i < gemm_k; ++i) {
            a_values[i] = static_cast<float>(i + 1u);
            b_values[i] = 2.0f;
        }
        float c_value = 0.0f;
        const float expected = static_cast<float>(gemm_k * (gemm_k + 1u));
        assert(vea_s5_gemm_row_major_f32(
            a_values, b_values, &c_value, 1, 1, gemm_k,
            1.0f, gemm_k, 1, 0.0f, 1) == VEA_S5_TENSOR_STATUS_OK);
        assert(c_value == expected);
    }

    // K==0 scales C by beta and does not dereference A or B.
    float gemm_zero_k[] = {2.0f, -4.0f};
    assert(vea_s5_gemm_row_major_f32(
        nullptr, nullptr, gemm_zero_k, 1, 2, 0, 1.0f, 0, 2, 0.25f, 2) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(gemm_zero_k[0] == 0.5f && gemm_zero_k[1] == -1.0f);

    assert(vea_s5_gemm_row_major_f32(
        gemm_a, gemm_b, gemm_c, 2, 2, 3, 1.0f, 2, 3, 0.0f, 3) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_gemm_row_major_f32(
        gemm_a, gemm_b, gemm_c, 2, 2, 3, 1.0f, 4, 1, 0.0f, 3) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_gemm_row_major_f32(
        nullptr, gemm_b, gemm_c, 1, 2, 3, 1.0f, 3, 2, 0.0f, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_gemm_row_major_f32(
        gemm_a, gemm_b, nullptr, 1, 2, 3, 1.0f, 3, 2, 0.0f, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_1800c6390: run one GEMM per bank, scatter to [feature][position][bank],
    // then add the shared per-output-feature bias.
    const float banked_weights[] = {
        1.0f, 0.0f, 0.0f, 1.0f,   // bank 0: identity
        2.0f, 0.0f, 0.0f, 3.0f    // bank 1: diagonal scale
    };
    const float banked_input[] = {
        1.0f, 2.0f, 3.0f,         // input feature 0 over 3 positions
        4.0f, 5.0f, 6.0f          // input feature 1 over 3 positions
    };
    const float banked_bias[] = {0.5f, -1.0f};
    float banked_output[12] = {};
    assert(vea_s5_banked_linear_projection_f32(
        banked_weights, 2, 4, banked_input, banked_bias, banked_output,
        2, 2, 3, 2) == VEA_S5_TENSOR_STATUS_OK);
    const float banked_expected[] = {
        1.5f, 2.5f, 2.5f, 4.5f, 3.5f, 6.5f,
        3.0f, 11.0f, 4.0f, 14.0f, 5.0f, 17.0f
    };
    for (size_t i = 0; i < 12; ++i) assert(banked_output[i] == banked_expected[i]);

    // Padded weight rows/banks are accepted and padding remains outside the logical product.
    const float banked_weights_padded[] = {
        1.0f, 0.0f, 91.0f, 92.0f,
        0.0f, 1.0f, 93.0f, 94.0f,
        2.0f, 0.0f, 95.0f, 96.0f,
        0.0f, 3.0f, 97.0f, 98.0f
    };
    float banked_padded_output[12] = {};
    assert(vea_s5_banked_linear_projection_f32(
        banked_weights_padded, 4, 8, banked_input, nullptr, banked_padded_output,
        2, 2, 3, 2) == VEA_S5_TENSOR_STATUS_OK);
    const float banked_no_bias_expected[] = {
        1.0f, 2.0f, 2.0f, 4.0f, 3.0f, 6.0f,
        4.0f, 12.0f, 5.0f, 15.0f, 6.0f, 18.0f
    };
    for (size_t i = 0; i < 12; ++i) assert(banked_padded_output[i] == banked_no_bias_expected[i]);

    assert(vea_s5_banked_linear_projection_f32(
        nullptr, 2, 4, banked_input, nullptr, banked_output, 2, 2, 3, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_banked_linear_projection_f32(
        banked_weights, 1, 4, banked_input, nullptr, banked_output, 2, 2, 3, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // Integrated scalar block: 7x7 depthwise -> global stats -> expand/GELU/project -> residual.
    constexpr size_t block_channels = 1;
    constexpr size_t block_positions = 7;
    constexpr size_t block_expanded = 2;
    float block_history[block_channels * 7 * block_positions] = {};
    for (size_t row = 0; row < 7; ++row) {
        for (size_t pos = 0; pos < block_positions; ++pos) {
            block_history[row * block_positions + pos] = static_cast<float>(pos + 1u);
        }
    }
    float block_dw_weights[49] = {};
    block_dw_weights[3u * 7u + 3u] = 1.0f;
    const float block_expand_weights[] = {1.0f, 2.0f};
    const float block_bias_a[] = {0.0f, 0.0f};
    const float block_bias_b[] = {0.0f, 0.0f};
    const float block_project_weights[] = {1.0f, 0.5f};
    const float block_residual[] = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
    const float block_residual_scale[] = {2.0f};
    const float block_residual_bias[] = {0.25f};
    float block_output[7] = {};
    assert(vea_s5_run_residual_dw_mlp_block_f32(
        block_history, block_dw_weights, block_expand_weights,
        block_bias_a, block_bias_b, block_project_weights,
        block_residual, block_residual_scale, block_residual_bias, block_output,
        block_channels, block_positions, block_expanded, 0.25f) ==
        VEA_S5_TENSOR_STATUS_OK);
    // Depthwise center impulse leaves 1..7. Global mean=4, var=4, invStd=1/sqrt(4.25).
    const float block_inv_std = 1.0f / std::sqrt(4.25f);
    for (size_t pos = 0; pos < block_positions; ++pos) {
        const float x = static_cast<float>(pos + 1u) * block_inv_std;
        const float gelu1 = x / (1.0f + std::exp(-((x*x*x*0.044715f + x)*1.5957692f)));
        const float x2 = 2.0f * static_cast<float>(pos + 1u) * block_inv_std;
        const float gelu2 = x2 / (1.0f + std::exp(-((x2*x2*x2*0.044715f + x2)*1.5957692f)));
        const float projected = gelu1 + 0.5f * gelu2;
        const float expected = (projected + 0.25f) * 2.0f + 0.5f;
        assert(std::fabs(block_output[pos] - expected) < 1.0e-5f);
    }
    assert(vea_s5_run_residual_dw_mlp_block_f32(
        nullptr, block_dw_weights, block_expand_weights, block_bias_a, block_bias_b,
        block_project_weights, block_residual, block_residual_scale, block_residual_bias,
        block_output, block_channels, block_positions, block_expanded, 0.25f) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // S5API FUN_18002aa90 payload transform: seeded multiword and tail vectors.
    const uint8_t payload_tail_input[] = {
        0x56, 0x54, 0x42, 0x52, 0x0f, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x78, 0x56, 0x34, 0x12,
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77
    };
    const uint8_t payload_tail_expected[] = {
        0x65, 0x20, 0x4d, 0xf1, 0x16, 0x3a, 0xfb
    };
    size_t decoded_payload_size = 0u;
    assert(vea_s5_decode_model_payload(
        payload_tail_input, sizeof(payload_tail_input), nullptr, 0u,
        &decoded_payload_size) == VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL);
    assert(decoded_payload_size == sizeof(payload_tail_expected));
    uint8_t decoded_payload[sizeof(payload_tail_expected)] = {};
    assert(vea_s5_decode_model_payload(
        payload_tail_input, sizeof(payload_tail_input),
        decoded_payload, sizeof(decoded_payload), &decoded_payload_size) ==
        VEA_S5_CONTAINER_STATUS_OK);
    assert(decoded_payload_size == sizeof(payload_tail_expected));
    assert(std::memcmp(decoded_payload, payload_tail_expected, sizeof(decoded_payload)) == 0);

    const uint8_t payload_multiword_input[] = {
        0x56, 0x54, 0x4d, 0x47, 0x14, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xef, 0xbe, 0xad, 0xde,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c
    };
    const uint8_t payload_multiword_expected[] = {
        0xb2, 0x8d, 0xa3, 0xb2, 0xfa, 0xd7, 0x55, 0x79,
        0xfa, 0x89, 0x18, 0xf3
    };
    uint8_t decoded_multiword[sizeof(payload_multiword_expected)] = {};
    assert(vea_s5_decode_model_payload(
        payload_multiword_input, sizeof(payload_multiword_input),
        decoded_multiword, sizeof(decoded_multiword), &decoded_payload_size) ==
        VEA_S5_CONTAINER_STATUS_OK);
    assert(decoded_payload_size == sizeof(payload_multiword_expected));
    assert(std::memcmp(decoded_multiword, payload_multiword_expected,
        sizeof(decoded_multiword)) == 0);

    // Insufficient capacity is atomic, and unrecognized data uses the copy path.
    uint8_t too_small_output[sizeof(payload_tail_expected) - 1u];
    std::memset(too_small_output, 0xa5, sizeof(too_small_output));
    assert(vea_s5_decode_model_payload(
        payload_tail_input, sizeof(payload_tail_input),
        too_small_output, sizeof(too_small_output), &decoded_payload_size) ==
        VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL);
    for (uint8_t value : too_small_output) assert(value == 0xa5u);

    uint8_t passthrough_input[20] = {
        0x4a, 0x53, 0x4f, 0x4e, 0x10, 0x00, 0x00, 0x00
    };
    for (size_t index = 8; index < sizeof(passthrough_input); ++index) {
        passthrough_input[index] = static_cast<uint8_t>(index * 3u);
    }
    uint8_t passthrough_output[sizeof(passthrough_input)] = {};
    assert(vea_s5_decode_model_payload(
        passthrough_input, sizeof(passthrough_input),
        passthrough_output, sizeof(passthrough_output), &decoded_payload_size) ==
        VEA_S5_CONTAINER_STATUS_OK);
    assert(decoded_payload_size == sizeof(passthrough_input));
    assert(std::memcmp(passthrough_input, passthrough_output, sizeof(passthrough_input)) == 0);

    const uint8_t short_model_header[12] = {};
    assert(vea_s5_decode_model_payload(
        short_model_header, sizeof(short_model_header), nullptr, 0u,
        &decoded_payload_size) == VEA_S5_CONTAINER_STATUS_OK);
    assert(decoded_payload_size == 0u);

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
        assert(parsed.raw_bytes_after_format_word[0] == raw_header[12]);
        assert(parsed.raw_bytes_after_format_word[123] == raw_header[135]);
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
