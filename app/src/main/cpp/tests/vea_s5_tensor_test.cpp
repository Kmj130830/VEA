#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <cassert>
#include <cstring>
#include <bit>
#include <cstdint>
#include <cmath>
#include <limits>
#include <vector>
#include <initializer_list>

namespace {

bool IsAligned(const void* pointer, uintptr_t alignment) {
    return pointer != nullptr &&
        (reinterpret_cast<uintptr_t>(pointer) & (alignment - 1u)) == 0;
}

double SumSquares(const float* values, size_t count) {
    double sum = 0.0;
    for (size_t index = 0; index < count; ++index) {
        sum += static_cast<double>(values[index]) * values[index];
    }
    return sum;
}

void AssertSppFftLeafMatchesDirectDft(
    uint32_t fft_size, int32_t direction,
    const float* input, const float* coefficients) {
    assert(fft_size == 16u || fft_size == 32u);
    const size_t complex_count = fft_size / 2u;
    float actual[32] = {};
    for (size_t index = 0; index < fft_size; ++index) {
        actual[index] = input[index];
    }
    assert(vea_s5_spp_fft_leaf_f32(
        fft_size, direction, actual, fft_size,
        coefficients, complex_count) == VEA_S5_TENSOR_STATUS_OK);

    constexpr double kTwoPi = 6.283185307179586476925286766559;
    const double sign = direction < 0 ? -1.0 : 1.0;
    for (size_t frequency = 0; frequency < complex_count; ++frequency) {
        double expected_real = 0.0;
        double expected_imag = 0.0;
        for (size_t sample = 0; sample < complex_count; ++sample) {
            const double angle = sign * kTwoPi *
                static_cast<double>(frequency * sample) /
                static_cast<double>(complex_count);
            const double cosine = std::cos(angle);
            const double sine = std::sin(angle);
            const double real = input[sample * 2u];
            const double imag = input[sample * 2u + 1u];
            expected_real += real * cosine - imag * sine;
            expected_imag += real * sine + imag * cosine;
        }
        const double real_error = std::fabs(
            static_cast<double>(actual[frequency * 2u]) - expected_real);
        const double imag_error = std::fabs(
            static_cast<double>(actual[frequency * 2u + 1u]) - expected_imag);
        assert(real_error < 2.0e-4 * (1.0 + std::fabs(expected_real)));
        assert(imag_error < 2.0e-4 * (1.0 + std::fabs(expected_imag)));
    }
}

}  // namespace

