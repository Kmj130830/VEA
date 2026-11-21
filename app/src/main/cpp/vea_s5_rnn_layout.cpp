#include "vea_s5_tensor.h"

#include <limits>

namespace {
bool MultiplyU64(uint64_t a, uint64_t b, uint64_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<uint64_t>::max() / a)) {
        return false;
    }
    *out = a * b;
    return true;
}
}

static_assert(sizeof(VeaS5RnnDirectionLayout) == 56u,
              "RNN layout descriptor ABI must remain stable");

extern "C" VeaS5TensorStatus vea_s5_rnn_direction_layout(
    uint32_t input_size, uint32_t hidden_size, uint32_t num_layers,
    int has_bias, int bidirectional, uint32_t layer_index,
    uint32_t direction_index, VeaS5RnnDirectionLayout* out_layout) {
    if (out_layout == nullptr || input_size == 0u || hidden_size == 0u ||
        num_layers == 0u || layer_index >= num_layers ||
        (has_bias != 0 && has_bias != 1) ||
        (bidirectional != 0 && bidirectional != 1)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    const uint32_t direction_count = bidirectional != 0 ? 2u : 1u;
    if (direction_index >= direction_count) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;

    uint64_t gate_features = 0u;
    if (!MultiplyU64(hidden_size, 3u, &gate_features) ||
        gate_features > std::numeric_limits<uint32_t>::max()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    uint64_t input_features = input_size;
    if (layer_index != 0u &&
        !MultiplyU64(hidden_size, direction_count, &input_features)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    if (input_features > std::numeric_limits<uint32_t>::max()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    uint64_t weight_ih = 0u;
    uint64_t weight_hh = 0u;
    if (!MultiplyU64(input_features, gate_features, &weight_ih) ||
        !MultiplyU64(hidden_size, gate_features, &weight_hh)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    VeaS5RnnDirectionLayout result{};
    result.layer_index = layer_index;
    result.direction_index = direction_index;
    result.input_features = static_cast<uint32_t>(input_features);
    result.hidden_features = hidden_size;
    result.gate_features = static_cast<uint32_t>(gate_features);
    result.direction_count = direction_count;
    result.weight_ih_elements = weight_ih;
    result.weight_hh_elements = weight_hh;
    result.bias_ih_elements = has_bias != 0 ? gate_features : 0u;
    result.bias_hh_elements = has_bias != 0 ? gate_features : 0u;
    *out_layout = result;
    return VEA_S5_TENSOR_STATUS_OK;
}
