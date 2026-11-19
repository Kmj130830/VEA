#include "vea_s5_tensor.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>

#if defined(_WIN32)
  #include <malloc.h>
#else
  #include <cstdlib>
#endif

struct VeaS5Arena {
    uint8_t* memory = nullptr;
    size_t capacity = 0;
    size_t used = 0;
};

namespace {

constexpr size_t kAlignment = VEA_S5_ARENA_ALIGNMENT;
constexpr size_t kDescriptorBytes = VEA_S5_MATRIX_DESCRIPTOR_SIZE;

bool AddOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || a > std::numeric_limits<size_t>::max() - b) return true;
    *out = a + b;
    return false;
}

bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0 && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}

bool AlignUp(size_t value, size_t alignment, size_t* out) {
    if (out == nullptr || alignment == 0 || (alignment & (alignment - 1)) != 0) return false;
    size_t sum = 0;
    if (AddOverflow(value, alignment - 1, &sum)) return false;
    *out = sum & ~(alignment - 1);
    return true;
}

void* AllocateAligned(size_t bytes) {
    if (bytes == 0) return nullptr;
#if defined(_WIN32)
    return _aligned_malloc(bytes, kAlignment);
#else
    void* pointer = nullptr;
    if (posix_memalign(&pointer, kAlignment, bytes) != 0) return nullptr;
    return pointer;
#endif
}

void FreeAligned(void* pointer) {
#if defined(_WIN32)
    _aligned_free(pointer);
#else
    std::free(pointer);
#endif
}

VeaS5TensorStatus AllocateBytes(
    VeaS5Arena* arena,
    size_t logical_bytes,
    size_t* out_offset) {
    if (arena == nullptr || out_offset == nullptr) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }

    size_t reserved_bytes = 0;
    if (!AlignUp(logical_bytes, kAlignment, &reserved_bytes)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    if (arena->used > arena->capacity || reserved_bytes > arena->capacity - arena->used) {
        return VEA_S5_TENSOR_STATUS_ARENA_EXHAUSTED;
    }

    *out_offset = arena->used;
    arena->used += reserved_bytes;
    return VEA_S5_TENSOR_STATUS_OK;
}

}  // namespace

static_assert(sizeof(VeaS5MatrixDescriptor) == kDescriptorBytes,
              "S5API matrix descriptor must remain 0x18 bytes");
static_assert(offsetof(VeaS5MatrixDescriptor, axis0) == 0x00, "axis0 offset mismatch");
static_assert(offsetof(VeaS5MatrixDescriptor, axis1) == 0x04, "axis1 offset mismatch");
static_assert(offsetof(VeaS5MatrixDescriptor, padded_axis0) == 0x08,
              "padded axis offset mismatch");
static_assert(offsetof(VeaS5MatrixDescriptor, values) == 0x10, "values pointer offset mismatch");

extern "C" uint32_t vea_s5_tensor_abi_version(void) {
    return VEA_S5_TENSOR_ABI_VERSION;
}

extern "C" VeaS5TensorStatus vea_s5_arena_create(
    size_t capacity_bytes,
    VeaS5Arena** out_arena) {
    if (out_arena == nullptr || capacity_bytes == 0) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_arena = nullptr;

    size_t capacity = 0;
    if (!AlignUp(capacity_bytes, kAlignment, &capacity)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }

    auto* arena = new (std::nothrow) VeaS5Arena();
    if (arena == nullptr) return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;

    arena->memory = static_cast<uint8_t*>(AllocateAligned(capacity));
    if (arena->memory == nullptr) {
        delete arena;
        return VEA_S5_TENSOR_STATUS_OUT_OF_MEMORY;
    }
    arena->capacity = capacity;
    arena->used = 0;
    std::memset(arena->memory, 0, capacity);
    *out_arena = arena;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" void vea_s5_arena_destroy(VeaS5Arena* arena) {
    if (arena == nullptr) return;
    FreeAligned(arena->memory);
    arena->memory = nullptr;
    delete arena;
}

extern "C" size_t vea_s5_arena_capacity(const VeaS5Arena* arena) {
    return arena == nullptr ? 0 : arena->capacity;
}

extern "C" size_t vea_s5_arena_used(const VeaS5Arena* arena) {
    return arena == nullptr ? 0 : arena->used;
}

extern "C" void vea_s5_arena_reset(VeaS5Arena* arena) {
    if (arena == nullptr) return;
    arena->used = 0;
    if (arena->memory != nullptr && arena->capacity != 0) {
        std::memset(arena->memory, 0, arena->capacity);
    }
}

