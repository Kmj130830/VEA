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
extern "C" VeaS5TensorStatus vea_s5_spp_fft_build_plan_f32(
    uint32_t fft_size, uint32_t* split_table, size_t split_table_count,
    float* coefficients, size_t coefficient_count) {
    if (fft_size < 4u || (fft_size & (fft_size - 1u)) != 0u ||
        split_table == nullptr || coefficients == nullptr) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    // The original plan uses 32-bit sizes and allocates N/2 float coefficients.
    if (fft_size > (1u << 24u)) return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;

    const size_t expected_coefficients = static_cast<size_t>(fft_size >> 1u);
    const double split_extent =
        std::sqrt(static_cast<double>(fft_size >> 1u)) + 2.0;
    const size_t expected_split_count = static_cast<size_t>(split_extent);
    if (split_table_count < expected_split_count ||
        coefficient_count < expected_coefficients) {
        return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;
    }

    std::fill_n(split_table, split_table_count, 0u);
    std::fill_n(coefficients, coefficient_count, 0.0f);

    const uint32_t quarter_size = fft_size >> 2u;
    split_table[0] = quarter_size;
    split_table[1] = 1u;

    uint32_t half_quarter = quarter_size >> 1u;
    size_t stage_base = 0u;
    float stage_angle = 0.0f;
    float stage_cos_quarter = 0.0f;

    if (half_quarter >= 3u) {
        stage_angle = std::atan(1.0f) / static_cast<float>(half_quarter);
        stage_cos_quarter =
            std::cos(static_cast<float>(half_quarter) * stage_angle);
        coefficients[0] = 1.0f;
        coefficients[1] = stage_cos_quarter;

        if (half_quarter == 4u) {
            coefficients[2] = std::cos(stage_angle * 2.0f);
            coefficients[3] = std::sin(stage_angle * 2.0f);
        } else if (half_quarter > 4u) {
            split_table[2] = 0u;
            split_table[3] = 0x10u;

            if (quarter_size > 0x20u) {
                uint32_t remaining = quarter_size;
                uint32_t radix = 2u;
                while (remaining > 0x20u) {
                    for (uint32_t index = radix; index < radix * 2u; ++index) {
                        const uint32_t value = split_table[index];
                        const size_t first_destination =
                            static_cast<size_t>(radix) + index;
                        const size_t second_destination =
                            static_cast<size_t>(index) + radix * 2u;
                        if (first_destination >= expected_split_count ||
                            second_destination >= expected_split_count) {
                            return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
                        }
                        split_table[first_destination] = value * 4u;
                        split_table[second_destination] =
                            value * 4u + radix * 0x10u;
                    }
                    remaining >>= 2u;
                    radix <<= 1u;
                }
            }

            const float cos_two = std::cos(stage_angle * 2.0f);
            const float cos_six = std::cos(stage_angle * 6.0f);
            if (cos_two == 0.0f || cos_six == 0.0f ||
                !std::isfinite(cos_two) || !std::isfinite(cos_six)) {
                return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
            }
            coefficients[2] = 0.5f / cos_two;
            coefficients[3] = 0.5f / cos_six;

            for (uint32_t index = 4u; index < half_quarter; index += 4u) {
                if (static_cast<size_t>(index) + 3u >= expected_coefficients) {
                    return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
                }
                const float angle = static_cast<float>(index) * stage_angle;
                const float triple_angle = angle * 3.0f;
                coefficients[index] = std::cos(angle);
                coefficients[index + 1u] = std::sin(angle);
                coefficients[index + 2u] = std::cos(triple_angle);
                coefficients[index + 3u] = -std::sin(triple_angle);
            }
        }

        // FUN_180082220's recursive radix-stage table construction.
        for (;;) {
            const size_t next_stage = stage_base + half_quarter;
            half_quarter >>= 1u;
            if (next_stage + 1u >= expected_coefficients) {
                return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
            }
            coefficients[next_stage] = 1.0f;
            coefficients[next_stage + 1u] = stage_cos_quarter;

            if (half_quarter == 4u) {
                if (stage_base + 5u >= expected_coefficients ||
                    next_stage + 3u >= expected_coefficients) {
                    return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
                }
                coefficients[next_stage + 2u] = coefficients[stage_base + 4u];
                coefficients[next_stage + 3u] = coefficients[stage_base + 5u];
                stage_base = next_stage;
                continue;
            }

            if (half_quarter > 4u) {
                if (stage_base + 6u >= expected_coefficients ||
                    next_stage + 3u >= expected_coefficients) {
                    return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
                }
                const float cosine_two = coefficients[stage_base + 4u];
                const float cosine_six = coefficients[stage_base + 6u];
                if (cosine_two == 0.0f || cosine_six == 0.0f) {
                    return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
                }
                coefficients[next_stage + 2u] = 0.5f / cosine_two;
                coefficients[next_stage + 3u] = 0.5f / cosine_six;

                const uint32_t copy_groups = ((half_quarter - 5u) >> 2u) + 1u;
                for (uint32_t group = 0u; group < copy_groups; ++group) {
                    const size_t source = stage_base + 8u +
                        static_cast<size_t>(group) * 8u;
                    const size_t destination = next_stage + 4u +
                        static_cast<size_t>(group) * 4u;
                    if (source + 3u >= expected_coefficients ||
                        destination + 3u >= expected_coefficients) {
                        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
                    }
                    coefficients[destination] = coefficients[source];
                    coefficients[destination + 1u] = coefficients[source + 1u];
                    coefficients[destination + 2u] = coefficients[source + 2u];
                    coefficients[destination + 3u] = coefficients[source + 3u];
                }
                stage_base = next_stage;
                continue;
            }
            break;
        }
    }

    // FUN_18004ec80's second half fills the terminal cosine/sine table.
    if (static_cast<uint64_t>(split_table[1]) * 4u < fft_size) {
        split_table[1] = quarter_size;
        if (quarter_size > 1u) {
            const uint32_t eighth_size = fft_size >> 3u;
            if (eighth_size == 0u ||
                static_cast<size_t>(quarter_size) + eighth_size >= expected_coefficients) {
                return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
            }
            const float angle_step =
                std::atan(1.0f) / static_cast<float>(eighth_size);
            const float quarter_cosine =
                std::cos(static_cast<float>(eighth_size) * angle_step);
            coefficients[quarter_size] = quarter_cosine;
            coefficients[quarter_size + eighth_size] = quarter_cosine * 0.5f;
            for (uint32_t index = 1u; index < eighth_size; ++index) {
                const float angle = static_cast<float>(index) * angle_step;
                coefficients[quarter_size + index] = std::cos(angle) * 0.5f;
                coefficients[(quarter_size * 2u) - index] = std::sin(angle) * 0.5f;
            }
        }
    }

    for (size_t index = 0; index < expected_coefficients; ++index) {
        if (!std::isfinite(coefficients[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
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

extern "C" VeaS5TensorStatus vea_s5_spp_interpolate_peak_f32(
    const float* magnitude_phase, size_t bin_count, size_t peak_index,
    float* out_magnitude, float* out_phase) {
    if (magnitude_phase == nullptr || out_magnitude == nullptr || out_phase == nullptr ||
        bin_count == 0u || peak_index >= bin_count ||
        bin_count > std::numeric_limits<size_t>::max() / 2u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    const size_t sample_count = bin_count * 2u;
    for (size_t index = 0; index < sample_count; ++index) {
        if (!std::isfinite(magnitude_phase[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    const float center_magnitude = magnitude_phase[peak_index * 2u];
    const float center_phase = magnitude_phase[peak_index * 2u + 1u];
    float interpolated_magnitude = center_magnitude;
    float interpolated_phase = center_phase;

    if (peak_index > 0u && peak_index + 1u < bin_count) {
        const float left_magnitude = magnitude_phase[(peak_index - 1u) * 2u];
        const float right_magnitude = magnitude_phase[(peak_index + 1u) * 2u];
        if (center_magnitude > left_magnitude && center_magnitude > right_magnitude) {
            const float half_difference = (right_magnitude - left_magnitude) * 0.5f;
            const float curvature =
                (left_magnitude + right_magnitude) * 0.5f - center_magnitude;
            const float denominator = curvature + curvature;
            if (denominator != 0.0f && std::isfinite(denominator)) {
                const float offset = -half_difference / denominator;
                const float refined_magnitude = center_magnitude -
                    (half_difference * half_difference) / (curvature * 4.0f);
                if (!std::isfinite(offset) || !std::isfinite(refined_magnitude)) {
                    return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
                }
                if (offset >= 0.0f) {
                    const float right_phase = magnitude_phase[(peak_index + 1u) * 2u + 1u];
                    const float phase_delta = right_phase - center_phase;
                    const float wrapped_delta = phase_delta -
                        std::nearbyint(phase_delta * 0.15915494f) * 6.2831855f;
                    interpolated_phase = center_phase + wrapped_delta * offset;
                } else {
                    const float left_phase = magnitude_phase[(peak_index - 1u) * 2u + 1u];
                    const float phase_delta = center_phase - left_phase;
                    const float wrapped_delta = phase_delta -
                        std::nearbyint(phase_delta * 0.15915494f) * 6.2831855f;
                    interpolated_phase = left_phase + wrapped_delta * (offset + 1.0f);
                }
                interpolated_magnitude = refined_magnitude;
            }
        }
    }

    if (!std::isfinite(interpolated_magnitude) || !std::isfinite(interpolated_phase)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_magnitude = interpolated_magnitude;
    *out_phase = interpolated_phase;
    return VEA_S5_TENSOR_STATUS_OK;
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
