#include "vea_s5_tensor.h"

#include <cstddef>
#include <limits>
#include <new>
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
    } catch (...) {
        return VEA_S5_TENSOR_STATUS_INTERNAL_ERROR;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
