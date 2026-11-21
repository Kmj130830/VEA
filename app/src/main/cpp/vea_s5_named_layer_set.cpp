#include "vea_s5_tensor.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>

namespace {

constexpr size_t kAlignment = VEA_S5_ARENA_ALIGNMENT;
constexpr size_t kPairBytes = sizeof(void*) * 2u;

struct DescriptorPair {
    VeaS5MatrixDescriptor* weights;
    VeaS5MatrixDescriptor* bias;
};

static_assert(sizeof(DescriptorPair) == 16u,
              "Named-layer descriptor pair must occupy 16 bytes");
static_assert(kPairBytes == 16u,
              "The recovered named-layer object is a 64-bit layout");

bool AddOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || a > std::numeric_limits<size_t>::max() - b) return true;
    *out = a + b;
    return false;
}

bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) {
        return true;
    }
    *out = a * b;
    return false;
}

bool AlignUp(size_t value, size_t alignment, size_t* out) {
    if (out == nullptr || alignment == 0u || (alignment & (alignment - 1u)) != 0u) {
        return false;
    }
    size_t sum = 0;
    if (AddOverflow(value, alignment - 1u, &sum)) return false;
    *out = sum & ~(alignment - 1u);
    return true;
}

bool AddArenaAllocation(size_t logical_bytes, size_t* total) {
    size_t reserved = 0;
    return AlignUp(logical_bytes, kAlignment, &reserved) &&
        !AddOverflow(*total, reserved, total);
}

bool AddLayerArenaSize(
    uint32_t axis0,
    uint32_t axis1,
    uint32_t matrix_count,
    size_t* total) {
    if (axis0 == 0u || axis1 == 0u || matrix_count == 0u || total == nullptr) {
        return false;
    }
    const uint64_t padded_axis0 =
        (static_cast<uint64_t>(axis0) + 7u) & ~static_cast<uint64_t>(7u);
    if (padded_axis0 > std::numeric_limits<size_t>::max()) return false;

    size_t descriptor_bytes = 0;
    size_t matrix_elements = 0;
    size_t matrix_bytes = 0;
    size_t bias_bytes = 0;
    if (MultiplyOverflow(matrix_count, sizeof(VeaS5MatrixDescriptor), &descriptor_bytes) ||
        MultiplyOverflow(static_cast<size_t>(padded_axis0), axis1, &matrix_elements) ||
        MultiplyOverflow(matrix_elements, sizeof(float), &matrix_bytes) ||
        MultiplyOverflow(static_cast<size_t>(padded_axis0), sizeof(float), &bias_bytes)) {
        return false;
    }

    if (!AddArenaAllocation(descriptor_bytes, total)) return false;
    for (uint32_t matrix = 0; matrix < matrix_count; ++matrix) {
        if (!AddArenaAllocation(matrix_bytes, total)) return false;
    }
    return AddArenaAllocation(sizeof(VeaS5MatrixDescriptor), total) &&
        AddArenaAllocation(bias_bytes, total);
}

}  // namespace

struct VeaS5NamedLayerParameterSet {
    VeaS5Arena* arena = nullptr;
    alignas(16) uint8_t descriptor_table[VEA_S5_NAMED_LAYER_DESCRIPTOR_TABLE_BYTES]{};
    std::array<uint32_t, VEA_S5_NAMED_LAYER_COUNT> axis0{};
    std::array<uint32_t, VEA_S5_NAMED_LAYER_COUNT> axis1{};
    std::array<uint32_t, VEA_S5_NAMED_LAYER_COUNT> padded_axis0{};
    std::array<uint32_t, VEA_S5_NAMED_LAYER_COUNT> matrix_counts{};
    std::array<uint32_t, VEA_S5_NAMED_LAYER_COUNT> pair_offsets{};
};