int main() {
    assert(vea_s5_tensor_abi_version() == VEA_S5_TENSOR_ABI_VERSION);

    // FUN_180133fd0: circular frame-buffer rotation (positive = left, negative = right).
    float rotate_left[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    assert(vea_s5_spp_rotate_circular_f32(rotate_left, 5, 2) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float expected_left[] = {3.0f, 4.0f, 5.0f, 1.0f, 2.0f};
    assert(std::memcmp(rotate_left, expected_left, sizeof(expected_left)) == 0);

    float rotate_right[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    assert(vea_s5_spp_rotate_circular_f32(rotate_right, 5, -2) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float expected_right[] = {4.0f, 5.0f, 1.0f, 2.0f, 3.0f};
    assert(std::memcmp(rotate_right, expected_right, sizeof(expected_right)) == 0);

    float rotate_wrapped[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    assert(vea_s5_spp_rotate_circular_f32(rotate_wrapped, 5, 7) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(rotate_wrapped, expected_left, sizeof(expected_left)) == 0);
    assert(vea_s5_spp_rotate_circular_f32(nullptr, 0, -100) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_spp_rotate_circular_f32(nullptr, 5, 1) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_1801397d0: frame-wide multiplication; invalid input is not partially mutated.
    float scaled_frame[] = {2.0f, -4.0f, 6.0f, 0.5f};
    assert(vea_s5_spp_scale_buffer_f32(scaled_frame, 4, 0.5f) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float expected_scaled[] = {1.0f, -2.0f, 3.0f, 0.25f};
    assert(std::memcmp(scaled_frame, expected_scaled, sizeof(expected_scaled)) == 0);
    const float before_invalid_scale[] = {1.0f, 2.0f};
    float invalid_scale[] = {before_invalid_scale[0], before_invalid_scale[1]};
    assert(vea_s5_spp_scale_buffer_f32(
        invalid_scale, 2, std::numeric_limits<float>::infinity()) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(invalid_scale[0] == before_invalid_scale[0] &&
        invalid_scale[1] == before_invalid_scale[1]);
    float non_finite_frame[] = {1.0f, std::numeric_limits<float>::quiet_NaN()};
    assert(vea_s5_spp_scale_buffer_f32(non_finite_frame, 2, 0.5f) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_spp_scale_buffer_f32(nullptr, 0, 0.0f) ==
        VEA_S5_TENSOR_STATUS_OK);


    // FUN_18004ec80 / FUN_180082220: size-dependent real-FFT plan records.
    uint32_t fft_split_4[3] = {};
    float fft_coefficients_4[2] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        4u, fft_split_4, 3, fft_coefficients_4, 2) == VEA_S5_TENSOR_STATUS_OK);
    assert(fft_split_4[0] == 1u && fft_split_4[1] == 1u);
    assert(fft_coefficients_4[0] == 0.0f && fft_coefficients_4[1] == 0.0f);

    uint32_t fft_split_8[4] = {};
    float fft_coefficients_8[4] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        8u, fft_split_8, 4, fft_coefficients_8, 4) == VEA_S5_TENSOR_STATUS_OK);
    assert(fft_split_8[0] == 2u && fft_split_8[1] == 2u);
    assert(std::fabs(fft_coefficients_8[2] - 0.70710678f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_8[3] - 0.35355339f) < 1.0e-6f);

    uint32_t fft_split_16[4] = {};
    float fft_coefficients_16[8] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        16u, fft_split_16, 4, fft_coefficients_16, 8) == VEA_S5_TENSOR_STATUS_OK);
    assert(fft_split_16[0] == 4u && fft_split_16[1] == 4u);
    assert(fft_coefficients_16[0] == 1.0f);
    assert(std::fabs(fft_coefficients_16[1] - 0.70710678f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_16[5] - 0.46193975f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_16[6] - 0.35355339f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_16[7] - 0.19134171f) < 1.0e-6f);

    uint32_t fft_split_32[6] = {};
    float fft_coefficients_32[16] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        32u, fft_split_32, 6, fft_coefficients_32, 16) == VEA_S5_TENSOR_STATUS_OK);
    assert(fft_split_32[0] == 8u && fft_split_32[1] == 8u);
    assert(fft_coefficients_32[0] == 1.0f && fft_coefficients_32[4] == 1.0f);
    assert(std::fabs(fft_coefficients_32[2] - 0.92387950f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_32[3] - 0.38268343f) < 1.0e-6f);
    assert(std::fabs(fft_coefficients_32[15] - 0.09754516f) < 1.0e-6f);

    uint32_t fft_split_256[13] = {};
    float fft_coefficients_256[128] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        256u, fft_split_256, 13, fft_coefficients_256, 128) == VEA_S5_TENSOR_STATUS_OK);
    const uint32_t expected_fft_split_256[] = {64u, 64u, 0u, 16u, 0u, 64u, 32u, 96u};
    for (size_t index = 0; index < 8; ++index) {
        assert(fft_split_256[index] == expected_fft_split_256[index]);
    }
    assert(vea_s5_spp_fft_build_plan_f32(
        12u, fft_split_256, 13, fft_coefficients_256, 128) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_spp_fft_build_plan_f32(
        256u, fft_split_256, 12, fft_coefficients_256, 128) ==
        VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);

    // FUN_18007d700 / FUN_18007fca0: exact small complex-leaf butterfly orders.
    float fft4_negative[] = {1.0f, 2.0f, 3.0f, 4.0f};
    assert(vea_s5_spp_fft_small_leaf_f32(
        4u, -1, fft4_negative, 4) == VEA_S5_TENSOR_STATUS_OK);
    const float expected_fft4[] = {4.0f, 6.0f, -2.0f, -2.0f};
    assert(std::memcmp(fft4_negative, expected_fft4, sizeof(expected_fft4)) == 0);
    float fft4_positive[] = {1.0f, 2.0f, 3.0f, 4.0f};
    assert(vea_s5_spp_fft_small_leaf_f32(
        4u, 1, fft4_positive, 4) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(fft4_positive, expected_fft4, sizeof(expected_fft4)) == 0);

    const float fft8_input[] = {1.0f, 10.0f, 2.0f, 20.0f, 3.0f, 30.0f, 4.0f, 40.0f};
    float fft8_negative[8] = {};
    std::memcpy(fft8_negative, fft8_input, sizeof(fft8_input));
    assert(vea_s5_spp_fft_small_leaf_f32(
        8u, -1, fft8_negative, 8) == VEA_S5_TENSOR_STATUS_OK);
    const float expected_fft8_negative[] = {
        10.0f, 100.0f, -22.0f, -18.0f, -2.0f, -20.0f, 18.0f, -22.0f
    };
    assert(std::memcmp(fft8_negative, expected_fft8_negative, sizeof(expected_fft8_negative)) == 0);

    float fft8_positive[8] = {};
    std::memcpy(fft8_positive, fft8_input, sizeof(fft8_input));
    assert(vea_s5_spp_fft_small_leaf_f32(
        8u, 1, fft8_positive, 8) == VEA_S5_TENSOR_STATUS_OK);
    const float expected_fft8_positive[] = {
        10.0f, 100.0f, 18.0f, -22.0f, -2.0f, -20.0f, -22.0f, -18.0f
    };
    assert(std::memcmp(fft8_positive, expected_fft8_positive, sizeof(expected_fft8_positive)) == 0);
    float invalid_fft8[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f,
        7.0f, std::numeric_limits<float>::quiet_NaN()};
    assert(vea_s5_spp_fft_small_leaf_f32(
        8u, -1, invalid_fft8, 8) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(invalid_fft8[0] == 1.0f && invalid_fft8[6] == 7.0f);
    assert(vea_s5_spp_fft_small_leaf_f32(
        16u, -1, fft8_negative, 8) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_18007db00 / FUN_18007e140: plan-backed 16/32 real-FFT complex leaves.
    uint32_t fft16_split[4] = {};
    float fft16_coefficients[8] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        16u, fft16_split, 4u, fft16_coefficients, 8u) ==
        VEA_S5_TENSOR_STATUS_OK);
    float fft16_input[16] = {};
    for (size_t index = 0; index < 16u; ++index) {
        const int value = static_cast<int>((index * 7u + 3u) % 19u) - 9;
        fft16_input[index] = static_cast<float>(value) * 0.125f;
    }
    AssertSppFftLeafMatchesDirectDft(
        16u, -1, fft16_input, fft16_coefficients);
    AssertSppFftLeafMatchesDirectDft(
        16u, 1, fft16_input, fft16_coefficients);

    uint32_t fft32_split[6] = {};
    float fft32_coefficients[16] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        32u, fft32_split, 6u, fft32_coefficients, 16u) ==
        VEA_S5_TENSOR_STATUS_OK);
    float fft32_input[32] = {};
    for (size_t index = 0; index < 32u; ++index) {
        const int value = static_cast<int>((index * 11u + 5u) % 23u) - 11;
        fft32_input[index] = static_cast<float>(value) * 0.0625f;
    }
    AssertSppFftLeafMatchesDirectDft(
        32u, -1, fft32_input, fft32_coefficients);
    AssertSppFftLeafMatchesDirectDft(
        32u, 1, fft32_input, fft32_coefficients);

    float fft16_unchanged[16] = {};
    std::memcpy(fft16_unchanged, fft16_input, sizeof(fft16_input));
    assert(vea_s5_spp_fft_leaf_f32(
        16u, -1, fft16_unchanged, 16u, fft16_coefficients, 1u) ==
        VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);
    assert(std::memcmp(fft16_unchanged, fft16_input, sizeof(fft16_input)) == 0);
    assert(vea_s5_spp_fft_leaf_f32(
        32u, -1, fft32_input, 31u, fft32_coefficients, 16u) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_18007fff0: mixed 64/128-float radix stages with direction-independent leaves.
    uint32_t fft64_split[7] = {};
    float fft64_coefficients[32] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        64u, fft64_split, 7u, fft64_coefficients, 32u) ==
        VEA_S5_TENSOR_STATUS_OK);
    float fft64_stage_input[64] = {};
    for (size_t index = 0; index < 64u; ++index) {
        fft64_stage_input[index] = static_cast<float>(
            static_cast<int>((index * 5u + 2u) % 17u) - 8) * 0.125f;
    }
    float fft64_stage_a[64] = {};
    float fft64_stage_b[64] = {};
    std::memcpy(fft64_stage_a, fft64_stage_input, sizeof(fft64_stage_input));
    std::memcpy(fft64_stage_b, fft64_stage_input, sizeof(fft64_stage_input));
    assert(vea_s5_spp_fft_radix_stage_f32(
        64u, fft64_stage_a, 64u, fft64_coefficients, 32u) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_spp_fft_radix_stage_f32(
        64u, fft64_stage_b, 64u, fft64_coefficients, 32u) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(fft64_stage_a, fft64_stage_b, sizeof(fft64_stage_a)) == 0);
    assert(std::memcmp(fft64_stage_a, fft64_stage_input, sizeof(fft64_stage_a)) != 0);
    for (float value : fft64_stage_a) assert(std::isfinite(value));
    const double fft64_energy_in = SumSquares(fft64_stage_input, 64u);
    const double fft64_energy_out = SumSquares(fft64_stage_a, 64u);
    assert(std::fabs(fft64_energy_out / fft64_energy_in - 8.0) < 2.0e-3);


    uint32_t fft128_split[10] = {};
    float fft128_coefficients[64] = {};
    assert(vea_s5_spp_fft_build_plan_f32(
        128u, fft128_split, 10u, fft128_coefficients, 64u) ==
        VEA_S5_TENSOR_STATUS_OK);
    float fft128_stage_input[128] = {};
    for (size_t index = 0; index < 128u; ++index) {
        fft128_stage_input[index] = static_cast<float>(
            static_cast<int>((index * 7u + 3u) % 23u) - 11) * 0.0625f;
    }
    float fft128_stage_a[128] = {};
    float fft128_stage_b[128] = {};
    std::memcpy(fft128_stage_a, fft128_stage_input, sizeof(fft128_stage_input));
    std::memcpy(fft128_stage_b, fft128_stage_input, sizeof(fft128_stage_input));
    assert(vea_s5_spp_fft_radix_stage_f32(
        128u, fft128_stage_a, 128u, fft128_coefficients, 64u) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_spp_fft_radix_stage_f32(
        128u, fft128_stage_b, 128u, fft128_coefficients, 64u) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(fft128_stage_a, fft128_stage_b, sizeof(fft128_stage_a)) == 0);
    assert(std::memcmp(fft128_stage_a, fft128_stage_input, sizeof(fft128_stage_a)) != 0);
    for (float value : fft128_stage_a) assert(std::isfinite(value));
    const double fft128_energy_in = SumSquares(fft128_stage_input, 128u);
    const double fft128_energy_out = SumSquares(fft128_stage_a, 128u);
    assert(std::fabs(fft128_energy_out / fft128_energy_in - 16.0) < 4.0e-3);


    float fft64_stage_unchanged[64] = {};
    std::memcpy(fft64_stage_unchanged, fft64_stage_input, sizeof(fft64_stage_input));
    assert(vea_s5_spp_fft_radix_stage_f32(
        64u, fft64_stage_unchanged, 64u, fft64_coefficients, 31u) ==
        VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);
    assert(std::memcmp(fft64_stage_unchanged, fft64_stage_input,
        sizeof(fft64_stage_input)) == 0);
    assert(vea_s5_spp_fft_radix_stage_f32(
        32u, fft64_stage_unchanged, 64u, fft64_coefficients, 32u) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_1800800c0: 256/512-lane large mixed-radix stages.
    for (const uint32_t fft_size : {256u, 512u}) {
        const size_t split_count = static_cast<size_t>(
            std::sqrt(static_cast<double>(fft_size / 2u))) + 2u;
        const size_t coefficient_count = fft_size / 2u;
        std::vector<uint32_t> split_table(split_count);
        std::vector<float> coefficients(coefficient_count);
        assert(vea_s5_spp_fft_build_plan_f32(
            fft_size, split_table.data(), split_table.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_OK);

        std::vector<float> input(fft_size);
        for (size_t index = 0; index < input.size(); ++index) {
            input[index] = static_cast<float>(
                static_cast<int>((index * 13u + 7u) % 31u) - 15) * 0.03125f;
        }
        std::vector<float> mode_zero_a = input;
        std::vector<float> mode_zero_b = input;
        std::vector<float> mode_one = input;
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            fft_size, 0u, mode_zero_a.data(), mode_zero_a.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_OK);
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            fft_size, 0u, mode_zero_b.data(), mode_zero_b.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_OK);
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            fft_size, 1u, mode_one.data(), mode_one.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_OK);
        assert(std::memcmp(mode_zero_a.data(), mode_zero_b.data(),
            mode_zero_a.size() * sizeof(float)) == 0);
        assert(std::memcmp(mode_zero_a.data(), input.data(),
            input.size() * sizeof(float)) != 0);
        assert(std::memcmp(mode_zero_a.data(), mode_one.data(),
            mode_zero_a.size() * sizeof(float)) != 0);
        for (float value : mode_zero_a) assert(std::isfinite(value));
        for (float value : mode_one) assert(std::isfinite(value));
        const double input_energy = SumSquares(input.data(), input.size());
        const double mode_zero_energy = SumSquares(mode_zero_a.data(), mode_zero_a.size());
        const double mode_one_energy = SumSquares(mode_one.data(), mode_one.size());
        const double expected_energy_ratio = static_cast<double>(fft_size) / 8.0;
        assert(std::fabs(mode_zero_energy / input_energy - expected_energy_ratio) < 2.0e-2);
        assert(std::fabs(mode_one_energy / input_energy - expected_energy_ratio) < 2.0e-2);

        std::vector<float> unchanged = input;
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            fft_size, 0u, unchanged.data(), unchanged.size(),
            coefficients.data(), coefficients.size() - 1u) ==
            VEA_S5_TENSOR_STATUS_BUFFER_TOO_SMALL);
        assert(std::memcmp(unchanged.data(), input.data(),
            input.size() * sizeof(float)) == 0);
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            fft_size, 2u, unchanged.data(), unchanged.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
        assert(vea_s5_spp_fft_large_radix_stage_f32(
            128u, 0u, unchanged.data(), unchanged.size(),
            coefficients.data(), coefficients.size()) ==
            VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    }
    // FUN_18007a000: FFT plan index is floor(log2(N)); zero maps to -1.
    int32_t fft_plan_index = 123;
    assert(vea_s5_spp_fft_log2_floor_u32(0u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == -1);
    assert(vea_s5_spp_fft_log2_floor_u32(1u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 0);
    assert(vea_s5_spp_fft_log2_floor_u32(2u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 1);
    assert(vea_s5_spp_fft_log2_floor_u32(3u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 1);
    assert(vea_s5_spp_fft_log2_floor_u32(4u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 2);
    assert(vea_s5_spp_fft_log2_floor_u32(512u, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 9);
    assert(vea_s5_spp_fft_log2_floor_u32(UINT32_MAX, &fft_plan_index) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(fft_plan_index == 31);
    assert(vea_s5_spp_fft_log2_floor_u32(8u, nullptr) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

            // FUN_180136150: periodic SPP frequency weighting.
    const float peak_weight_config[] = {10.0f, 2.0f, 4.0f, 6.0f, 3.0f};
    float peak_weight = 99.0f;
    assert(vea_s5_spp_peak_weight_f32(peak_weight_config, 1.0f, &peak_weight) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(peak_weight == 0.0f);
    assert(vea_s5_spp_peak_weight_f32(peak_weight_config, 3.0f, &peak_weight) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(peak_weight - (-1.5f)) < 1.0e-6f);
    assert(vea_s5_spp_peak_weight_f32(peak_weight_config, 13.0f, &peak_weight) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(peak_weight - (-1.5f)) < 1.0e-6f);
    assert(vea_s5_spp_peak_weight_f32(peak_weight_config, -7.0f, &peak_weight) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(peak_weight - (-1.5f)) < 1.0e-6f);
    assert(vea_s5_spp_peak_weight_f32(peak_weight_config, 6.0f, &peak_weight) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(peak_weight == 0.0f);

    // FUN_180135b90: stride-1 weighted argmax; the frequency penalty changes the winner.
    const float peak_values[] = {0.0f, 1.0f, 4.0f, 4.9f, 7.0f};
    size_t selected_peak = 99u;
    float selected_score = 0.0f;
    assert(vea_s5_spp_find_weighted_peak_f32(
        peak_values, 5, 1, 0, 4, 1.0f, 1.0f, peak_weight_config,
        &selected_peak, &selected_score) == VEA_S5_TENSOR_STATUS_OK);
    assert(selected_peak == 2u);
    assert(std::fabs(selected_score - 4.0f) < 1.0e-6f);

    // FUN_180135670: stride-2 [magnitude,phase] records; only lane zero is ranked.
    const float interleaved_peak_values[] = {
        0.0f, 1.0f, 1.0f, 2.0f, 4.0f, 3.0f, 4.9f, 4.0f, 7.0f, 5.0f
    };
    selected_peak = 99u;
    assert(vea_s5_spp_find_weighted_peak_f32(
        interleaved_peak_values, 5, 2, 0, 4, 1.0f, 1.0f, peak_weight_config,
        &selected_peak, &selected_score) == VEA_S5_TENSOR_STATUS_OK);
    assert(selected_peak == 2u);

    // Forward scan preserves the first index when scores tie.
    const float tied_peak_values[] = {0.0f, 4.0f, 4.0f, 0.0f};
    const float no_weight_config[] = {10.0f, 2.0f, 4.0f, 6.0f, 0.0f};
    assert(vea_s5_spp_find_weighted_peak_f32(
        tied_peak_values, 4, 1, 1, 2, 0.0f, 1.0f, no_weight_config,
        &selected_peak, &selected_score) == VEA_S5_TENSOR_STATUS_OK);
    assert(selected_peak == 1u);

    // Invalid SPP peak requests must not overwrite result outputs.
    selected_peak = 77u;
    selected_score = 88.0f;
    assert(vea_s5_spp_find_weighted_peak_f32(
        peak_values, 5, 3, 0, 4, 1.0f, 1.0f, peak_weight_config,
        &selected_peak, &selected_score) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(selected_peak == 77u && selected_score == 88.0f);
    assert(vea_s5_spp_peak_weight_f32(
        peak_weight_config, 2.0f, nullptr) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

// FUN_180135490: deterministic xorshift phase assignment from magnitude bins.
    const float spp_magnitudes[] = {1.0f, 2.0f, 3.0f, 4.0f};
    const uint32_t initial_phase_state[] = {0x12345678u, 0x9abcdef0u, 0x31415926u, 0x27182818u};
    uint32_t phase_state_a[4] = {
        initial_phase_state[0], initial_phase_state[1],
        initial_phase_state[2], initial_phase_state[3]
    };
    uint32_t phase_state_b[4] = {
        initial_phase_state[0], initial_phase_state[1],
        initial_phase_state[2], initial_phase_state[3]
    };
    float phase_output_a[8] = {};
    float phase_output_b[8] = {};
    assert(vea_s5_spp_generate_phase_noise_f32(
        spp_magnitudes, 4, 0u, phase_state_a, phase_output_a, 8) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_spp_generate_phase_noise_f32(
        spp_magnitudes, 4, 0u, phase_state_b, phase_output_b, 8) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(phase_output_a, phase_output_b, sizeof(phase_output_a)) == 0);
    assert(std::memcmp(phase_state_a, phase_state_b, sizeof(phase_state_a)) == 0);
    const uint32_t expected_phase_state_after_four_steps[] = {
        0x972f3d15u, 0xeb183a5eu, 0xd0abc672u, 0x36156c97u
    };
    assert(std::memcmp(
        phase_state_a, expected_phase_state_after_four_steps,
        sizeof(expected_phase_state_after_four_steps)) == 0);
    assert(std::memcmp(phase_state_a, initial_phase_state, sizeof(phase_state_a)) != 0);
    assert(phase_output_a[1] == 0.0f);
    assert(phase_output_a[7] == 0.0f);
    assert(std::fabs(std::fabs(phase_output_a[0]) - spp_magnitudes[0]) < 1.0e-6f);
    assert(std::fabs(std::hypot(phase_output_a[2], phase_output_a[3]) - spp_magnitudes[1]) < 1.0e-5f);
    assert(std::fabs(std::hypot(phase_output_a[4], phase_output_a[5]) - spp_magnitudes[2]) < 1.0e-5f);
    assert(std::fabs(std::fabs(phase_output_a[6]) - spp_magnitudes[3]) < 1.0e-6f);

    // Edge mode 1 keeps the terminal bin phase-rotated rather than real-only.
    uint32_t phase_state_edge[4] = {
        initial_phase_state[0], initial_phase_state[1],
        initial_phase_state[2], initial_phase_state[3]
    };
    float phase_output_edge[8] = {};
    assert(vea_s5_spp_generate_phase_noise_f32(
        spp_magnitudes, 4, 1u, phase_state_edge, phase_output_edge, 8) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(phase_output_edge[1] == 0.0f);
    assert(std::fabs(std::hypot(phase_output_edge[6], phase_output_edge[7]) -
        spp_magnitudes[3]) < 1.0e-5f);

    // The one-bin even mode follows the original DC then terminal-bin update path.
    const float single_magnitude[] = {2.0f};
    uint32_t single_state[4] = {
        initial_phase_state[0], initial_phase_state[1],
        initial_phase_state[2], initial_phase_state[3]
    };
    float single_output[2] = {};
    assert(vea_s5_spp_generate_phase_noise_f32(
        single_magnitude, 1, 0u, single_state, single_output, 2) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(single_output[1] == 0.0f);
    assert(std::fabs(std::fabs(single_output[0]) - 2.0f) < 1.0e-6f);
    assert(single_state[0] == 0x31415926u && single_state[1] == 0x27182818u &&
        single_state[2] == 0x972f3d15u && single_state[3] == 0xeb183a5eu);
    // Invalid requests do not mutate the caller RNG state or output buffer.
    uint32_t invalid_phase_state[4] = {
        initial_phase_state[0], initial_phase_state[1],
        initial_phase_state[2], initial_phase_state[3]
    };
    float untouched_phase_output[8] = {9, 9, 9, 9, 9, 9, 9, 9};
    float invalid_phase_magnitudes[4] = {1.0f, 2.0f, std::numeric_limits<float>::quiet_NaN(), 4.0f};
    assert(vea_s5_spp_generate_phase_noise_f32(
        invalid_phase_magnitudes, 4, 0u, invalid_phase_state,
        untouched_phase_output, 8) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(std::memcmp(invalid_phase_state, initial_phase_state, sizeof(invalid_phase_state)) == 0);
    for (float value : untouched_phase_output) assert(value == 9.0f);
    assert(vea_s5_spp_generate_phase_noise_f32(
        spp_magnitudes, 4, 0u, invalid_phase_state,
        untouched_phase_output, 7) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(std::memcmp(invalid_phase_state, initial_phase_state, sizeof(invalid_phase_state)) == 0);
    assert(vea_s5_spp_generate_phase_noise_f32(
        nullptr, 0, 0u, nullptr, nullptr, 0) == VEA_S5_TENSOR_STATUS_OK);
// FUN_180139870: absolute-position table lookup with linear interpolation.
    const double spp_window_table[] = {0.0, 1.0, 4.0, 9.0, 16.0};
    double spp_window_value = 0.0;
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 5, 4, 2.0, -99.0, 0.75, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(spp_window_value - 2.5) < 1.0e-12);
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 5, 4, 1.0, -99.0, -2.5, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(spp_window_value - 6.5) < 1.0e-12);
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 5, 4, 1.0, -99.0, 4.0, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(spp_window_value == -99.0);
    assert(vea_s5_spp_lookup_table_f64(
        nullptr, 0, 0, 1.0, 12.0, 0.25, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(spp_window_value == 12.0);
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 4, 4, 1.0, -99.0, 0.5, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 5, 4, -1.0, -99.0, 0.5, &spp_window_value) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_spp_lookup_table_f64(
        spp_window_table, 5, 4, 1.0, -99.0, 0.5, nullptr) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);


    // FUN_1801343c0: parabolic interpolation of a [magnitude,phase] spectral peak.
    const float interpolation_spectrum[] = {
        0.5f, 0.0f,
        2.0f, 3.1f,
        4.0f, -3.0f,
        1.0f, -2.9f
    };
    float interpolated_magnitude = 0.0f;
    float interpolated_phase = 0.0f;
    assert(vea_s5_spp_interpolate_peak_f32(
        interpolation_spectrum, 4, 2,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(interpolated_magnitude - 4.025f) < 1.0e-5f);
    assert(std::fabs(interpolated_phase - 3.26486f) < 2.0e-4f);

    // Positive sub-bin offsets interpolate from the center phase toward the right bin.
    const float positive_offset_spectrum[] = {
        1.0f, 0.5f,
        4.0f, 1.0f,
        3.0f, 1.4f
    };
    assert(vea_s5_spp_interpolate_peak_f32(
        positive_offset_spectrum, 3, 1,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(std::fabs(interpolated_magnitude - 4.125f) < 1.0e-5f);
    assert(std::fabs(interpolated_phase - 1.1f) < 1.0e-5f);

    // Edge bins and non-local maxima are returned unchanged.
    assert(vea_s5_spp_interpolate_peak_f32(
        interpolation_spectrum, 4, 0,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(interpolated_magnitude == 0.5f && interpolated_phase == 0.0f);
    const float non_peak_spectrum[] = {1.0f, 0.1f, 1.0f, 0.2f, 2.0f, 0.3f};
    assert(vea_s5_spp_interpolate_peak_f32(
        non_peak_spectrum, 3, 1,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(interpolated_magnitude == 1.0f && interpolated_phase == 0.2f);

    const float invalid_interpolation_spectrum[] = {
        1.0f, 0.0f, 2.0f, std::numeric_limits<float>::quiet_NaN(), 1.0f, 0.0f
    };
    assert(vea_s5_spp_interpolate_peak_f32(
        invalid_interpolation_spectrum, 3, 1,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_spp_interpolate_peak_f32(
        interpolation_spectrum, 4, 4,
        &interpolated_magnitude, &interpolated_phase) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

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

    // VEA-side layout adapter: VTMG [bank][input][paddedOutput] -> v0_naive [bank][output][input].
    const float packed_for_v0_naive[] = {
        0.0f, 1.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f,
        10.0f, 11.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f,
        20.0f, 21.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f,
        100.0f, 101.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f,
        110.0f, 111.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f,
        120.0f, 121.0f, 90.0f, 91.0f, 92.0f, 93.0f, 94.0f, 95.0f
    };
    float v0_naive_weights[12] = {};
    assert(vea_s5_vtmg_bank_to_v0_naive_a_f32(
        packed_for_v0_naive, 48, 2, 3, 8, 2,
        v0_naive_weights, 12) == VEA_S5_TENSOR_STATUS_OK);
    const float expected_v0_naive_weights[] = {
        0.0f, 10.0f, 20.0f,
        1.0f, 11.0f, 21.0f,
        100.0f, 110.0f, 120.0f,
        101.0f, 111.0f, 121.0f
    };
    for (size_t i = 0; i < 12; ++i) {
        assert(v0_naive_weights[i] == expected_v0_naive_weights[i]);
    }
    assert(vea_s5_vtmg_bank_to_v0_naive_a_f32(
        packed_for_v0_naive, 47, 2, 3, 8, 2,
        v0_naive_weights, 12) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_vtmg_bank_to_v0_naive_a_f32(
        packed_for_v0_naive, 48, 2, 3, 4, 2,
        v0_naive_weights, 12) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_vtmg_bank_to_v0_naive_a_f32(
        packed_for_v0_naive, 48, 2, 3, 8, 2,
        v0_naive_weights, 11) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

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

    // FUN_1800c72d0: endpoint-clamped 2x interpolation and zero-padded 3-tap projection.
    const float upsample_input[] = {2.0f, 4.0f};
    const float upsample_weights[] = {1.0f, 10.0f, 100.0f};
    const float upsample_bias[] = {0.25f};
    float upsample_output[4] = {};
    assert(vea_s5_upsample2_conv3_projection_f32(
        upsample_input, upsample_weights, upsample_bias, upsample_output,
        1, 1, 2) == VEA_S5_TENSOR_STATUS_OK);
    const float upsample_expected[] = {270.25f, 377.25f, 437.75f, 43.75f};
    for (size_t i = 0; i < 4; ++i) {
        assert(std::fabs(upsample_output[i] - upsample_expected[i]) < 1.0e-5f);
    }
    assert(vea_s5_upsample2_conv3_projection_f32(
        nullptr, upsample_weights, nullptr, upsample_output, 1, 1, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    // FUN_1800c5440: stride-2 Conv1D lowered to [channel*tap][outputPosition] GEMM input.
    const float stride2_input[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    const float stride2_weights[] = {1.0f, 10.0f, 100.0f};
    const float stride2_bias[] = {0.5f};
    float stride2_output[3] = {};
    assert(vea_s5_stride2_conv1d_projection_f32(
        stride2_input, stride2_weights, stride2_bias, stride2_output,
        1, 1, 5, 3) == VEA_S5_TENSOR_STATUS_OK);
    const float stride2_expected[] = {210.5f, 432.5f, 54.5f};
    for (size_t i = 0; i < 3; ++i) {
        assert(std::fabs(stride2_output[i] - stride2_expected[i]) < 1.0e-6f);
    }

    // Kernel width 1 follows the exact original output-length expression.
    const float stride2_point_input[] = {2.0f, 3.0f, 5.0f, 7.0f};
    const float stride2_point_weight[] = {2.0f};
    float stride2_point_output[3] = {};
    assert(vea_s5_stride2_conv1d_projection_f32(
        stride2_point_input, stride2_point_weight, nullptr, stride2_point_output,
        1, 1, 4, 1) == VEA_S5_TENSOR_STATUS_OK);
    assert(stride2_point_output[0] == 4.0f);
    assert(stride2_point_output[1] == 10.0f);
    assert(stride2_point_output[2] == 0.0f);

    // Even kernel widths use floor(N/2)+1 outputs, matching the source formula.
    const float stride2_even_input[] = {2.0f, 3.0f, 5.0f, 7.0f};
    const float stride2_even_weights[] = {1.0f, 10.0f};
    float stride2_even_output[3] = {};
    assert(vea_s5_stride2_conv1d_projection_f32(
        stride2_even_input, stride2_even_weights, nullptr, stride2_even_output,
        1, 1, 4, 2) == VEA_S5_TENSOR_STATUS_OK);
    assert(stride2_even_output[0] == 20.0f);
    assert(stride2_even_output[1] == 53.0f);
    assert(stride2_even_output[2] == 7.0f);

    assert(vea_s5_stride2_conv1d_projection_f32(
        stride2_input, stride2_weights, nullptr, stride2_output,
        1, 1, 5, 0) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_stride2_conv1d_projection_f32(
        stride2_input, stride2_weights, nullptr, stride2_output,
        0, 1, 5, 3) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

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

            // Model-facing c5d50 path: convert 32-wide A/B vectors into expanded GELU affine vectors.
    const float condition_a[] = {2.0f};
    const float condition_b[] = {3.0f};
    const float stored_expansion_bias[] = {0.5f, -0.25f};
    float expanded_bias_a[2] = {};
    float expanded_bias_b[2] = {};
    assert(vea_s5_prepare_c5d50_expansion_bias_f32(
        block_expand_weights, condition_a, condition_b, stored_expansion_bias,
        expanded_bias_a, expanded_bias_b, 1, 2) == VEA_S5_TENSOR_STATUS_OK);
    assert(expanded_bias_a[0] == 3.5f && expanded_bias_a[1] == 5.75f);
    assert(expanded_bias_b[0] == 2.0f && expanded_bias_b[1] == 4.0f);
    assert(vea_s5_prepare_c5d50_expansion_bias_f32(
        block_expand_weights, nullptr, condition_b, stored_expansion_bias,
        expanded_bias_a, expanded_bias_b, 1, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

    float model_block_output[7] = {};
    assert(vea_s5_run_vtb2_c5d50_block_f32(
        block_history, block_dw_weights, condition_a, condition_b,
        block_expand_weights, stored_expansion_bias, block_project_weights,
        block_residual_bias, block_residual_scale, block_residual,
        model_block_output, block_channels, block_positions, block_expanded, 0.25f) ==
        VEA_S5_TENSOR_STATUS_OK);
    for (size_t pos = 0; pos < block_positions; ++pos) {
        const float base = static_cast<float>(pos + 1u);
        const float x1 = base * block_inv_std + expanded_bias_a[0] -
            block_inv_std * 4.0f * expanded_bias_b[0];
        const float x2 = 2.0f * base * block_inv_std + expanded_bias_a[1] -
            block_inv_std * 4.0f * expanded_bias_b[1];
        const float model_gelu1 = x1 / (1.0f + std::exp(-((x1*x1*x1*0.044715f + x1)*1.5957692f)));
        const float model_gelu2 = x2 / (1.0f + std::exp(-((x2*x2*x2*0.044715f + x2)*1.5957692f)));
        const float model_expected = (model_gelu1 + 0.5f * model_gelu2 + 0.25f) * 2.0f + 0.5f;
        assert(std::fabs(model_block_output[pos] - model_expected) < 1.0e-5f);
    }
    assert(vea_s5_run_vtb2_c5d50_block_f32(
        block_history, block_dw_weights, condition_a, condition_b,
        block_expand_weights, stored_expansion_bias, block_project_weights,
        block_residual_bias, block_residual_scale, nullptr,
        model_block_output, block_channels, block_positions, block_expanded, 0.25f) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);

// S5API FUN_18002aa90: 16-byte prefix, seeded body transform, opaque trailer.
    constexpr size_t kPrefixBytes = VEA_S5_CONTAINER_PREFIX_SIZE;
    constexpr size_t kDecodedBytes = 13;
    constexpr size_t kTrailerBytes = 128;
    constexpr size_t kFileBytes = kPrefixBytes + kDecodedBytes + kTrailerBytes;
    uint8_t raw_file[kFileBytes] = {};
    const char* magics[] = {"VTMB", "VTBR", "VTMG", "VPIT", "VNPV", "VHNV"};
    const uint32_t kinds[] = {
        VEA_S5_CONTAINER_KIND_VTMB, VEA_S5_CONTAINER_KIND_VTBR,
        VEA_S5_CONTAINER_KIND_VTMG, VEA_S5_CONTAINER_KIND_VPIT,
        VEA_S5_CONTAINER_KIND_VNPV, VEA_S5_CONTAINER_KIND_VHNV
    };
    const uint32_t declared_span = static_cast<uint32_t>(kFileBytes - 136u);
    for (size_t i = 0; i < 6; ++i) {
        std::memcpy(raw_file, magics[i], 4);
        for (size_t byte = 0; byte < 4; ++byte) {
            raw_file[4 + byte] = static_cast<uint8_t>(declared_span >> (byte * 8u));
            raw_file[8 + byte] = static_cast<uint8_t>(16u >> (byte * 8u));
            raw_file[12 + byte] = static_cast<uint8_t>(0x12345678u >> (byte * 8u));
        }
        VeaS5ContainerHeader parsed{};
        assert(vea_s5_parse_container_header(raw_file, kFileBytes, &parsed) ==
            VEA_S5_CONTAINER_STATUS_OK);
        assert(parsed.kind == kinds[i]);
        assert(parsed.declared_span == declared_span);
        assert(parsed.format_word == 16u);
        assert(parsed.transform_seed == 0x12345678u);
        assert(parsed.decoded_payload_size == kDecodedBytes);
    }

    // Fixed ciphertext bytes cover three complete words plus the distinct tail path.
    // The final 128 bytes are deliberately nonzero and must not enter the decoded body.
    const uint8_t encrypted_body[kDecodedBytes] = {
        0x21u, 0x22u, 0x23u, 0x24u, 0x25u, 0x26u, 0x27u,
        0x28u, 0x29u, 0x2au, 0x2bu, 0x2cu, 0x2du
    };
    std::memcpy(raw_file + kPrefixBytes, encrypted_body, sizeof(encrypted_body));
    std::memset(raw_file + kPrefixBytes + kDecodedBytes, 0xee, kTrailerBytes);
    const uint8_t expected_decoded[kDecodedBytes] = {
        0x55u, 0x20u, 0x5du, 0x91u, 0x66u, 0x7au, 0xabu,
        0x5au, 0x1du, 0x24u, 0xe6u, 0xd0u, 0x38u
    };
    uint8_t decoded[kDecodedBytes] = {};
    uint64_t decoded_size = 0;
    assert(vea_s5_decode_container_payload(
        raw_file, kFileBytes, decoded, sizeof(decoded), &decoded_size) ==
        VEA_S5_CONTAINER_STATUS_OK);
    assert(decoded_size == kDecodedBytes);
    assert(std::memcmp(decoded, expected_decoded, sizeof(decoded)) == 0);

    uint64_t required_size = 0;
    assert(vea_s5_decode_container_payload(
        raw_file, kFileBytes, decoded, sizeof(decoded) - 1u, &required_size) ==
        VEA_S5_CONTAINER_STATUS_BUFFER_TOO_SMALL);
    assert(required_size == kDecodedBytes);

    VeaS5ContainerHeader invalid_header{};
    const uint8_t invalid_magic[VEA_S5_CONTAINER_PREFIX_SIZE] = {};
    assert(vea_s5_parse_container_header(
        invalid_magic, kFileBytes, &invalid_header) ==
        VEA_S5_CONTAINER_STATUS_INVALID_MAGIC);
    assert(vea_s5_parse_container_header(
        raw_file, VEA_S5_CONTAINER_PREFIX_SIZE - 1u, &invalid_header) ==
        VEA_S5_CONTAINER_STATUS_INVALID_LENGTH);
    assert(vea_s5_decode_container_payload(
        raw_file, VEA_S5_CONTAINER_PREFIX_SIZE - 1u, decoded,
        sizeof(decoded), &decoded_size) == VEA_S5_CONTAINER_STATUS_INVALID_LENGTH);

    // FUN_1800c67e0: append blocks to a fixed ring and expose oldest-to-newest history.
    VeaS5HistoryRing* history = nullptr;
    assert(vea_s5_history_ring_create(1, 3, 7, &history) == VEA_S5_TENSOR_STATUS_OK);
    assert(history != nullptr);
    assert(vea_s5_history_ring_block_size(history) == 3);
    assert(vea_s5_history_ring_row_count(history) == 7);
    float history_snapshot[21] = {};
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 21) == VEA_S5_TENSOR_STATUS_OK);
    for (float value : history_snapshot) assert(value == 0.0f);

    const float history_block_1[] = {1.0f, 1.5f, 2.0f};
    const float history_block_2[] = {3.0f, 3.5f, 4.0f};
    assert(vea_s5_history_ring_append(history, history_block_1, 3) == VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 21) == VEA_S5_TENSOR_STATUS_OK);
    for (size_t i = 0; i < 18; ++i) assert(history_snapshot[i] == 0.0f);
    assert(history_snapshot[18] == 1.0f && history_snapshot[19] == 1.5f &&
        history_snapshot[20] == 2.0f);

    assert(vea_s5_history_ring_append(history, history_block_2, 3) == VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 21) == VEA_S5_TENSOR_STATUS_OK);
    for (size_t i = 0; i < 15; ++i) assert(history_snapshot[i] == 0.0f);
    assert(history_snapshot[15] == 1.0f && history_snapshot[16] == 1.5f &&
        history_snapshot[17] == 2.0f);
    assert(history_snapshot[18] == 3.0f && history_snapshot[19] == 3.5f &&
        history_snapshot[20] == 4.0f);

    for (size_t block = 3; block <= 9; ++block) {
        const float values[] = {
            static_cast<float>(block),
            static_cast<float>(block) + 0.5f,
            static_cast<float>(block) + 1.0f
        };
        assert(vea_s5_history_ring_append(history, values, 3) == VEA_S5_TENSOR_STATUS_OK);
    }
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 21) == VEA_S5_TENSOR_STATUS_OK);
    for (size_t row = 0; row < 7; ++row) {
        const float block = static_cast<float>(row + 3);
        assert(history_snapshot[row * 3] == block);
        assert(history_snapshot[row * 3 + 1] == block + 0.5f);
        assert(history_snapshot[row * 3 + 2] == block + 1.0f);
    }
    assert(vea_s5_history_ring_append(history, history_block_1, 2) ==
        VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 20) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    assert(vea_s5_history_ring_reset(history) == VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_copy_chronological(
        history, history_snapshot, 21) == VEA_S5_TENSOR_STATUS_OK);
    for (float value : history_snapshot) assert(value == 0.0f);

    // Direct native transpose: chronological [row][channel][position] to
    // channel-major [channel][row][position], including wraparound.
    VeaS5HistoryRing* channel_major_history = nullptr;
    assert(vea_s5_history_ring_create(2, 2, 3, &channel_major_history) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float cm_block_0[] = {1.0f, 2.0f, 101.0f, 102.0f};
    const float cm_block_1[] = {3.0f, 4.0f, 103.0f, 104.0f};
    const float cm_block_2[] = {5.0f, 6.0f, 105.0f, 106.0f};
    const float cm_block_3[] = {7.0f, 8.0f, 107.0f, 108.0f};
    assert(vea_s5_history_ring_append(channel_major_history, cm_block_0, 4) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_append(channel_major_history, cm_block_1, 4) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_append(channel_major_history, cm_block_2, 4) ==
        VEA_S5_TENSOR_STATUS_OK);
    assert(vea_s5_history_ring_append(channel_major_history, cm_block_3, 4) ==
        VEA_S5_TENSOR_STATUS_OK);
    const float expected_chronological[] = {
        3.0f, 4.0f, 103.0f, 104.0f,
        5.0f, 6.0f, 105.0f, 106.0f,
        7.0f, 8.0f, 107.0f, 108.0f
    };
    float cm_chronological[12] = {};
    assert(vea_s5_history_ring_copy_chronological(
        channel_major_history, cm_chronological, 12) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(cm_chronological, expected_chronological,
        sizeof(expected_chronological)) == 0);
    const float expected_channel_major[] = {
        3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f,
        103.0f, 104.0f, 105.0f, 106.0f, 107.0f, 108.0f
    };
    float cm_output[12] = {};
    assert(vea_s5_history_ring_copy_channel_major(
        channel_major_history, cm_output, 12) == VEA_S5_TENSOR_STATUS_OK);
    assert(std::memcmp(cm_output, expected_channel_major,
        sizeof(expected_channel_major)) == 0);
    assert(vea_s5_history_ring_copy_channel_major(
        channel_major_history, cm_output, 11) == VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT);
    vea_s5_history_ring_destroy(channel_major_history);

    vea_s5_history_ring_destroy(history);

    vea_s5_arena_reset(arena);
    assert(vea_s5_arena_used(arena) == 0);
    vea_s5_arena_destroy(arena);
    return 0;
}
