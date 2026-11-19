#include "vea_s5_tensor.h"

#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace {

/*
 * Reproduces FUN_18003be50, the S5API approximate exponential used by
 * FUN_18012d460. The vendor implementation constructs the IEEE-754 result
 * from a truncated scaled polynomial. Volatile intermediates preserve the
 * scalar SSE rounding sequence and prevent contraction/reassociation.
 */
float S5ApproxExp(float input) {
    volatile float log2_input = input * 1.4426950216293335f;
    volatile float negative_correction = log2_input < 0.0f ? 1.0f : 0.0f;
    volatile float exponent_base = log2_input < -126.0f ? -126.0f : log2_input;

    volatile float integer_part = std::trunc(exponent_base);
    volatile float fraction = exponent_base - integer_part;
    fraction = fraction + negative_correction;

    volatile float denominator = 4.842525482177734f - fraction;
    volatile float rational = 27.728023529052734f / denominator;
    volatile float offset = exponent_base + 121.27405548095703f;
    volatile float combined = offset + rational;
    volatile float polynomial_correction = fraction * 1.4901291131973267f;
    combined = combined - polynomial_correction;
    volatile float scaled = combined * 8388608.0f;

    const int32_t encoded_bits = static_cast<int32_t>(scaled);
    return std::bit_cast<float>(static_cast<uint32_t>(encoded_bits));
}

float ClampS5ActivationInput(float value) {
    if (value < -8.0f) return -8.0f;
    if (value > 8.0f) return 8.0f;
    return value;
}

}  // namespace

extern "C" VeaS5TensorStatus vea_s5_apply_gated_activation_f32(
    float* planes, size_t count, int mode) {
    if (planes == nullptr || count == 0) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (count > static_cast<size_t>(std::numeric_limits<int32_t>::max() / 2)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    const size_t total = count * 2u;
    for (size_t i = 0; i < total; ++i) {
        planes[i] = ClampS5ActivationInput(planes[i]);
    }

    float* value_plane = planes;
    const float* gate_plane = planes + count;

    for (size_t i = 0; i < count; ++i) {
        if (mode == 0) {
            const float gate_exp = S5ApproxExp(-gate_plane[i]);
            const float gate_denominator = gate_exp + 1.0f;
            const float sigmoid_gate = 1.0f / gate_denominator;

            const float value_exp = S5ApproxExp(value_plane[i] * -2.0f);
            const float value_denominator = value_exp + 1.0f;
            const float tanh_value = 2.0f / value_denominator - 1.0f;
            value_plane[i] = sigmoid_gate * tanh_value;
        } else {
            const float gate_exp = S5ApproxExp(gate_plane[i] * -2.0f);
            const float gate_denominator = gate_exp + 1.0f;
            const float tanh_gate = 2.0f / gate_denominator - 1.0f;

            const float value_exp = S5ApproxExp(-value_plane[i]);
            const float value_denominator = value_exp + 1.0f;
            const float sigmoid_value = 1.0f / value_denominator;
            value_plane[i] = tanh_gate * sigmoid_value;
        }
    }

    return VEA_S5_TENSOR_STATUS_OK;
}
