#include "vea_s5_tensor.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

struct VeaS5HistoryRing {
    size_t channels = 0;
    size_t positions = 0;
    size_t history_rows = 0;
    size_t block_size = 0;
    size_t next_write_row = 0;
    std::vector<float> storage;
};

namespace {
bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0u && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}
}  // namespace

extern "C" VeaS5TensorStatus vea_s5_history_ring_create(
    size_t channels, size_t positions, size_t history_rows,
    VeaS5HistoryRing** out_ring) {
    if (out_ring == nullptr || channels == 0u || positions == 0u || history_rows == 0u) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_ring = nullptr;
    size_t block_size = 0u;
    size_t total = 0u;
    if (MultiplyOverflow(channels, positions, &block_size) ||
        MultiplyOverflow(block_size, history_rows, &total) ||
        total > std::vector<float>().max_size()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    try {
        auto* ring = new (std::nothrow) VeaS5HistoryRing();
        if (ring == nullptr) return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
        ring->channels = channels;
        ring->positions = positions;
        ring->history_rows = history_rows;
        ring->block_size = block_size;
        ring->next_write_row = 0u;
        try {
            ring->storage.assign(total, 0.0f);
        } catch (...) {
            delete ring;
            throw;
        }
        *out_ring = ring;
        return VEA_S5_TENSOR_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    } catch (const std::length_error&) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
}

extern "C" void vea_s5_history_ring_destroy(VeaS5HistoryRing* ring) {
    delete ring;
}

extern "C" VeaS5TensorStatus vea_s5_history_ring_reset(VeaS5HistoryRing* ring) {
    if (ring == nullptr) return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    std::fill(ring->storage.begin(), ring->storage.end(), 0.0f);
    ring->next_write_row = 0u;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_history_ring_append(
    VeaS5HistoryRing* ring, const float* block, size_t block_count) {
    if (ring == nullptr || block == nullptr || block_count != ring->block_size) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    const size_t offset = ring->next_write_row * ring->block_size;
    std::memcpy(ring->storage.data() + offset, block, ring->block_size * sizeof(float));
    ring->next_write_row++;
    if (ring->next_write_row == ring->history_rows) ring->next_write_row = 0u;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_history_ring_copy_chronological(
    const VeaS5HistoryRing* ring, float* output, size_t output_count) {
    if (ring == nullptr || output == nullptr ||
        output_count != ring->storage.size()) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    for (size_t row = 0; row < ring->history_rows; ++row) {
        const size_t source_row = (ring->next_write_row + row) % ring->history_rows;
        std::memcpy(
            output + row * ring->block_size,
            ring->storage.data() + source_row * ring->block_size,
            ring->block_size * sizeof(float));
    }
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_history_ring_copy_channel_major(
    const VeaS5HistoryRing* ring, float* output, size_t output_count) {
    if (ring == nullptr || output == nullptr ||
        output_count != ring->storage.size()) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    // Reorder chronological [row][channel][position] directly into
    // [channel][row][position], avoiding a second Kotlin-side allocation.
    for (size_t row = 0; row < ring->history_rows; ++row) {
        const size_t source_row = (ring->next_write_row + row) % ring->history_rows;
        const float* source_base = ring->storage.data() + source_row * ring->block_size;
        for (size_t channel = 0; channel < ring->channels; ++channel) {
            const size_t source_offset = channel * ring->positions;
            const size_t destination_offset =
                (channel * ring->history_rows + row) * ring->positions;
            std::memcpy(
                output + destination_offset,
                source_base + source_offset,
                ring->positions * sizeof(float));
        }
    }
    return VEA_S5_TENSOR_STATUS_OK;
}
extern "C" size_t vea_s5_history_ring_block_size(const VeaS5HistoryRing* ring) {
    return ring == nullptr ? 0u : ring->block_size;
}

extern "C" size_t vea_s5_history_ring_row_count(const VeaS5HistoryRing* ring) {
    return ring == nullptr ? 0u : ring->history_rows;
}
