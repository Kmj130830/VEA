#include "vea_s5_tensor.h"

#include <cmath>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

extern "C" VeaS5TensorStatus vea_s5_spp_rotate_circular_f32(
    float* values, size_t count, int32_t shift) {
    if (count == 0u) return VEA_S5_TENSOR_STATUS_OK;
    if (values == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    if (count > static_cast<size_t>(std::numeric_limits<int64_t>::max())) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    for (size_t index = 0; index < count; ++index) {
        if (!std::isfinite(values[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    if (count == 1u) return VEA_S5_TENSOR_STATUS_OK;
    const int64_t length = static_cast<int64_t>(count);
    int64_t left_shift = static_cast<int64_t>(shift) % length;
    if (left_shift < 0) left_shift += length;
    if (left_shift == 0) return VEA_S5_TENSOR_STATUS_OK;

    try {
        std::vector<float> rotated(count);
        const size_t offset = static_cast<size_t>(left_shift);
        const size_t first_count = count - offset;
        for (size_t index = 0; index < first_count; ++index) {
            rotated[index] = values[index + offset];
        }
        for (size_t index = 0; index < offset; ++index) {
            rotated[first_count + index] = values[index];
        }
        for (size_t index = 0; index < count; ++index) {
            values[index] = rotated[index];
        }
        return VEA_S5_TENSOR_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    }
}

extern "C" VeaS5TensorStatus vea_s5_spp_scale_buffer_f32(
    float* values, size_t count, float scale) {
    if (count == 0u) return VEA_S5_TENSOR_STATUS_OK;
    if (values == nullptr || !std::isfinite(scale)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    // Validate the entire operation before mutating the input buffer.
    for (size_t index = 0; index < count; ++index) {
        if (!std::isfinite(values[index]) || !std::isfinite(values[index] * scale)) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < count; ++index) {
        values[index] *= scale;
    }
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_spp_lookup_table_f64(
    const double* table, size_t table_count, size_t segment_count,
    double scale, double fallback_value, double x, double* out_value) {
    if (out_value == nullptr || !std::isfinite(scale) || scale < 0.0 ||
        !std::isfinite(fallback_value) || !std::isfinite(x)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (segment_count == std::numeric_limits<size_t>::max()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    if (segment_count > 0u && (table == nullptr || table_count < segment_count + 1u)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    const double scaled = std::fabs(x) * scale;
    if (!std::isfinite(scaled)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    if (segment_count == 0u || scaled >= static_cast<double>(segment_count)) {
        *out_value = fallback_value;
        return VEA_S5_TENSOR_STATUS_OK;
    }

    // FUN_180139870's conversion is floor(scaled) for non-negative inputs.
    const size_t index = static_cast<size_t>(std::floor(scaled));
    if (index >= segment_count || index + 1u >= table_count) {
        return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    }
    const double left = table[index];
    const double right = table[index + 1u];
    if (!std::isfinite(left) || !std::isfinite(right)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    const double fraction = scaled - static_cast<double>(index);
    const double result = (right - left) * fraction + left;
    if (!std::isfinite(result)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_value = result;
    return VEA_S5_TENSOR_STATUS_OK;
}
extern "C" VeaS5TensorStatus vea_s5_spp_fft_log2_floor_u32(
    uint32_t value, int32_t* out_index) {
    if (out_index == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_index = value == 0u
        ? -1
        : static_cast<int32_t>(std::bit_width(value) - 1u);
    return VEA_S5_TENSOR_STATUS_OK;
}
/** Advances the four-word SPP xorshift state and returns the new fourth word. */
static uint32_t vea_s5_spp_next_random_u32(uint32_t state[4]) {
    const uint32_t first = state[0];
    const uint32_t t = (first << 11u) ^ first;
    const uint32_t last = state[3];
    const uint32_t next = (((last >> 11u) ^ t) >> 8u) ^ last ^ t;
    state[0] = state[1];
    state[1] = state[2];
    state[2] = last;
    state[3] = next;
    return next;
}

extern "C" VeaS5TensorStatus vea_s5_spp_generate_phase_noise_f32(
    const float* magnitudes, size_t count, uint8_t edge_mode,
    uint32_t state[4], float* output_complex, size_t output_float_count) {
    if (count == 0u) {
        return output_float_count == 0u
            ? VEA_S5_TENSOR_STATUS_OK
            : VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (magnitudes == nullptr || state == nullptr || output_complex == nullptr) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (count > std::numeric_limits<size_t>::max() / 2u) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    const size_t expected_float_count = count * 2u;
    if (output_float_count != expected_float_count) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t index = 0; index < count; ++index) {
        if (!std::isfinite(magnitudes[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    // Work on a local state copy so invalid input never advances the caller's RNG.
    uint32_t local_state[4] = {state[0], state[1], state[2], state[3]};
    std::vector<float> local_output;
    try {
        local_output.resize(expected_float_count);
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (const std::length_error&) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    const uint32_t first_word = vea_s5_spp_next_random_u32(local_state);
    const int first_sign = std::bit_cast<int32_t>(first_word) < 0 ? -1 : 1;
    local_output[0] = static_cast<float>(first_sign) * magnitudes[0];
    local_output[1] = 0.0f;

    const bool include_last_bin_in_phase_loop = (edge_mode & 0x01u) != 0u;
    const size_t phase_loop_end = include_last_bin_in_phase_loop
        ? count
        : count - 1u;
    constexpr float kUniformPhaseScale = 3.7450704e-7f;
    for (size_t bin = 1u; bin < phase_loop_end; ++bin) {
        const uint32_t random_word = vea_s5_spp_next_random_u32(local_state);
        const float angle = static_cast<float>(random_word >> 8u) * kUniformPhaseScale;
        local_output[bin * 2u] = std::cos(angle) * magnitudes[bin];
        local_output[bin * 2u + 1u] = std::sin(angle) * magnitudes[bin];
    }

    if (!include_last_bin_in_phase_loop) {
        const uint32_t last_word = vea_s5_spp_next_random_u32(local_state);
        const int last_sign = std::bit_cast<int32_t>(last_word) < 0 ? -1 : 1;
        local_output[(count - 1u) * 2u] = static_cast<float>(last_sign) * magnitudes[count - 1u];
        local_output[(count - 1u) * 2u + 1u] = 0.0f;
    }

    for (size_t index = 0; index < expected_float_count; ++index) {
        if (!std::isfinite(local_output[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < expected_float_count; ++index) {
        output_complex[index] = local_output[index];
    }
    for (size_t index = 0; index < 4u; ++index) state[index] = local_state[index];
    return VEA_S5_TENSOR_STATUS_OK;
}
extern "C" VeaS5TensorStatus vea_s5_spp_peak_weight_f32(
    const float config[5], float coordinate, float* out_weight) {
    if (config == nullptr || out_weight == nullptr || !std::isfinite(coordinate)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t index = 0; index < 5u; ++index) {
        if (!std::isfinite(config[index])) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    const float period = config[0];
    const float low_edge = config[1];
    const float center = config[2];
    const float high_edge = config[3];
    const float amplitude = config[4];
    if (period <= 0.0f || low_edge <= 0.0f || center < low_edge || high_edge < center) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    const float quotient = coordinate / period;
    if (!std::isfinite(quotient)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    float wrapped = coordinate - std::floor(quotient) * period;
    if (!std::isfinite(wrapped)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    // Keep round-off at the periodic boundary inside the canonical interval.
    if (wrapped < 0.0f) wrapped += period;
    if (wrapped >= period) wrapped -= period;

    float weight = 0.0f;
    if (low_edge <= wrapped) {
        if (wrapped < center) {
            weight = ((wrapped - low_edge) * -amplitude) / low_edge;
        } else if (wrapped < high_edge) {
            weight = ((1.0f - (wrapped - center)) * -amplitude) / low_edge - amplitude;
        }
    }
    if (!std::isfinite(weight)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    *out_weight = weight;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_spp_find_weighted_peak_f32(
    const float* values, size_t value_count, size_t value_stride,
    size_t start_index, size_t end_index, float bin_spacing, float denominator,
    const float weight_config[5], size_t* out_peak_index, float* out_peak_score) {
    if (values == nullptr || weight_config == nullptr || out_peak_index == nullptr ||
        out_peak_score == nullptr || value_count == 0u ||
        (value_stride != 1u && value_stride != 2u) ||
        start_index > end_index || end_index >= value_count ||
        !std::isfinite(bin_spacing) || bin_spacing < 0.0f ||
        !std::isfinite(denominator) || denominator <= 0.0f) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (end_index > std::numeric_limits<size_t>::max() / value_stride) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    size_t best_index = start_index;
    float best_score = 0.0f;
    bool have_best = false;
    for (size_t index = start_index; index <= end_index; ++index) {
        const size_t value_offset = index * value_stride;
        const float magnitude = values[value_offset];
        if (!std::isfinite(magnitude)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        const float coordinate = (static_cast<float>(index) * bin_spacing) / denominator;
        if (!std::isfinite(coordinate)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        float weight = 0.0f;
        const VeaS5TensorStatus weight_status = vea_s5_spp_peak_weight_f32(
            weight_config, coordinate, &weight);
        if (weight_status != VEA_S5_TENSOR_STATUS_OK) return weight_status;
        const float score = magnitude + weight;
        if (!std::isfinite(score)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        if (!have_best || score > best_score) {
            best_index = index;
            best_score = score;
            have_best = true;
        }
        if (index == end_index) break; // Avoid size_t wrap at SIZE_MAX.
    }
    if (!have_best) return VEA_S5_TENSOR_STATUS_MALFORMED_DATA;
    *out_peak_index = best_index;
    *out_peak_score = best_score;
    return VEA_S5_TENSOR_STATUS_OK;
}
