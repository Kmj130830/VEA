#include "vea_s5_tensor.h"

#include <cstddef>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

namespace {
bool AddOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || b > std::numeric_limits<size_t>::max() - a) return true;
    *out = a + b;
    return false;
}
bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_vtmg_bank_to_v0_naive_a_f32(
    const float* packed_vtmg, size_t packed_count,
    size_t output_features, size_t input_features,
    size_t padded_output_features, size_t banks,
    float* gemm_a, size_t gemm_a_count) {
    if (packed_vtmg == nullptr || gemm_a == nullptr ||
        output_features == 0u || input_features == 0u || banks == 0u ||
        padded_output_features < output_features ||
        (padded_output_features % VEA_S5_MATRIX_AXIS0_ALIGNMENT) != 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t source_bank_stride = 0u;
    size_t expected_source_count = 0u;
    size_t destination_bank_stride = 0u;
    size_t expected_destination_count = 0u;
    if (MultiplyOverflow(input_features, padded_output_features, &source_bank_stride) ||
        MultiplyOverflow(source_bank_stride, banks, &expected_source_count) ||
        MultiplyOverflow(output_features, input_features, &destination_bank_stride) ||
        MultiplyOverflow(destination_bank_stride, banks, &expected_destination_count)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    if (packed_count != expected_source_count || gemm_a_count != expected_destination_count) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    for (size_t bank = 0; bank < banks; ++bank) {
        const float* source_bank = packed_vtmg + bank * source_bank_stride;
        float* destination_bank = gemm_a + bank * destination_bank_stride;
        for (size_t output = 0; output < output_features; ++output) {
            float* destination_row = destination_bank + output * input_features;
            for (size_t input = 0; input < input_features; ++input) {
                destination_row[input] = source_bank[input * padded_output_features + output];
            }
        }
    }
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_banked_linear_projection_f32(
    const float* weights, size_t weight_row_stride, size_t weight_bank_stride,
    const float* input, const float* bias, float* output,
    size_t input_features, size_t output_features, size_t positions, size_t banks) {
    if (weights == nullptr || input == nullptr || output == nullptr ||
        input_features == 0u || output_features == 0u || positions == 0u || banks == 0u ||
        weight_row_stride < input_features) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t weight_last_row = 0u, weight_matrix_extent = 0u;
    size_t minimum_bank_stride = 0u, last_bank_offset = 0u;
    size_t input_count = 0u, output_count = 0u, scratch_count = 0u;
    if (MultiplyOverflow(output_features - 1u, weight_row_stride, &weight_last_row) ||
        AddOverflow(weight_last_row, input_features, &weight_matrix_extent) ||
        MultiplyOverflow(output_features, positions, &scratch_count) ||
        MultiplyOverflow(input_features, positions, &input_count) ||
        MultiplyOverflow(scratch_count, banks, &output_count) ||
        MultiplyOverflow(banks - 1u, weight_bank_stride, &last_bank_offset)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    minimum_bank_stride = weight_matrix_extent;
    if (weight_bank_stride < minimum_bank_stride) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    size_t weight_total = 0u;
    if (AddOverflow(last_bank_offset, weight_matrix_extent, &weight_total)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    (void)input_count;
    (void)output_count;
    (void)weight_total;

    try {
        std::vector<float> scratch(scratch_count, 0.0f);
        for (size_t bank = 0; bank < banks; ++bank) {
            const float* weight_matrix = weights + bank * weight_bank_stride;
            const VeaS5TensorStatus status = vea_s5_gemm_row_major_f32(
                weight_matrix, input, scratch.data(),
                output_features, positions, input_features,
                1.0f, weight_row_stride, positions, 0.0f, positions);
            if (status != VEA_S5_TENSOR_STATUS_OK) return status;

            for (size_t feature = 0; feature < output_features; ++feature) {
                const float* scratch_row = scratch.data() + feature * positions;
                float* output_row = output + feature * (positions * banks) + bank;
                for (size_t position = 0; position < positions; ++position) {
                    output_row[position * banks] = scratch_row[position];
                }
            }
        }

        if (bias != nullptr) {
            const size_t row_size = positions * banks;
            for (size_t feature = 0; feature < output_features; ++feature) {
                float* row = output + feature * row_size;
                const float value = bias[feature];
                for (size_t index = 0; index < row_size; ++index) {
                    row[index] += value;
                }
            }
        }
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (const std::length_error&) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