extern "C" VeaS5TensorStatus vea_s5_named_layer_parameter_set_create(
    const float* const* weight_sources,
    const size_t* weight_source_counts,
    const float* const* bias_sources,
    const size_t* bias_source_counts,
    const uint32_t* axis0_counts,
    const uint32_t* axis1_counts,
    const uint32_t* matrix_counts,
    const uint32_t* descriptor_pair_offsets,
    size_t layer_count,
    VeaS5NamedLayerParameterSet** out_set) {
    if (out_set == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_set = nullptr;
    if (weight_sources == nullptr || weight_source_counts == nullptr ||
        bias_sources == nullptr || bias_source_counts == nullptr ||
        axis0_counts == nullptr || axis1_counts == nullptr ||
        matrix_counts == nullptr || descriptor_pair_offsets == nullptr ||
        layer_count != VEA_S5_NAMED_LAYER_COUNT) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t arena_capacity = 32u;
    for (size_t layer = 0; layer < layer_count; ++layer) {
        const uint32_t expected_offset = 0x20u + static_cast<uint32_t>(layer) * 0x10u;
        if (descriptor_pair_offsets[layer] != expected_offset ||
            expected_offset + kPairBytes > VEA_S5_NAMED_LAYER_DESCRIPTOR_TABLE_BYTES ||
            weight_sources[layer] == nullptr || bias_sources[layer] == nullptr ||
            axis0_counts[layer] == 0u || axis1_counts[layer] == 0u ||
            matrix_counts[layer] == 0u) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }

        size_t expected_weights = 0;
        if (MultiplyOverflow(axis0_counts[layer], axis1_counts[layer], &expected_weights) ||
            MultiplyOverflow(expected_weights, matrix_counts[layer], &expected_weights)) {
            return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
        }
        if (weight_source_counts[layer] != expected_weights ||
            bias_source_counts[layer] != axis0_counts[layer]) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }

        if (!AddLayerArenaSize(
                axis0_counts[layer], axis1_counts[layer], matrix_counts[layer],
                &arena_capacity)) {
            return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
        }
    }

    auto* parameter_set = new (std::nothrow) VeaS5NamedLayerParameterSet();
    if (parameter_set == nullptr) return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;

    VeaS5TensorStatus status =
        vea_s5_arena_create(arena_capacity, &parameter_set->arena);
    if (status != VEA_S5_TENSOR_STATUS_OK) {
        delete parameter_set;
        return status;
    }

    for (size_t layer = 0; layer < layer_count; ++layer) {
        VeaS5MatrixDescriptor* weight_descriptors = nullptr;
        VeaS5MatrixDescriptor* bias_descriptor = nullptr;

        status = vea_s5_pack_matrix_bank_f32(
            parameter_set->arena,
            weight_sources[layer],
            axis0_counts[layer],
            axis1_counts[layer],
            matrix_counts[layer],
            &weight_descriptors);
        if (status != VEA_S5_TENSOR_STATUS_OK) {
            vea_s5_named_layer_parameter_set_destroy(parameter_set);
            return status;
        }

        status = vea_s5_pack_row_major_f32(
            parameter_set->arena,
            bias_sources[layer],
            axis0_counts[layer],
            1u,
            &bias_descriptor);
        if (status != VEA_S5_TENSOR_STATUS_OK) {
            vea_s5_named_layer_parameter_set_destroy(parameter_set);
            return status;
        }

        const DescriptorPair pair{weight_descriptors, bias_descriptor};
        std::memcpy(
            parameter_set->descriptor_table + descriptor_pair_offsets[layer],
            &pair,
            sizeof(pair));

        parameter_set->axis0[layer] = axis0_counts[layer];
        parameter_set->axis1[layer] = axis1_counts[layer];
        parameter_set->padded_axis0[layer] =
            (axis0_counts[layer] + 7u) & ~7u;
        parameter_set->matrix_counts[layer] = matrix_counts[layer];
        parameter_set->pair_offsets[layer] = descriptor_pair_offsets[layer];
    }

    *out_set = parameter_set;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" void vea_s5_named_layer_parameter_set_destroy(
    VeaS5NamedLayerParameterSet* parameter_set) {
    if (parameter_set == nullptr) return;
    vea_s5_arena_destroy(parameter_set->arena);
    parameter_set->arena = nullptr;
    delete parameter_set;
}

extern "C" VeaS5TensorStatus vea_s5_named_layer_parameter_set_get_layer(
    const VeaS5NamedLayerParameterSet* parameter_set,
    size_t layer_index,
    const VeaS5MatrixDescriptor** out_weight_descriptors,
    size_t* out_weight_descriptor_count,
    const VeaS5MatrixDescriptor** out_bias_descriptor,
    uint32_t* out_axis0_count,
    uint32_t* out_axis1_count,
    uint32_t* out_padded_axis0_count) {
    if (parameter_set == nullptr || out_weight_descriptors == nullptr ||
        out_weight_descriptor_count == nullptr || out_bias_descriptor == nullptr ||
        out_axis0_count == nullptr || out_axis1_count == nullptr ||
        out_padded_axis0_count == nullptr ||
        layer_index >= VEA_S5_NAMED_LAYER_COUNT) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    DescriptorPair pair{};
    std::memcpy(
        &pair,
        parameter_set->descriptor_table + parameter_set->pair_offsets[layer_index],
        sizeof(pair));
    if (pair.weights == nullptr || pair.bias == nullptr) {
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    }

    *out_weight_descriptors = pair.weights;
    *out_weight_descriptor_count = parameter_set->matrix_counts[layer_index];
    *out_bias_descriptor = pair.bias;
    *out_axis0_count = parameter_set->axis0[layer_index];
    *out_axis1_count = parameter_set->axis1[layer_index];
    *out_padded_axis0_count = parameter_set->padded_axis0[layer_index];
    return VEA_S5_TENSOR_STATUS_OK;
}
