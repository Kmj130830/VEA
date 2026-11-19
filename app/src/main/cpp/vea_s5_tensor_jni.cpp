#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

namespace {

bool MultiplyOverflow(size_t a, size_t b, size_t* out) {
    if (out == nullptr || (a != 0 && b > std::numeric_limits<size_t>::max() / a)) return true;
    *out = a * b;
    return false;
}

bool Align32(size_t value, size_t* out) {
    if (out == nullptr || value > std::numeric_limits<size_t>::max() - 31u) return false;
    *out = (value + 31u) & ~static_cast<size_t>(31u);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeAbiVersion(
    JNIEnv*, jobject) {
    return static_cast<jint>(vea_s5_tensor_abi_version());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativePackMatrixBank(
    JNIEnv* env,
    jobject,
    jfloatArray source_array,
    jint axis0_count,
    jint axis1_count,
    jint matrix_count) {
    if (source_array == nullptr || axis0_count <= 0 || axis1_count <= 0 || matrix_count <= 0) {
        return nullptr;
    }

    size_t expected_source = 0;
    size_t axis_elements = 0;
    if (MultiplyOverflow(static_cast<size_t>(axis0_count),
                         static_cast<size_t>(axis1_count), &axis_elements) ||
        MultiplyOverflow(axis_elements, static_cast<size_t>(matrix_count), &expected_source)) {
        return nullptr;
    }
    const jsize input_length = env->GetArrayLength(source_array);
    if (input_length < 0 || static_cast<size_t>(input_length) != expected_source) {
        return nullptr;
    }

    const uint64_t padded64 =
        (static_cast<uint64_t>(axis0_count) + 7u) & ~static_cast<uint64_t>(7u);
    if (padded64 > static_cast<uint64_t>(std::numeric_limits<uint32_t>::max())) {
        return nullptr;
    }

    size_t per_matrix_elements = 0;
    size_t per_matrix_bytes = 0;
    size_t descriptor_bytes = 0;
    size_t descriptor_reserved = 0;
    size_t matrix_reserved = 0;
    size_t matrix_total_reserved = 0;
    size_t capacity = 0;
    if (MultiplyOverflow(static_cast<size_t>(padded64),
                         static_cast<size_t>(axis1_count), &per_matrix_elements) ||
        MultiplyOverflow(per_matrix_elements, sizeof(float), &per_matrix_bytes) ||
        MultiplyOverflow(static_cast<size_t>(matrix_count), VEA_S5_MATRIX_DESCRIPTOR_SIZE,
                         &descriptor_bytes) ||
        !Align32(descriptor_bytes, &descriptor_reserved) ||
        !Align32(per_matrix_bytes, &matrix_reserved) ||
        MultiplyOverflow(matrix_reserved, static_cast<size_t>(matrix_count),
                         &matrix_total_reserved) ||
        descriptor_reserved > std::numeric_limits<size_t>::max() - matrix_total_reserved) {
        return nullptr;
    }
    capacity = descriptor_reserved + matrix_total_reserved;

    try {
        std::vector<jfloat> input(expected_source);
        env->GetFloatArrayRegion(source_array, 0, input_length, input.data());
        if (env->ExceptionCheck()) return nullptr;

        VeaS5Arena* arena = nullptr;
        if (vea_s5_arena_create(capacity, &arena) != VEA_S5_TENSOR_STATUS_OK) {
            return nullptr;
        }

        VeaS5MatrixDescriptor* descriptors = nullptr;
        const VeaS5TensorStatus status = vea_s5_pack_matrix_bank_f32(
            arena,
            input.data(),
            static_cast<uint32_t>(axis0_count),
            static_cast<uint32_t>(axis1_count),
            static_cast<uint32_t>(matrix_count),
            &descriptors);

        if (status != VEA_S5_TENSOR_STATUS_OK || descriptors == nullptr) {
            vea_s5_arena_destroy(arena);
            return nullptr;
        }

        size_t output_count = 0;
        if (MultiplyOverflow(per_matrix_elements, static_cast<size_t>(matrix_count),
                             &output_count) ||
            output_count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            vea_s5_arena_destroy(arena);
            return nullptr;
        }

        std::vector<jfloat> output(output_count);
        for (jint matrix = 0; matrix < matrix_count; ++matrix) {
            const size_t offset = static_cast<size_t>(matrix) * per_matrix_elements;
            for (size_t i = 0; i < per_matrix_elements; ++i) {
                output[offset + i] = descriptors[matrix].values[i];
            }
        }

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output_count));
        if (result != nullptr && output_count != 0) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output_count), output.data());
        }
        vea_s5_arena_destroy(arena);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeParseContainerHeader(
    JNIEnv* env,
    jobject,
    jbyteArray header_array,
    jlong total_file_size) {
    if (header_array == nullptr || total_file_size < static_cast<jlong>(VEA_S5_CONTAINER_HEADER_SIZE)) {
        return nullptr;
    }
    if (env->GetArrayLength(header_array) != static_cast<jsize>(VEA_S5_CONTAINER_HEADER_SIZE)) {
        return nullptr;
    }

    uint8_t header_bytes[VEA_S5_CONTAINER_HEADER_SIZE] = {};
    env->GetByteArrayRegion(
        header_array, 0, static_cast<jsize>(VEA_S5_CONTAINER_HEADER_SIZE),
        reinterpret_cast<jbyte*>(header_bytes));
    if (env->ExceptionCheck()) return nullptr;

    VeaS5ContainerHeader parsed{};
    const VeaS5ContainerStatus status = vea_s5_parse_container_header(
        header_bytes, static_cast<uint64_t>(total_file_size), &parsed);
    if (status != VEA_S5_CONTAINER_STATUS_OK) return nullptr;

    const jlong values[3] = {
        static_cast<jlong>(parsed.kind),
        static_cast<jlong>(parsed.payload_size),
        static_cast<jlong>(parsed.format_word)
    };
    jlongArray result = env->NewLongArray(3);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, values);
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativePackRowMajor(
    JNIEnv* env,
    jobject,
    jfloatArray source_array,
    jint axis0_count,
    jint axis1_count) {
    if (source_array == nullptr || axis0_count <= 0 || axis1_count <= 0) return nullptr;

    size_t source_count = 0;
    size_t output_count = 0;
    size_t descriptor_reserved = 0;
    size_t values_bytes = 0;
    size_t values_reserved = 0;
    if (MultiplyOverflow(static_cast<size_t>(axis0_count), static_cast<size_t>(axis1_count),
                         &source_count)) return nullptr;
    const jsize input_length = env->GetArrayLength(source_array);
    if (input_length < 0 || static_cast<size_t>(input_length) != source_count) return nullptr;

    const uint64_t padded64 = (static_cast<uint64_t>(axis0_count) + 7u) & ~static_cast<uint64_t>(7u);
    if (padded64 > std::numeric_limits<uint32_t>::max() ||
        MultiplyOverflow(static_cast<size_t>(padded64), static_cast<size_t>(axis1_count),
                         &output_count) ||
        MultiplyOverflow(output_count, sizeof(float), &values_bytes) ||
        !Align32(VEA_S5_MATRIX_DESCRIPTOR_SIZE, &descriptor_reserved) ||
        !Align32(values_bytes, &values_reserved) ||
        descriptor_reserved > std::numeric_limits<size_t>::max() - values_reserved) {
        return nullptr;
    }
    const size_t capacity = descriptor_reserved + values_reserved;

    try {
        std::vector<jfloat> input(source_count);
        env->GetFloatArrayRegion(source_array, 0, input_length, input.data());
        if (env->ExceptionCheck()) return nullptr;
        VeaS5Arena* arena = nullptr;
        if (vea_s5_arena_create(capacity, &arena) != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        VeaS5MatrixDescriptor* descriptor = nullptr;
        const VeaS5TensorStatus status = vea_s5_pack_row_major_f32(
            arena, input.data(), static_cast<uint32_t>(axis0_count),
            static_cast<uint32_t>(axis1_count), &descriptor);
        if (status != VEA_S5_TENSOR_STATUS_OK || descriptor == nullptr ||
            output_count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            vea_s5_arena_destroy(arena);
            return nullptr;
        }

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output_count));
        if (result != nullptr && output_count != 0) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output_count), descriptor->values);
        }
        vea_s5_arena_destroy(arena);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}
