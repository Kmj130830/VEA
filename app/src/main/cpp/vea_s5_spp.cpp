#include "vea_s5_tensor.h"

#include <algorithm>
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

    if (quarter_size >= 3u) {
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

        if (half_quarter >= 3u) {
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

extern "C" VeaS5TensorStatus vea_s5_spp_fft_small_leaf_f32(
    uint32_t fft_size, int32_t direction, float* interleaved, size_t value_count) {
    if ((fft_size != 4u && fft_size != 8u) ||
        interleaved == nullptr || value_count != static_cast<size_t>(fft_size)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    float values[8] = {};
    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(interleaved[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
        values[index] = interleaved[index];
    }

    if (fft_size == 4u) {
        const float real0 = values[0];
        const float imag0 = values[1];
        values[0] = values[2] + real0;
        values[1] = values[3] + imag0;
        values[2] = real0 - values[2];
        values[3] = imag0 - values[3];
    } else if (direction < 0) {
        const float pair_26_real = values[2] + values[6];
        const float pair_26_imag = values[3] + values[7];
        const float pair_15_real = values[1] + values[5];
        const float diff_15_real = values[1] - values[5];
        const float diff_37_imag = values[3] - values[7];
        const float diff_26_real = values[2] - values[6];
        const float pair_04_real = values[0] + values[4];
        const float diff_04_real = values[0] - values[4];

        values[1] = pair_26_imag + pair_15_real;
        values[5] = pair_15_real - pair_26_imag;
        values[0] = pair_26_real + pair_04_real;
        values[4] = pair_04_real - pair_26_real;
        values[3] = diff_15_real - diff_26_real;
        values[7] = diff_26_real + diff_15_real;
        values[2] = diff_37_imag + diff_04_real;
        values[6] = diff_04_real - diff_37_imag;
    } else {
        const float pair_26_real = values[2] + values[6];
        const float pair_37_imag = values[3] + values[7];
        const float pair_04_real = values[0] + values[4];
        const float diff_04_real = values[0] - values[4];
        const float diff_26_real = values[2] - values[6];
        const float diff_37_imag = values[3] - values[7];
        const float pair_15_imag = values[1] + values[5];
        const float diff_15_imag = values[1] - values[5];

        values[0] = pair_26_real + pair_04_real;
        values[4] = pair_04_real - pair_26_real;
        values[1] = pair_37_imag + pair_15_imag;
        values[5] = pair_15_imag - pair_37_imag;
        values[2] = diff_04_real - diff_37_imag;
        values[6] = diff_37_imag + diff_04_real;
        values[3] = diff_26_real + diff_15_imag;
        values[7] = diff_15_imag - diff_26_real;
    }

    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(values[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < value_count; ++index) {
        interleaved[index] = values[index];
    }
    return VEA_S5_TENSOR_STATUS_OK;
}


/**
 * The native 16-complex-point leaf is shared by both direction branches.
 * Its direction-dependent output permutation is applied by the dispatcher.
 */
static void vea_s5_spp_fft16_butterfly(float* p, float twiddle) {
    float f3 = p[4] + p[12];
    float f4 = p[4] - p[12];
    float f8 = p[1] - p[9];
    float f10 = p[1] + p[9];
    float f6 = p[0] - p[8];
    float f12 = p[0] + p[8];
    float f1 = f3 + f12;
    f12 = f12 - f3;
    f3 = p[5] + p[13];
    float f14 = p[3] - p[11];
    float f19 = p[5] - p[13];
    float f17 = p[3] + p[11];
    float f2 = f3 + f10;
    f10 = f10 - f3;
    float f16 = f4 + f8;
    f8 = f8 - f4;
    float f15 = f6 - f19;
    f4 = p[6] + p[14];
    float f5 = p[6] - p[14];
    f19 = f19 + f6;
    float f11 = p[2] - p[10];
    float f18 = p[2] + p[10];
    f6 = p[7] + p[15];
    float f9 = p[7] - p[15];
    float f13 = f4 + f18;
    float f7 = f5 + f14;
    f18 = f18 - f4;
    f4 = f11 - f9;
    f9 = f9 + f11;
    f14 = f14 - f5;
    f11 = f6 + f17;
    f17 = f17 - f6;
    f6 = (f4 - f7) * twiddle;
    f5 = (f7 + f4) * twiddle;
    f4 = (f9 - f14) * twiddle;
    f3 = (f14 + f9) * twiddle;

    p[8] = f6 + f15;
    p[10] = f15 - f6;
    p[9] = f5 + f16;
    p[12] = f19 - f3;
    p[11] = f16 - f5;
    p[14] = f3 + f19;
    p[15] = f8 - f4;
    p[13] = f4 + f8;
    p[0] = f13 + f1;
    p[2] = f1 - f13;
    p[1] = f11 + f2;
    p[3] = f2 - f11;
    p[5] = f18 + f10;
    p[7] = f10 - f18;
    p[4] = f12 - f17;
    p[6] = f17 + f12;
}

/**
 * The native 32-complex-point leaf is shared by both direction branches.
 * Its direction-dependent output permutation is applied by the dispatcher.
 */
static void vea_s5_spp_fft32_butterfly(
    float* p, float twiddle_pi_over_4,
    float twiddle_pi_over_8_cos, float twiddle_pi_over_8_sin) {
    float f8 = p[8] + p[24];
    float f11 = p[8] - p[24];
    float f5 = p[9] + p[25];
    float f15 = p[9] - p[25];
    float f31 = p[1] + p[17];
    float f28 = p[0] + p[16];
    float f13 = p[0] - p[16];
    float f19 = p[1] - p[17];
    float f1 = f5 + f31;
    float f2 = f8 + f28;
    f28 = f28 - f8;
    f31 = f31 - f5;
    f5 = f13 - f15;
    f15 = f15 + f13;
    float f3 = f11 + f19;
    f19 = f19 - f11;
    f11 = p[11] + p[27];
    float f14 = p[11] - p[27];
    f8 = p[10] + p[26];
    float f12 = p[10] - p[26];
    float f29 = p[2] + p[18];
    float f16 = p[2] - p[18];
    float f32 = p[3] + p[19];
    float f20 = p[3] - p[19];
    float f4 = f8 + f29;
    f29 = f29 - f8;
    f13 = f16 - f14;
    f8 = f11 + f32;
    f32 = f32 - f11;
    f11 = f12 + f20;
    f20 = f20 - f12;
    f14 = f14 + f16;
    float f6 = f13 * twiddle_pi_over_8_cos -
        f11 * twiddle_pi_over_8_sin;
    float f9 = f13 * twiddle_pi_over_8_sin +
        f11 * twiddle_pi_over_8_cos;
    float f24 = p[4] + p[20];
    float f17 = p[4] - p[20];
    float f21 = f20 * twiddle_pi_over_8_sin +
        f14 * twiddle_pi_over_8_cos;
    f16 = f14 * twiddle_pi_over_8_sin -
        f20 * twiddle_pi_over_8_cos;
    float f10 = p[12] + p[28];
    float f7 = p[13] + p[29];
    f13 = p[12] - p[28];
    f12 = p[13] - p[29];
    float f22 = p[5] - p[21];
    float f26 = p[5] + p[21];
    f11 = f10 + f24;
    f24 = f24 - f10;
    float f35 = f13 + f22;
    f22 = f22 - f13;
    f14 = p[14] + p[30];
    f20 = p[14] - p[30];
    f13 = f7 + f26;
    f26 = f26 - f7;
    f7 = f17 - f12;
    f12 = f12 + f17;
    f10 = p[15] + p[31];
    float f27 = p[15] - p[31];
    float f23 = (f22 - f12) * twiddle_pi_over_4;
    float f34 = (f7 - f35) * twiddle_pi_over_4;
    float f36 = (f35 + f7) * twiddle_pi_over_4;
    float f38 = p[7] + p[23];
    f35 = p[7] - p[23];
    float f33 = (f22 + f12) * twiddle_pi_over_4;
    float f37 = p[6] + p[22];
    f7 = p[6] - p[22];
    f12 = f14 + f37;
    f17 = f7 - f27;
    f27 = f27 + f7;
    f22 = f20 + f35;
    f35 = f35 - f20;
    f37 = f37 - f14;
    f14 = f10 + f38;
    f38 = f38 - f10;
    float f25 = f17 * twiddle_pi_over_8_sin -
        f22 * twiddle_pi_over_8_cos;
    float f30 = f22 * twiddle_pi_over_8_sin +
        f17 * twiddle_pi_over_8_cos;
    f22 = f27 * twiddle_pi_over_8_cos -
        f35 * twiddle_pi_over_8_sin;
    f35 = f27 * twiddle_pi_over_8_sin +
        f35 * twiddle_pi_over_8_cos;
    f17 = f15 - f33;
    f33 = f33 + f15;
    f20 = f19 - f23;
    f23 = f23 + f19;
    f7 = f16 - f22;
    f10 = f21 - f35;
    f35 = f35 + f21;
    f22 = f22 + f16;

    p[24] = f7 + f17;
    p[25] = f10 + f20;
    p[26] = f17 - f7;
    p[27] = f20 - f10;
    p[30] = f35 + f33;
    f17 = f36 + f3;
    f3 = f3 - f36;
    f16 = f34 + f5;
    p[28] = f33 - f35;
    p[31] = f23 - f22;
    f5 = f5 - f34;
    f7 = f25 + f6;
    p[29] = f22 + f23;
    f6 = f6 - f25;
    f10 = f30 + f9;
    f9 = f9 - f30;
    p[16] = f7 + f16;
    p[18] = f16 - f7;
    p[17] = f10 + f17;
    p[20] = f5 - f9;
    p[19] = f17 - f10;
    p[22] = f9 + f5;
    f16 = f37 + f32;
    f32 = f32 - f37;
    p[21] = f6 + f3;
    p[23] = f3 - f6;
    f7 = f29 - f38;
    f38 = f38 + f29;
    f10 = f24 + f31;
    f31 = f31 - f24;
    f3 = (f7 - f16) * twiddle_pi_over_4;
    f5 = (f38 - f32) * twiddle_pi_over_4;
    f17 = (f32 + f38) * twiddle_pi_over_4;
    float f18 = (f16 + f7) * twiddle_pi_over_4;
    f7 = f28 - f26;
    f26 = f26 + f28;
    p[9] = f18 + f10;
    p[11] = f10 - f18;
    p[8] = f3 + f7;
    p[10] = f7 - f3;
    p[13] = f5 + f31;
    p[15] = f31 - f5;
    p[14] = f17 + f26;
    f5 = f13 + f1;
    p[12] = f26 - f17;
    f18 = f11 + f2;
    f2 = f2 - f11;
    f1 = f1 - f13;
    f10 = f14 + f8;
    f8 = f8 - f14;
    f7 = f12 + f4;
    f4 = f4 - f12;
    p[1] = f10 + f5;
    p[3] = f5 - f10;
    p[0] = f7 + f18;
    p[2] = f18 - f7;
    p[5] = f4 + f1;
    p[7] = f1 - f4;
    p[4] = f2 - f8;
    p[6] = f8 + f2;
}

extern "C" VeaS5TensorStatus vea_s5_spp_fft_leaf_f32(
    uint32_t fft_size, int32_t direction,
    float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count) {
    if ((fft_size != 4u && fft_size != 8u && fft_size != 16u && fft_size != 32u) ||
        interleaved == nullptr || value_count != static_cast<size_t>(fft_size)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (fft_size >= 16u) {
        const size_t required = fft_size == 16u ? 2u : 4u;
        if (coefficients == nullptr || coefficient_count < required) {
            return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;
        }
        for (size_t index = 1u; index < required; ++index) {
            if (!std::isfinite(coefficients[index])) {
                return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
            }
        }
    }

    float values[32] = {};
    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(interleaved[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
        values[index] = interleaved[index];
    }

    if (fft_size == 4u || fft_size == 8u) {
        const VeaS5TensorStatus status = vea_s5_spp_fft_small_leaf_f32(
            fft_size, direction, values, value_count);
        if (status != VEA_S5_TENSOR_STATUS_OK) return status;
    } else if (fft_size == 16u) {
        vea_s5_spp_fft16_butterfly(values, coefficients[1]);
        const float raw[16] = {
            values[0], values[1], values[2], values[3],
            values[4], values[5], values[6], values[7],
            values[8], values[9], values[10], values[11],
            values[12], values[13], values[14], values[15]
        };
        static constexpr size_t kNegativeOrder[16] = {
            0, 1, 14, 15, 6, 7, 10, 11,
            2, 3, 12, 13, 4, 5, 8, 9
        };
        static constexpr size_t kPositiveOrder[16] = {
            0, 1, 8, 9, 4, 5, 12, 13,
            2, 3, 10, 11, 6, 7, 14, 15
        };
        const size_t* order = direction < 0 ? kNegativeOrder : kPositiveOrder;
        for (size_t index = 0; index < 16u; ++index) {
            values[index] = raw[order[index]];
        }
    } else {
        vea_s5_spp_fft32_butterfly(
            values, coefficients[1], coefficients[2], coefficients[3]);
        const float raw[32] = {
            values[0], values[1], values[2], values[3],
            values[4], values[5], values[6], values[7],
            values[8], values[9], values[10], values[11],
            values[12], values[13], values[14], values[15],
            values[16], values[17], values[18], values[19],
            values[20], values[21], values[22], values[23],
            values[24], values[25], values[26], values[27],
            values[28], values[29], values[30], values[31]
        };
        static constexpr size_t kNegativeOrder[32] = {
            0, 1, 30, 31, 14, 15, 22, 23,
            6, 7, 26, 27, 10, 11, 18, 19,
            2, 3, 28, 29, 12, 13, 20, 21,
            4, 5, 24, 25, 8, 9, 16, 17
        };
        static constexpr size_t kPositiveOrder[32] = {
            0, 1, 16, 17, 8, 9, 24, 25,
            4, 5, 20, 21, 12, 13, 28, 29,
            2, 3, 18, 19, 10, 11, 26, 27,
            6, 7, 22, 23, 14, 15, 30, 31
        };
        const size_t* order = direction < 0 ? kNegativeOrder : kPositiveOrder;
        for (size_t index = 0; index < 32u; ++index) {
            values[index] = raw[order[index]];
        }
    }

    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(values[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < value_count; ++index) {
        interleaved[index] = values[index];
    }
    return VEA_S5_TENSOR_STATUS_OK;
}


/**
 * Recovered second subblock from the 64-float radix stage (FUN_18007ddc0).
 * The 16-float input is eight interleaved complex values. The coefficient
 * pointer is already adjusted by the parent stage to its local twiddle base.
 */
static void vea_s5_spp_fft_ddc0_substage(float* p, const float* twiddle) {
    float f21 = twiddle[1];
    float f7 = twiddle[3];
    float f1 = p[0] - p[9];
    float f5 = p[9] + p[0];
    float f9 = p[1] - p[8];
    float f4 = p[1] + p[8];
    float f8 = twiddle[2];
    float f10 = p[5] - p[12];
    float f20 = p[5] + p[12];
    float f2 = p[4] - p[13];
    float f6 = p[13] + p[4];
    float f19 = (f2 - f20) * f21;
    float f3 = (f6 - f10) * f21;
    float f11 = (f10 + f6) * f21;
    f21 = (f20 + f2) * f21;
    f20 = p[2] - p[11];
    f6 = p[11] + p[2];
    f2 = p[3] + p[10];
    f10 = p[3] - p[10];
    float f15 = f20 * f8 - f2 * f7;
    float f16 = f20 * f7 + f2 * f8;
    f20 = f10 * f7 + f6 * f8;
    f2 = p[6] - p[15];
    float f13 = p[7] + p[14];
    float f18 = f6 * f7 - f10 * f8;
    f6 = p[7] - p[14];
    f10 = p[15] + p[6];
    float f12 = f2 * f7 - f13 * f8;
    f13 = f13 * f7 + f2 * f8;
    f2 = f12 + f15;
    float f14 = f10 * f8 - f6 * f7;
    float f17 = f10 * f7 + f6 * f8;
    f7 = f13 + f16;
    f6 = f19 + f1;
    f10 = f21 + f4;
    f15 = f15 - f12;
    f16 = f16 - f13;
    f4 = f4 - f21;
    f1 = f1 - f19;
    p[0] = f2 + f6;
    f8 = f20 - f17;
    p[1] = f7 + f10;
    f17 = f17 + f20;
    p[2] = f6 - f2;
    p[3] = f10 - f7;
    f21 = f18 - f14;
    f2 = f3 + f9;
    p[5] = f15 + f4;
    p[4] = f1 - f16;
    f9 = f9 - f3;
    p[6] = f16 + f1;
    f14 = f14 + f18;
    p[7] = f4 - f15;
    f7 = f5 - f11;
    f11 = f11 + f5;
    p[9] = f8 + f2;
    p[11] = f2 - f8;
    p[8] = f21 + f7;
    p[10] = f7 - f21;
    p[12] = f11 - f17;
    p[14] = f17 + f11;
    p[15] = f9 - f14;
    p[13] = f14 + f9;
}

/**
 * Recovered alternate 32-float subblock used by the 128-float radix stage
 * (FUN_18007e820). The input is sixteen interleaved complex values.
 */
static void vea_s5_spp_fft_e820_substage(float* p, const float* twiddle) {
    float f1;
    float f2;
    float f3;
    float f4;
    float f5;
    float f6;
    float f7;
    float f8;
    float f9;
    float f10;
    float f11;
    float f12;
    float f13;
    float f14;
    float f15;
    float f16;
    float f17;
    float f18;
    float f19;
    float f20;
    float f21;
    float f22;
    float f23;
    float f24;
    float f25;
    float f26;
    float f27;
    float f28;
    float f29;
    float f30;
    float f31;
    float f32;
    float f33;
    float f34;
    float f35;
    float f36;
    float f37;
    float f38;

    f31 = twiddle[1];
    f7 = p[9] + p[0x18];
    f1 = p[8] - p[0x19];
    f18 = p[0x19] + p[8];
    f22 = p[0] - p[0x11];
    f14 = p[9] - p[0x18];
    f19 = p[0x11] + p[0];
    f6 = twiddle[4];
    f2 = twiddle[5];
    f37 = -twiddle[7];
    f3 = twiddle[8];
    f30 = twiddle[9];
    f4 = (f1 - f7) * f31;
    f7 = (f7 + f1) * f31;
    f26 = p[1] + p[0x10];
    f1 = f4 + f22;
    f17 = p[1] - p[0x10];
    f22 = f22 - f4;
    f4 = f7 + f26;
    f26 = f26 - f7;
    f11 = p[2] - p[0x13];
    f7 = (f18 - f14) * f31;
    f15 = (f14 + f18) * f31;
    f8 = f7 + f17;
    f5 = f19 - f15;
    f17 = f17 - f7;
    f15 = f15 + f19;
    f7 = p[3] + p[0x12];
    f9 = f11 * f6 - f7 * f2;
    f27 = p[0x13] + p[2];
    f23 = p[3] - p[0x12];
    f14 = p[10] - p[0x1b];
    f20 = p[0x1b] + p[10];
    f11 = f11 * f2 + f7 * f6;
    f7 = p[0xb] + p[0x1a];
    f18 = f7 * f37 + f14 * twiddle[6];
    f14 = f14 * f37 - f7 * twiddle[6];
    f7 = f14 + f9;
    f9 = f9 - f14;
    f14 = f18 + f11;
    f11 = f11 - f18;
    f19 = p[0xb] - p[0x1a];
    f28 = f27 * f37 + f23 * twiddle[6];
    f18 = f27 * twiddle[6] - f23 * f37;
    f10 = f19 * f2 + f20 * f6;
    f12 = f19 * f6 - f20 * f2;
    f2 = f18 - f10;
    f10 = f10 + f18;
    f18 = f28 - f12;
    f12 = f12 + f28;
    f20 = p[4] - p[0x15];
    f6 = p[5] + p[0x14];
    f19 = f20 * f30 + f6 * f3;
    f34 = f20 * f3 - f6 * f30;
    f29 = p[5] - p[0x14];
    f24 = p[0x15] + p[4];
    f23 = p[0xd] + p[0x1c];
    f21 = p[0xd] - p[0x1c];
    f6 = twiddle[6];
    f20 = p[0xc] - p[0x1d];
    f27 = f23 * f30 + f20 * f3;
    f20 = f20 * f30 - f23 * f3;
    f38 = f27 + f19;
    f19 = f19 - f27;
    f36 = f20 + f34;
    f34 = f34 - f20;
    f20 = p[0x1d] + p[0xc];
    f23 = f24 * f30 - f29 * f3;
    f28 = f20 * f3 - f21 * f30;
    f13 = f20 * f30 + f21 * f3;
    f20 = f23 - f28;
    f28 = f28 + f23;
    f23 = p[7] + p[0x16];
    f30 = f29 * f30 + f24 * f3;
    f3 = f30 - f13;
    f13 = f13 + f30;
    f27 = p[6] - p[0x17];
    f21 = p[0xf] + p[0x1e];
    f30 = f27 * f37 + f23 * f6;
    f35 = f27 * f6 - f23 * f37;
    f27 = p[0xe] - p[0x1f];
    f25 = p[0xf] - p[0x1e];
    f24 = p[0x1f] + p[0xe];
    f29 = p[0x17] + p[6];
    f16 = f38 + f4;
    f23 = f27 * twiddle[5] - f21 * twiddle[4];
    f21 = f21 * twiddle[5] + f27 * twiddle[4];
    f27 = p[7] - p[0x16];
    f32 = f23 + f35;
    f33 = f21 + f30;
    f30 = f30 - f21;
    f35 = f35 - f23;
    f23 = f29 * twiddle[5] + f27 * twiddle[4];
    f21 = f27 * twiddle[5] - f29 * twiddle[4];
    f29 = f36 + f1;
    f27 = f24 * f37 - f25 * f6;
    f6 = f25 * f37 + f24 * f6;
    f24 = f33 + f14;
    f14 = f14 - f33;
    f33 = f27 + f23;
    f25 = f6 + f21;
    f21 = f21 - f6;
    f23 = f23 - f27;
    f4 = f4 - f38;
    f6 = f32 + f7;
    f7 = f7 - f32;
    p[1] = f24 + f16;
    p[3] = f16 - f24;
    f1 = f1 - f36;
    p[0] = f6 + f29;
    p[2] = f29 - f6;
    p[5] = f7 + f4;
    p[4] = f1 - f14;
    p[6] = f14 + f1;
    f6 = f9 - f30;
    f30 = f30 + f9;
    p[7] = f4 - f7;
    f7 = f22 - f19;
    f19 = f19 + f22;
    f4 = f35 + f11;
    f11 = f11 - f35;
    f14 = f34 + f26;
    f1 = (f6 - f4) * f31;
    f4 = (f4 + f6) * f31;
    p[8] = f1 + f7;
    f26 = f26 - f34;
    f6 = (f30 - f11) * f31;
    p[9] = f4 + f14;
    f11 = (f11 + f30) * f31;
    p[10] = f7 - f1;
    p[0xb] = f14 - f4;
    f1 = f20 + f5;
    f30 = f2 - f33;
    p[0xd] = f6 + f26;
    p[0xc] = f19 - f11;
    f5 = f5 - f20;
    p[0xf] = f26 - f6;
    f33 = f33 + f2;
    p[0xe] = f11 + f19;
    f6 = f18 - f25;
    f2 = f3 + f8;
    f25 = f25 + f18;
    f8 = f8 - f3;
    p[0x10] = f30 + f1;
    p[0x12] = f1 - f30;
    p[0x11] = f6 + f2;
    p[0x13] = f2 - f6;
    f3 = f12 - f23;
    p[0x14] = f5 - f25;
    f23 = f23 + f12;
    p[0x16] = f25 + f5;
    f6 = f21 + f10;
    f10 = f10 - f21;
    p[0x15] = f33 + f8;
    p[0x17] = f8 - f33;
    f1 = f28 + f17;
    f30 = f15 - f13;
    f13 = f13 + f15;
    f17 = f17 - f28;
    f2 = (f6 - f3) * f31;
    f3 = (f3 + f6) * f31;
    p[0x18] = f2 + f30;
    p[0x1a] = f30 - f2;
    p[0x19] = f3 + f1;
    f6 = (f10 - f23) * f31;
    f31 = (f23 + f10) * f31;
    p[0x1b] = f1 - f3;
    p[0x1d] = f6 + f17;
    p[0x1c] = f13 - f31;
    p[0x1e] = f31 + f13;
    p[0x1f] = f17 - f6;
}

/**
 * Executes the recovered FUN_18007fff0 mixed-radix stage. The size argument
 * is a float-lane count (interleaved complex input), not a complex-bin count.
 */
extern "C" VeaS5TensorStatus vea_s5_spp_fft_radix_stage_f32(
    uint32_t fft_size, float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count) {
    if ((fft_size != 64u && fft_size != 128u) ||
        interleaved == nullptr || coefficients == nullptr ||
        value_count != static_cast<size_t>(fft_size)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (coefficient_count < static_cast<size_t>(fft_size / 2u)) {
        return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;
    }
    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(interleaved[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < coefficient_count; ++index) {
        if (!std::isfinite(coefficients[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    float output[128] = {};
    for (size_t index = 0; index < value_count; ++index) {
        output[index] = interleaved[index];
    }

    // FUN_18007fff0 receives splitTable[0] (N/4) as param_3,
    // so its pointer arithmetic is relative to quarter_size, not N/2.
    const uint32_t quarter_size = fft_size / 4u;
    if (fft_size == 64u) {
        const float* local_twiddle = coefficients + (quarter_size - 8u);
        for (size_t block = 0; block < 4u; ++block) {
            float* values = output + block * 16u;
            if (block == 1u) {
                vea_s5_spp_fft_ddc0_substage(values, local_twiddle);
            } else {
                vea_s5_spp_fft16_butterfly(values, local_twiddle[1]);
            }
        }
    } else {
        const float* fft32_twiddle = coefficients + (quarter_size - 8u);
        const float* alternate_twiddle = coefficients + (quarter_size - 32u);
        for (size_t block = 0; block < 4u; ++block) {
            float* values = output + block * 32u;
            if (block == 1u) {
                vea_s5_spp_fft_e820_substage(values, alternate_twiddle);
            } else {
                vea_s5_spp_fft32_butterfly(
                    values, fft32_twiddle[1], fft32_twiddle[2], fft32_twiddle[3]);
            }
        }
    }

    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(output[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < value_count; ++index) {
        interleaved[index] = output[index];
    }
    return VEA_S5_TENSOR_STATUS_OK;
}


/**
 * Exact arithmetic port of FUN_1800803f0. The final argument is a byte
 * address in the source routine; intptr_t retains that recovered ABI.
 */
static void vea_s5_spp_fft_kernel_803f0(
    int param_1, float* param_2, int64_t param_3) {
    int64_t lVar1;
    int64_t lVar2;
    float* pfVar3;
    float* pfVar4;
    int64_t lVar5;
    int64_t lVar6;
    float* pfVar7;
    float fVar8;
    float fVar9;
    float fVar10;
    float fVar11;
    float fVar12;
    float fVar13;
    float fVar14;
    float fVar15;
    float fVar16;
    float fVar17;
    float fVar18;
    float fVar19;
    float fVar20;
    float fVar21;
    float fVar22;

    param_1 = param_1 >> 3;
    lVar6 = static_cast<int64_t>(param_1 * 4);
    lVar2 = static_cast<int64_t>(param_1 * 6);
    lVar1 = static_cast<int64_t>(param_1 * 2);
    fVar16 = param_2[lVar6] + *param_2;
    fVar20 = *param_2 - param_2[lVar6];
    fVar14 = param_2[lVar6 + 1] + param_2[1];
    fVar8 = param_2[lVar1 + 1] + param_2[lVar2 + 1];
    fVar10 = param_2[lVar1 + 1] - param_2[lVar2 + 1];
    fVar18 = param_2[1] - param_2[lVar6 + 1];
    fVar9 = param_2[lVar1] + param_2[lVar2];
    fVar12 = param_2[lVar1] - param_2[lVar2];
    param_2[1] = fVar8 + fVar14;
    *param_2 = fVar9 + fVar16;
    param_2[lVar1] = fVar16 - fVar9;
    param_2[lVar1 + 1] = fVar14 - fVar8;
    param_2[lVar6 + 1] = fVar12 + fVar18;
    param_2[lVar6] = fVar20 - fVar10;
    lVar6 = static_cast<int64_t>(param_1);
    param_2[lVar2] = fVar10 + fVar20;
    param_2[lVar2 + 1] = fVar18 - fVar12;
    fVar8 = *reinterpret_cast<const float*>(param_3 + 4);
    if (2 < lVar6) {
        pfVar3 = param_2 + lVar1 * 2 + 3;
        pfVar4 = param_2 + lVar1 * 2 - 1;
        lVar2 = (lVar6 - 3U >> 1) + 1;
        pfVar7 = reinterpret_cast<float*>(param_3 + 8);
        do {
            fVar18 = pfVar3[-1 - lVar1] + pfVar3[lVar1 - 1];
            fVar15 = pfVar3[-1 - lVar1] - pfVar3[lVar1 - 1];
            fVar13 = pfVar3[lVar1 * -2 - 1] + pfVar3[-1];
            fVar19 = pfVar3[lVar1 * -2 - 1] - pfVar3[-1];
            fVar11 = *pfVar3 + pfVar3[lVar1 * -2];
            fVar9 = pfVar7[2];
            fVar21 = pfVar3[lVar1 * -2] - *pfVar3;
            fVar10 = pfVar7[3];
            fVar12 = pfVar7[4];
            fVar16 = pfVar3[lVar1] + pfVar3[-lVar1];
            fVar14 = pfVar7[5];
            fVar17 = pfVar3[-lVar1] - pfVar3[lVar1];
            fVar20 = fVar15 + fVar21;
            pfVar3[lVar1 * -2 - 1] = fVar18 + fVar13;
            fVar21 = fVar21 - fVar15;
            pfVar3[lVar1 * -2] = fVar16 + fVar11;
            pfVar3[-lVar1] = fVar11 - fVar16;
            pfVar3[-1 - lVar1] = fVar13 - fVar18;
            fVar16 = fVar19 - fVar17;
            fVar17 = fVar17 + fVar19;
            pfVar3[-1] = fVar9 * fVar16 - fVar10 * fVar20;
            *pfVar3 = fVar9 * fVar20 + fVar10 * fVar16;
            pfVar3[lVar1 - 1] = fVar14 * fVar21 + fVar12 * fVar17;
            pfVar3[lVar1] = fVar12 * fVar21 - fVar14 * fVar17;
            fVar13 = pfVar4[lVar1] + pfVar4[-lVar1];
            fVar22 = pfVar4[-lVar1] - pfVar4[lVar1];
            fVar15 = pfVar4[-1 - lVar1] + pfVar4[lVar1 - 1];
            fVar21 = pfVar4[-1 - lVar1] - pfVar4[lVar1 - 1];
            fVar20 = pfVar4[-1] + pfVar4[lVar1 * 2 - 1];
            fVar17 = pfVar4[-1] - pfVar4[lVar1 * 2 - 1];
            fVar16 = pfVar4[lVar1 * 2] + *pfVar4;
            fVar19 = *pfVar4 - pfVar4[lVar1 * 2];
            pfVar3 = pfVar3 + 2;
            fVar11 = fVar21 - fVar19;
            pfVar4[-lVar1] = fVar16 + fVar13;
            fVar18 = fVar17 + fVar22;
            pfVar4[-1 - lVar1] = fVar20 + fVar15;
            fVar19 = fVar19 + fVar21;
            pfVar4[-1] = fVar15 - fVar20;
            fVar22 = fVar22 - fVar17;
            *pfVar4 = fVar13 - fVar16;
            pfVar4[lVar1 - 1] = fVar10 * fVar11 - fVar9 * fVar18;
            pfVar4[lVar1] = fVar10 * fVar18 + fVar9 * fVar11;
            pfVar4[lVar1 * 2 - 1] = fVar12 * fVar22 + fVar14 * fVar19;
            pfVar4[lVar1 * 2] = fVar14 * fVar22 - fVar12 * fVar19;
            pfVar4 = pfVar4 - 2;
            lVar2 = lVar2 - 1;
            pfVar7 = pfVar7 + 4;
        } while (lVar2 != 0);
    }
    lVar5 = static_cast<int64_t>(param_1 * 5);
    lVar2 = static_cast<int64_t>(param_1 * 7);
    lVar1 = static_cast<int64_t>(param_1 * 3);
    fVar16 = param_2[lVar5 + 1] + param_2[lVar6 + 1];
    fVar13 = param_2[lVar6 + 1] - param_2[lVar5 + 1];
    fVar10 = param_2[lVar1 + 1] + param_2[lVar2 + 1];
    fVar20 = param_2[lVar1 + 1] - param_2[lVar2 + 1];
    fVar18 = param_2[lVar5] + param_2[lVar6];
    fVar9 = param_2[lVar1] - param_2[lVar2];
    fVar14 = param_2[lVar1] + param_2[lVar2];
    fVar11 = param_2[lVar6] - param_2[lVar5];
    fVar12 = fVar9 + fVar13;
    param_2[lVar6 + 1] = fVar10 + fVar16;
    fVar13 = fVar13 - fVar9;
    fVar9 = fVar11 - fVar20;
    param_2[lVar6] = fVar14 + fVar18;
    fVar20 = fVar20 + fVar11;
    param_2[lVar1] = fVar18 - fVar14;
    param_2[lVar1 + 1] = fVar16 - fVar10;
    param_2[lVar5] = (fVar9 - fVar12) * fVar8;
    param_2[lVar5 + 1] = (fVar12 + fVar9) * fVar8;
    param_2[lVar2 + 1] = (fVar13 - fVar20) * -fVar8;
    param_2[lVar2] = (fVar13 + fVar20) * -fVar8;
}

/**
 * Exact arithmetic port of FUN_180080940.
 */
static void vea_s5_spp_fft_kernel_0940(
    int param_1, float* param_2, int64_t param_3) {
    int64_t lVar1;
    int64_t lVar2;
    float* pfVar3;
    int64_t lVar4;
    float* pfVar5;
    float* pfVar6;
    float* pfVar7;
    float fVar8;
    float fVar9;
    float fVar10;
    float fVar11;
    float fVar12;
    float fVar13;
    float fVar14;
    float fVar15;
    float fVar16;
    float fVar17;
    float fVar18;
    float fVar19;
    float fVar20;
    float fVar21;
    float fVar22;
    float fVar23;
    float fVar24;
    float fVar25;

    fVar15 = *reinterpret_cast<const float*>(param_3 + 4);
    param_1 = param_1 >> 3;
    lVar4 = static_cast<int64_t>(param_1 * 4);
    lVar1 = static_cast<int64_t>(param_1 * 6);
    fVar19 = *param_2 - param_2[lVar4 + 1];
    fVar24 = param_2[lVar4 + 1] + *param_2;
    lVar2 = static_cast<int64_t>(param_1 * 2);
    fVar17 = param_2[lVar4] + param_2[1];
    fVar21 = param_2[1] - param_2[lVar4];
    fVar10 = param_2[lVar1] + param_2[lVar2 + 1];
    fVar14 = param_2[lVar2 + 1] - param_2[lVar1];
    fVar9 = param_2[lVar2] - param_2[lVar1 + 1];
    fVar12 = param_2[lVar1 + 1] + param_2[lVar2];
    fVar8 = (fVar9 - fVar10) * fVar15;
    fVar10 = (fVar10 + fVar9) * fVar15;
    *param_2 = fVar8 + fVar19;
    fVar9 = (fVar12 - fVar14) * fVar15;
    param_2[1] = fVar10 + fVar17;
    param_2[lVar2] = fVar19 - fVar8;
    param_2[lVar2 + 1] = fVar17 - fVar10;
    fVar15 = (fVar14 + fVar12) * fVar15;
    param_2[lVar4 + 1] = fVar9 + fVar21;
    param_2[lVar4] = fVar24 - fVar15;
    param_2[lVar1] = fVar15 + fVar24;
    param_2[lVar1 + 1] = fVar21 - fVar9;
    if (2 < static_cast<int64_t>(param_1)) {
        pfVar6 = param_2 + lVar2 * 2 + 3;
        pfVar7 = param_2 + lVar2 * 2 - 1;
        lVar1 = (static_cast<int64_t>(param_1) - 3U >> 1) + 1;
        pfVar3 = reinterpret_cast<float*>(param_3 + 8);
        pfVar5 = reinterpret_cast<float*>(param_3 + (lVar4 + 2) * 4);
        do {
            fVar24 = pfVar6[lVar2 * -2 - 1] - *pfVar6;
            fVar15 = pfVar3[4];
            fVar25 = *pfVar6 + pfVar6[lVar2 * -2 - 1];
            fVar8 = pfVar3[2];
            fVar9 = pfVar3[3];
            fVar10 = pfVar5[-6];
            fVar12 = pfVar5[-5];
            fVar14 = pfVar3[5];
            fVar18 = pfVar6[-1 - lVar2] - pfVar6[lVar2];
            fVar23 = pfVar6[-1 - lVar2] + pfVar6[lVar2];
            fVar17 = pfVar5[-4];
            fVar19 = pfVar5[-3];
            fVar21 = pfVar6[-1] + pfVar6[lVar2 * -2];
            fVar22 = pfVar6[lVar2 * -2] - pfVar6[-1];
            fVar11 = pfVar6[lVar2 - 1] + pfVar6[-lVar2];
            fVar20 = pfVar6[-lVar2] - pfVar6[lVar2 - 1];
            fVar16 = fVar8 * fVar24 - fVar9 * fVar21;
            fVar13 = fVar8 * fVar21 + fVar9 * fVar24;
            fVar24 = fVar12 * fVar18 - fVar10 * fVar11;
            fVar21 = fVar12 * fVar11 + fVar10 * fVar18;
            pfVar6[lVar2 * -2 - 1] = fVar24 + fVar16;
            pfVar6[lVar2 * -2] = fVar21 + fVar13;
            pfVar6[-lVar2] = fVar13 - fVar21;
            pfVar6[-1 - lVar2] = fVar16 - fVar24;
            fVar13 = fVar14 * fVar22 + fVar15 * fVar25;
            fVar11 = fVar15 * fVar22 - fVar14 * fVar25;
            fVar24 = fVar17 * fVar20 + fVar19 * fVar23;
            fVar21 = fVar19 * fVar20 - fVar17 * fVar23;
            pfVar6[-1] = fVar24 + fVar13;
            *pfVar6 = fVar21 + fVar11;
            pfVar6[lVar2] = fVar11 - fVar21;
            pfVar6[lVar2 - 1] = fVar13 - fVar24;
            fVar21 = pfVar7[lVar2 - 1] + pfVar7[-lVar2];
            fVar20 = pfVar7[-lVar2] - pfVar7[lVar2 - 1];
            fVar16 = pfVar7[-1 - lVar2] - pfVar7[lVar2];
            fVar23 = pfVar7[-1 - lVar2] + pfVar7[lVar2];
            fVar24 = pfVar7[lVar2 * 2 - 1] + *pfVar7;
            fVar18 = *pfVar7 - pfVar7[lVar2 * 2 - 1];
            fVar13 = pfVar7[-1] - pfVar7[lVar2 * 2];
            fVar22 = pfVar7[-1] + pfVar7[lVar2 * 2];
            fVar11 = fVar10 * fVar16 - fVar12 * fVar21;
            fVar12 = fVar10 * fVar21 + fVar12 * fVar16;
            fVar10 = fVar9 * fVar13 - fVar8 * fVar24;
            fVar8 = fVar9 * fVar24 + fVar8 * fVar13;
            pfVar7[-1 - lVar2] = fVar10 + fVar11;
            pfVar7[-lVar2] = fVar8 + fVar12;
            pfVar7[-1] = fVar11 - fVar10;
            fVar9 = fVar19 * fVar20 + fVar17 * fVar23;
            *pfVar7 = fVar12 - fVar8;
            fVar10 = fVar17 * fVar20 - fVar19 * fVar23;
            fVar8 = fVar15 * fVar18 + fVar14 * fVar22;
            fVar15 = fVar14 * fVar18 - fVar15 * fVar22;
            pfVar7[lVar2 - 1] = fVar8 + fVar9;
            pfVar6 = pfVar6 + 2;
            pfVar7[lVar2] = fVar15 + fVar10;
            pfVar7[lVar2 * 2 - 1] = fVar9 - fVar8;
            pfVar7[lVar2 * 2] = fVar10 - fVar15;
            pfVar7 = pfVar7 - 2;
            lVar1 = lVar1 - 1;
            pfVar3 = pfVar3 + 4;
            pfVar5 = pfVar5 - 4;
        } while (lVar1 != 0);
    }
    fVar15 = *reinterpret_cast<const float*>(param_3 + lVar2 * 4);
    fVar8 = *reinterpret_cast<const float*>(param_3 + lVar2 * 4 + 4);
    lVar4 = static_cast<int64_t>(param_1 * 5);
    lVar2 = static_cast<int64_t>(param_1 * 3);
    lVar1 = static_cast<int64_t>(param_1 * 7);
    fVar10 = param_2[param_1] - param_2[lVar4 + 1];
    fVar9 = param_2[lVar4] + param_2[static_cast<int64_t>(param_1) + 1];
    fVar11 = param_2[static_cast<int64_t>(param_1) + 1] - param_2[lVar4];
    fVar13 = param_2[param_1] + param_2[lVar4 + 1];
    fVar12 = param_2[lVar1] + param_2[lVar2 + 1];
    fVar21 = param_2[lVar2 + 1] - param_2[lVar1];
    fVar19 = param_2[lVar2] - param_2[lVar1 + 1];
    fVar24 = param_2[lVar1 + 1] + param_2[lVar2];
    fVar17 = fVar15 * fVar10 - fVar8 * fVar9;
    fVar14 = fVar15 * fVar9 + fVar8 * fVar10;
    fVar10 = fVar8 * fVar19 - fVar15 * fVar12;
    fVar9 = fVar8 * fVar12 + fVar15 * fVar19;
    param_2[param_1] = fVar10 + fVar17;
    fVar12 = fVar8 * fVar13 - fVar15 * fVar11;
    param_2[static_cast<int64_t>(param_1) + 1] = fVar9 + fVar14;
    param_2[lVar2] = fVar17 - fVar10;
    param_2[lVar2 + 1] = fVar14 - fVar9;
    fVar10 = fVar8 * fVar11 + fVar15 * fVar13;
    fVar9 = fVar15 * fVar24 - fVar8 * fVar21;
    fVar15 = fVar15 * fVar21 + fVar8 * fVar24;
    param_2[lVar4] = fVar12 - fVar9;
    param_2[lVar4 + 1] = fVar10 - fVar15;
    param_2[lVar1 + 1] = fVar15 + fVar10;
    param_2[lVar1] = fVar9 + fVar12;
}

/**
 * Exact arithmetic port of FUN_180080c0, supporting the 256- and 512-lane
 * plans. The mode byte selects the alternate branch for the final group.
 */
extern "C" VeaS5TensorStatus vea_s5_spp_fft_large_radix_stage_f32(
    uint32_t fft_size, uint8_t mode, float* interleaved, size_t value_count,
    const float* coefficients, size_t coefficient_count) {
    if ((fft_size != 256u && fft_size != 512u) || mode > 1u ||
        interleaved == nullptr || coefficients == nullptr ||
        value_count != static_cast<size_t>(fft_size)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    if (coefficient_count < static_cast<size_t>(fft_size / 2u)) {
        return VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL;
    }
    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(interleaved[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < coefficient_count; ++index) {
        if (!std::isfinite(coefficients[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    float output[512] = {};
    for (size_t index = 0; index < value_count; ++index) output[index] = interleaved[index];
    const uint32_t quarter_size = fft_size / 4u;

    if (fft_size == 256u) {
        const float* twiddle_3f0 = coefficients + (quarter_size - 32u);
        const float* twiddle_leaf = coefficients + (quarter_size - 8u);
        const float* twiddle_940 = coefficients + (quarter_size - 64u);

        vea_s5_spp_fft_kernel_803f0(0x40, output, reinterpret_cast<int64_t>(twiddle_3f0));
        vea_s5_spp_fft16_butterfly(output + 0, twiddle_leaf[1]);
        vea_s5_spp_fft_ddc0_substage(output + 16, twiddle_leaf);
        vea_s5_spp_fft16_butterfly(output + 32, twiddle_leaf[1]);
        vea_s5_spp_fft16_butterfly(output + 48, twiddle_leaf[1]);

        vea_s5_spp_fft_kernel_0940(0x40, output + 64, reinterpret_cast<int64_t>(twiddle_940));
        vea_s5_spp_fft16_butterfly(output + 64, twiddle_leaf[1]);
        vea_s5_spp_fft_ddc0_substage(output + 80, twiddle_leaf);
        vea_s5_spp_fft16_butterfly(output + 96, twiddle_leaf[1]);
        vea_s5_spp_fft_ddc0_substage(output + 112, twiddle_leaf);

        vea_s5_spp_fft_kernel_803f0(0x40, output + 128, reinterpret_cast<int64_t>(twiddle_3f0));
        vea_s5_spp_fft16_butterfly(output + 128, twiddle_leaf[1]);
        vea_s5_spp_fft_ddc0_substage(output + 144, twiddle_leaf);
        vea_s5_spp_fft16_butterfly(output + 160, twiddle_leaf[1]);
        vea_s5_spp_fft16_butterfly(output + 176, twiddle_leaf[1]);

        if (mode == 0u) {
            vea_s5_spp_fft_kernel_0940(0x40, output + 192, reinterpret_cast<int64_t>(twiddle_940));
            vea_s5_spp_fft_ddc0_substage(output + 240, twiddle_leaf);
        } else {
            vea_s5_spp_fft_kernel_803f0(0x40, output + 192, reinterpret_cast<int64_t>(twiddle_3f0));
            vea_s5_spp_fft16_butterfly(output + 240, twiddle_leaf[1]);
        }
        vea_s5_spp_fft16_butterfly(output + 192, twiddle_leaf[1]);
        vea_s5_spp_fft_ddc0_substage(output + 208, twiddle_leaf);
        vea_s5_spp_fft16_butterfly(output + 224, twiddle_leaf[1]);
    } else {
        const float* twiddle_3f0 = coefficients + (quarter_size - 64u);
        const float* twiddle_leaf = coefficients + (quarter_size - 8u);
        const float* twiddle_e820 = coefficients + (quarter_size - 32u);
        const float* twiddle_940 = coefficients + (quarter_size - 128u);

        vea_s5_spp_fft_kernel_803f0(0x80, output, reinterpret_cast<int64_t>(twiddle_3f0));
        vea_s5_spp_fft32_butterfly(output + 0, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft_e820_substage(output + 32, twiddle_e820);
        vea_s5_spp_fft32_butterfly(output + 64, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft32_butterfly(output + 96, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);

        vea_s5_spp_fft_kernel_0940(0x80, output + 128, reinterpret_cast<int64_t>(twiddle_940));
        vea_s5_spp_fft32_butterfly(output + 128, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft_e820_substage(output + 160, twiddle_e820);
        vea_s5_spp_fft32_butterfly(output + 192, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft_e820_substage(output + 224, twiddle_e820);

        vea_s5_spp_fft_kernel_803f0(0x80, output + 256, reinterpret_cast<int64_t>(twiddle_3f0));
        vea_s5_spp_fft32_butterfly(output + 256, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft_e820_substage(output + 288, twiddle_e820);
        vea_s5_spp_fft32_butterfly(output + 320, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft32_butterfly(output + 352, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);

        if (mode == 0u) {
            vea_s5_spp_fft_kernel_0940(0x80, output + 384, reinterpret_cast<int64_t>(twiddle_940));
            vea_s5_spp_fft_e820_substage(output + 480, twiddle_e820);
        } else {
            vea_s5_spp_fft_kernel_803f0(0x80, output + 384, reinterpret_cast<int64_t>(twiddle_3f0));
            vea_s5_spp_fft32_butterfly(output + 480, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        }
        vea_s5_spp_fft32_butterfly(output + 384, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
        vea_s5_spp_fft_e820_substage(output + 416, twiddle_e820);
        vea_s5_spp_fft32_butterfly(output + 448, twiddle_leaf[1], twiddle_leaf[2], twiddle_leaf[3]);
    }

    for (size_t index = 0; index < value_count; ++index) {
        if (!std::isfinite(output[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    for (size_t index = 0; index < value_count; ++index) interleaved[index] = output[index];
    return VEA_S5_TENSOR_STATUS_OK;
}


namespace {

struct VeaS5SppComplex {
    float real;
    float imag;
};

VeaS5TensorStatus vea_s5_spp_complex_fft_inplace(
    std::vector<VeaS5SppComplex>& values, int32_t direction) {
    const size_t count = values.size();
    if (count < 2u || (count & (count - 1u)) != 0u ||
        (direction != -1 && direction != 1)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t reversed = 0u;
    for (size_t index = 1u; index < count; ++index) {
        size_t bit = count >> 1u;
        while ((reversed & bit) != 0u) {
            reversed ^= bit;
            bit >>= 1u;
        }
        reversed ^= bit;
        if (index < reversed) {
            std::swap(values[index], values[reversed]);
        }
    }

    constexpr double kTwoPi = 6.283185307179586476925286766559;
    for (size_t length = 2u; length <= count; length <<= 1u) {
        const double angle = static_cast<double>(direction) *
            kTwoPi / static_cast<double>(length);
        const float root_real = static_cast<float>(std::cos(angle));
        const float root_imag = static_cast<float>(std::sin(angle));
        const size_t half = length >> 1u;
        for (size_t block = 0u; block < count; block += length) {
            float twiddle_real = 1.0f;
            float twiddle_imag = 0.0f;
            for (size_t offset = 0u; offset < half; ++offset) {
                const VeaS5SppComplex even = values[block + offset];
                const VeaS5SppComplex source_odd = values[block + offset + half];
                const VeaS5SppComplex odd {
                    source_odd.real * twiddle_real - source_odd.imag * twiddle_imag,
                    source_odd.real * twiddle_imag + source_odd.imag * twiddle_real
                };
                values[block + offset] = {
                    even.real + odd.real,
                    even.imag + odd.imag
                };
                values[block + offset + half] = {
                    even.real - odd.real,
                    even.imag - odd.imag
                };

                const float next_real =
                    twiddle_real * root_real - twiddle_imag * root_imag;
                twiddle_imag =
                    twiddle_real * root_imag + twiddle_imag * root_real;
                twiddle_real = next_real;
            }
        }
    }

    for (const VeaS5SppComplex& value : values) {
        if (!std::isfinite(value.real) || !std::isfinite(value.imag)) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }
    return VEA_S5_TENSOR_STATUS_OK;
}

}  // namespace

extern "C" VeaS5TensorStatus vea_s5_spp_real_fft_f32(
    uint32_t fft_size, int32_t direction, float* frame, size_t frame_count) {
    if (frame == nullptr || frame_count != static_cast<size_t>(fft_size) ||
        fft_size < 4u || fft_size > 65536u ||
        (fft_size & (fft_size - 1u)) != 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t index = 0; index < frame_count; ++index) {
        if (!std::isfinite(frame[index])) {
            return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
    }

    const size_t complex_count = static_cast<size_t>(fft_size / 2u);
    try {
        std::vector<VeaS5SppComplex> spectrum(complex_count);
        std::vector<float> output(frame_count, 0.0f);
        constexpr double kTwoPi = 6.283185307179586476925286766559;

        if (direction < 0) {
            // Pack adjacent real samples as z[n] = x[2n] + i*x[2n+1].
            for (size_t index = 0; index < complex_count; ++index) {
                spectrum[index] = {frame[index * 2u], frame[index * 2u + 1u]};
            }
            const VeaS5TensorStatus fft_status =
                vea_s5_spp_complex_fft_inplace(spectrum, -1);
            if (fft_status != VEA_S5_TENSOR_STATUS_OK) return fft_status;

            // The DC and Nyquist bins share the zero-frequency complex pair.
            output[0] = spectrum[0].real + spectrum[0].imag;
            output[1] = spectrum[0].real - spectrum[0].imag;

            for (size_t bin = 1u; bin < complex_count; ++bin) {
                const VeaS5SppComplex current = spectrum[bin];
                const VeaS5SppComplex mirrored = spectrum[complex_count - bin];
                const float average_real = (current.real + mirrored.real) * 0.5f;
                const float average_imag = (current.imag - mirrored.imag) * 0.5f;
                const float difference_real = (current.real - mirrored.real) * 0.5f;
                const float difference_imag = (current.imag + mirrored.imag) * 0.5f;
                const double angle = kTwoPi * static_cast<double>(bin) /
                    static_cast<double>(fft_size);
                const float sine = static_cast<float>(std::sin(angle));
                const float cosine = static_cast<float>(std::cos(angle));

                // X[k] = A - i*exp(-i*angle)*B.
                const float product_real =
                    sine * difference_real - cosine * difference_imag;
                const float product_imag =
                    sine * difference_imag + cosine * difference_real;
                output[bin * 2u] = average_real - product_real;
                output[bin * 2u + 1u] = average_imag - product_imag;
            }
        } else {
            // Unpack the real spectrum into the N/2 complex spectrum.
            spectrum[0] = {
                (frame[0] + frame[1]) * 0.5f,
                (frame[0] - frame[1]) * 0.5f
            };
            for (size_t bin = 1u; bin < complex_count; ++bin) {
                const float current_real = frame[bin * 2u];
                const float current_imag = frame[bin * 2u + 1u];
                const float mirrored_real = frame[(complex_count - bin) * 2u];
                const float mirrored_imag = frame[(complex_count - bin) * 2u + 1u];
                const float average_real = (current_real + mirrored_real) * 0.5f;
                const float average_imag = (current_imag - mirrored_imag) * 0.5f;
                const float difference_real = (current_real - mirrored_real) * 0.5f;
                const float difference_imag = (current_imag + mirrored_imag) * 0.5f;
                const double angle = kTwoPi * static_cast<double>(bin) /
                    static_cast<double>(fft_size);
                const float sine = static_cast<float>(std::sin(angle));
                const float cosine = static_cast<float>(std::cos(angle));

                // Z[k] = A + i*exp(-i*angle)*B.
                const float product_real =
                    sine * difference_real - cosine * difference_imag;
                const float product_imag =
                    sine * difference_imag + cosine * difference_real;
                spectrum[bin] = {
                    average_real + product_real,
                    average_imag + product_imag
                };
            }

            // The source inverse path is unnormalized: IFFT(M) returns M*x.
            const VeaS5TensorStatus fft_status =
                vea_s5_spp_complex_fft_inplace(spectrum, 1);
            if (fft_status != VEA_S5_TENSOR_STATUS_OK) return fft_status;
            for (size_t index = 0; index < complex_count; ++index) {
                output[index * 2u] = spectrum[index].real;
                output[index * 2u + 1u] = spectrum[index].imag;
            }
        }

        for (float value : output) {
            if (!std::isfinite(value)) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
        }
        std::copy(output.begin(), output.end(), frame);
        return VEA_S5_TENSOR_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    }
}