extern "C" VeaS5TensorStatus vea_s5_arena_alloc_f32(
    VeaS5Arena* arena,
    size_t count,
    float** out_values) {
    if (arena == nullptr || out_values == nullptr) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_values = nullptr;

    size_t bytes = 0;
    if (MultiplyOverflow(count, sizeof(float), &bytes)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    size_t offset = 0;
    const VeaS5TensorStatus status = AllocateBytes(arena, bytes, &offset);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    *out_values = reinterpret_cast<float*>(arena->memory + offset);
    if (bytes != 0) std::memset(*out_values, 0, bytes);
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_arena_alloc_matrix_descriptors(
    VeaS5Arena* arena,
    size_t count,
    VeaS5MatrixDescriptor** out_descriptors) {
    if (arena == nullptr || out_descriptors == nullptr) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_descriptors = nullptr;

    size_t bytes = 0;
    if (MultiplyOverflow(count, kDescriptorBytes, &bytes)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    size_t offset = 0;
    const VeaS5TensorStatus status = AllocateBytes(arena, bytes, &offset);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    *out_descriptors = reinterpret_cast<VeaS5MatrixDescriptor*>(arena->memory + offset);
    if (bytes != 0) std::memset(*out_descriptors, 0, bytes);
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_convert_f64_array_to_f32(
    VeaS5Arena* arena,
    const double* source,
    size_t count,
    float** out_values) {
    if (arena == nullptr || out_values == nullptr || (count != 0 && source == nullptr)) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_values = nullptr;

    float* values = nullptr;
    VeaS5TensorStatus status = vea_s5_arena_alloc_f32(arena, count, &values);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    for (size_t i = 0; i < count; ++i) {
        values[i] = static_cast<float>(source[i]);
    }
    *out_values = values;
    return VEA_S5_TENSOR_STATUS_OK;
}

extern "C" VeaS5TensorStatus vea_s5_pack_matrix_bank_f32(
    VeaS5Arena* arena,
    const float* source,
    uint32_t axis0_count,
    uint32_t axis1_count,
    uint32_t matrix_count,
    VeaS5MatrixDescriptor** out_descriptors) {
    if (arena == nullptr || out_descriptors == nullptr ||
        source == nullptr || axis0_count == 0 || axis1_count == 0 || matrix_count == 0) {
        return VEA_S5_TENSOR_STATUS_INVALID_ARGUMENT;
    }
    *out_descriptors = nullptr;

    const size_t checkpoint = arena->used;
    const uint64_t padded64 =
        (static_cast<uint64_t>(axis0_count) + (VEA_S5_MATRIX_AXIS0_ALIGNMENT - 1u)) &
        ~static_cast<uint64_t>(VEA_S5_MATRIX_AXIS0_ALIGNMENT - 1u);
    if (padded64 > std::numeric_limits<uint32_t>::max()) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    const uint32_t padded_axis0 = static_cast<uint32_t>(padded64);

    size_t source_elements = 0;
    size_t matrix_elements = 0;
    size_t descriptor_bytes = 0;
    size_t per_matrix_elements = 0;
    size_t ignored = 0;
    if (MultiplyOverflow(static_cast<size_t>(axis0_count), axis1_count, &matrix_elements) ||
        MultiplyOverflow(matrix_elements, matrix_count, &source_elements) ||
        MultiplyOverflow(static_cast<size_t>(padded_axis0), axis1_count, &per_matrix_elements) ||
        MultiplyOverflow(static_cast<size_t>(matrix_count), kDescriptorBytes, &descriptor_bytes) ||
        MultiplyOverflow(per_matrix_elements, sizeof(float), &ignored)) {
        return VEA_S5_TENSOR_STATUS_SIZE_OVERFLOW;
    }
    (void)source_elements;
    (void)descriptor_bytes;
    (void)ignored;

    VeaS5MatrixDescriptor* descriptors = nullptr;
    VeaS5TensorStatus status =
        vea_s5_arena_alloc_matrix_descriptors(arena, matrix_count, &descriptors);
    if (status != VEA_S5_TENSOR_STATUS_OK) return status;

    for (uint32_t matrix = 0; matrix < matrix_count; ++matrix) {
        float* values = nullptr;
        status = vea_s5_arena_alloc_f32(arena, per_matrix_elements, &values);
        if (status != VEA_S5_TENSOR_STATUS_OK) {
            arena->used = checkpoint;
            *out_descriptors = nullptr;
            return status;
        }

        descriptors[matrix].axis0 = axis0_count;
        descriptors[matrix].axis1 = axis1_count;
        descriptors[matrix].padded_axis0 = padded_axis0;
        descriptors[matrix].reserved = 0;
        descriptors[matrix].values = values;
    }

    // Source order recovered from FUN_180064670: axis0, axis1, matrix.
    for (uint32_t axis0 = 0; axis0 < axis0_count; ++axis0) {
        for (uint32_t axis1 = 0; axis1 < axis1_count; ++axis1) {
            const size_t source_base =
                (static_cast<size_t>(axis0) * axis1_count + axis1) * matrix_count;
            for (uint32_t matrix = 0; matrix < matrix_count; ++matrix) {
                const size_t target =
                    static_cast<size_t>(axis1) * padded_axis0 + axis0;
                descriptors[matrix].values[target] = source[source_base + matrix];
            }
        }
    }

    *out_descriptors = descriptors;
    return VEA_S5_TENSOR_STATUS_OK;
}
