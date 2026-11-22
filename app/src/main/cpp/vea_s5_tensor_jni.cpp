#include "vea_s5_tensor.h"
#include "vea_s5_container.h"

#include <jni.h>

#include <cmath>

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


extern "C" JNIEXPORT jlong JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeCreateHistoryRing(
    JNIEnv*, jobject, jint channels, jint positions, jint history_rows) {
    if (channels <= 0 || positions <= 0 || history_rows <= 0) return 0;
    VeaS5HistoryRing* ring = nullptr;
    const VeaS5TensorStatus status = vea_s5_history_ring_create(
        static_cast<size_t>(channels), static_cast<size_t>(positions),
        static_cast<size_t>(history_rows), &ring);
    if (status != VEA_S5_TENSOR_STATUS_OK || ring == nullptr) return 0;
    return reinterpret_cast<jlong>(ring);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeDestroyHistoryRing(
    JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    vea_s5_history_ring_destroy(reinterpret_cast<VeaS5HistoryRing*>(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeResetHistoryRing(
    JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    const VeaS5TensorStatus status = vea_s5_history_ring_reset(
        reinterpret_cast<VeaS5HistoryRing*>(handle));
    return status == VEA_S5_TENSOR_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeAppendHistoryRing(
    JNIEnv* env, jobject, jlong handle, jfloatArray block_array) {
    if (handle == 0 || block_array == nullptr) return JNI_FALSE;
    const jsize count = env->GetArrayLength(block_array);
    if (count <= 0) return JNI_FALSE;
    auto* block = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(block_array, nullptr));
    if (block == nullptr) return JNI_FALSE;

    // The native append only copies the samples; do not allocate another
    // temporary vector or make any JNI calls while the array is pinned.
    const VeaS5TensorStatus status = vea_s5_history_ring_append(
        reinterpret_cast<VeaS5HistoryRing*>(handle),
        block, static_cast<size_t>(count));
    env->ReleasePrimitiveArrayCritical(block_array, block, JNI_ABORT);
    return status == VEA_S5_TENSOR_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeCopyChronologicalHistoryRing(
    JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return nullptr;
    const auto* ring = reinterpret_cast<const VeaS5HistoryRing*>(handle);
    const size_t block_size = vea_s5_history_ring_block_size(ring);
    const size_t row_count = vea_s5_history_ring_row_count(ring);
    if (block_size == 0u || row_count == 0u ||
        block_size > std::numeric_limits<size_t>::max() / row_count) return nullptr;
    const size_t count = block_size * row_count;
    if (count > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    try {
        std::vector<float> output(count);
        const VeaS5TensorStatus status = vea_s5_history_ring_copy_chronological(
            ring, output.data(), output.size());
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(count));
        if (result != nullptr && count != 0u) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), output.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeVtmgBankToV0NaiveA(
    JNIEnv* env, jobject, jfloatArray packed_array,
    jint output_features, jint input_features, jint padded_output_features, jint banks) {
    if (packed_array == nullptr || output_features <= 0 || input_features <= 0 ||
        padded_output_features <= 0 || banks <= 0) return nullptr;

    size_t source_bank_stride = 0u;
    size_t source_count = 0u;
    size_t destination_bank_stride = 0u;
    size_t destination_count = 0u;
    if (MultiplyOverflow(static_cast<size_t>(input_features),
            static_cast<size_t>(padded_output_features), &source_bank_stride) ||
        MultiplyOverflow(source_bank_stride, static_cast<size_t>(banks), &source_count) ||
        MultiplyOverflow(static_cast<size_t>(output_features),
            static_cast<size_t>(input_features), &destination_bank_stride) ||
        MultiplyOverflow(destination_bank_stride, static_cast<size_t>(banks), &destination_count) ||
        source_count > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        destination_count > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        env->GetArrayLength(packed_array) != static_cast<jsize>(source_count)) {
        return nullptr;
    }

    try {
        std::vector<float> output(destination_count);
        auto* source = static_cast<jfloat*>(
            env->GetPrimitiveArrayCritical(packed_array, nullptr));
        if (source == nullptr) return nullptr;

        const VeaS5TensorStatus status = vea_s5_vtmg_bank_to_v0_naive_a_f32(
            source, source_count,
            static_cast<size_t>(output_features), static_cast<size_t>(input_features),
            static_cast<size_t>(padded_output_features), static_cast<size_t>(banks),
            output.data(), output.size());
        env->ReleasePrimitiveArrayCritical(packed_array, source, JNI_ABORT);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(destination_count));
        if (result == nullptr) return nullptr;
        env->SetFloatArrayRegion(
            result, 0, static_cast<jsize>(destination_count), output.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeBuildRecurrentParameterName(
    JNIEnv* env, jobject, jstring base_string, jstring suffix_string,
    jint layer_index, jboolean reverse_direction) {
    if (base_string == nullptr || suffix_string == nullptr) return nullptr;
    const char* base = env->GetStringUTFChars(base_string, nullptr);
    if (base == nullptr) return nullptr;
    const char* suffix = env->GetStringUTFChars(suffix_string, nullptr);
    if (suffix == nullptr) {
        env->ReleaseStringUTFChars(base_string, base);
        return nullptr;
    }
    const jsize base_length = env->GetStringUTFLength(base_string);
    const jsize suffix_length = env->GetStringUTFLength(suffix_string);
    if (base_length < 0 || suffix_length < 0 ||
        static_cast<size_t>(base_length) + static_cast<size_t>(suffix_length) > 4096u) {
        env->ReleaseStringUTFChars(suffix_string, suffix);
        env->ReleaseStringUTFChars(base_string, base);
        return nullptr;
    }
    try {
        const size_t capacity = static_cast<size_t>(base_length) +
            static_cast<size_t>(suffix_length) + 32u;
        std::vector<char> output(capacity, '\0');
        size_t required = 0;
        const VeaS5TensorStatus status = vea_s5_build_recurrent_parameter_name(
            base, suffix, static_cast<int32_t>(layer_index),
            reverse_direction == JNI_TRUE ? 1 : 0,
            output.data(), output.size(), &required);
        env->ReleaseStringUTFChars(suffix_string, suffix);
        env->ReleaseStringUTFChars(base_string, base);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        return env->NewStringUTF(output.data());
    } catch (...) {
        env->ReleaseStringUTFChars(suffix_string, suffix);
        env->ReleaseStringUTFChars(base_string, base);
        return nullptr;
    }
}

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
    if (header_array == nullptr || total_file_size < static_cast<jlong>(VEA_S5_CONTAINER_PREFIX_SIZE)) {
        return nullptr;
    }
    if (env->GetArrayLength(header_array) != static_cast<jsize>(VEA_S5_CONTAINER_PREFIX_SIZE)) {
        return nullptr;
    }

    uint8_t header_bytes[VEA_S5_CONTAINER_PREFIX_SIZE] = {};
    env->GetByteArrayRegion(
        header_array, 0, static_cast<jsize>(VEA_S5_CONTAINER_PREFIX_SIZE),
        reinterpret_cast<jbyte*>(header_bytes));
    if (env->ExceptionCheck()) return nullptr;

    VeaS5ContainerHeader parsed{};
    const VeaS5ContainerStatus status = vea_s5_parse_container_header(
        header_bytes, static_cast<uint64_t>(total_file_size), &parsed);
    if (status != VEA_S5_CONTAINER_STATUS_OK) return nullptr;

    const jlong values[5] = {
        static_cast<jlong>(parsed.kind),
        static_cast<jlong>(parsed.declared_span),
        static_cast<jlong>(parsed.format_word),
        static_cast<jlong>(parsed.transform_seed),
        static_cast<jlong>(parsed.decoded_payload_size)
    };
    jlongArray result = env->NewLongArray(5);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 5, values);
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeDecodeContainerPayload(
    JNIEnv* env,
    jobject,
    jbyteArray file_array) {
    if (file_array == nullptr) return nullptr;
    const jsize input_length = env->GetArrayLength(file_array);
    if (input_length < static_cast<jsize>(VEA_S5_CONTAINER_PREFIX_SIZE)) return nullptr;

    try {
        std::vector<uint8_t> input(static_cast<size_t>(input_length));
        env->GetByteArrayRegion(
            file_array, 0, input_length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;

        VeaS5ContainerHeader parsed{};
        const VeaS5ContainerStatus parse_status = vea_s5_parse_container_header(
            input.data(), input.size(), &parsed);
        if (parse_status != VEA_S5_CONTAINER_STATUS_OK ||
            parsed.decoded_payload_size > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
            return nullptr;
        }

        std::vector<uint8_t> output(static_cast<size_t>(parsed.decoded_payload_size));
        uint64_t written = 0;
        const VeaS5ContainerStatus status = vea_s5_decode_container_payload(
            input.data(), input.size(),
            output.empty() ? nullptr : output.data(),
            output.size(), &written);
        if (status != VEA_S5_CONTAINER_STATUS_OK ||
            written != parsed.decoded_payload_size) {
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
        if (result != nullptr && written != 0) {
            env->SetByteArrayRegion(
                result, 0, static_cast<jsize>(written),
                reinterpret_cast<const jbyte*>(output.data()));
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
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


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeDecodeTypedFloatArray(
    JNIEnv* env, jobject, jbyteArray encoded_array) {
    if (encoded_array == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(encoded_array);
    if (length < 4) return nullptr;

    try {
        std::vector<char> encoded(static_cast<size_t>(length));
        env->GetByteArrayRegion(
            encoded_array, 0, length, reinterpret_cast<jbyte*>(encoded.data()));
        if (env->ExceptionCheck()) return nullptr;

        size_t capacity = 0;
        if (static_cast<size_t>(length) > (std::numeric_limits<size_t>::max() - 64u) / 4u) {
            return nullptr;
        }
        capacity = static_cast<size_t>(length) * 4u + 64u;
        VeaS5Arena* arena = nullptr;
        if (vea_s5_arena_create(capacity, &arena) != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        float* values = nullptr;
        size_t count = 0;
        const VeaS5TensorStatus status = vea_s5_decode_typed_float_array(
            arena, encoded.data(), encoded.size(), &values, &count);
        if (status != VEA_S5_TENSOR_STATUS_OK ||
            count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            vea_s5_arena_destroy(arena);
            return nullptr;
        }

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(count));
        if (result != nullptr && count != 0) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), values);
        }
        vea_s5_arena_destroy(arena);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeDecodeTypedInt32Array(
    JNIEnv* env, jobject, jbyteArray encoded_array) {
    if (encoded_array == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(encoded_array);
    if (length < 4) return nullptr;

    try {
        std::vector<char> encoded(static_cast<size_t>(length));
        env->GetByteArrayRegion(
            encoded_array, 0, length, reinterpret_cast<jbyte*>(encoded.data()));
        if (env->ExceptionCheck()) return nullptr;

        if (static_cast<size_t>(length) > (std::numeric_limits<size_t>::max() - 64u) / 4u) {
            return nullptr;
        }
        const size_t capacity = static_cast<size_t>(length) * 4u + 64u;
        VeaS5Arena* arena = nullptr;
        if (vea_s5_arena_create(capacity, &arena) != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        int32_t* values = nullptr;
        size_t count = 0;
        const VeaS5TensorStatus status = vea_s5_decode_typed_int32_array(
            arena, encoded.data(), encoded.size(), &values, &count);
        if (status != VEA_S5_TENSOR_STATUS_OK ||
            count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            vea_s5_arena_destroy(arena);
            return nullptr;
        }

        jintArray result = env->NewIntArray(static_cast<jsize>(count));
        if (result != nullptr && count != 0) {
            env->SetIntArrayRegion(result, 0, static_cast<jsize>(count),
                                   reinterpret_cast<const jint*>(values));
        }
        vea_s5_arena_destroy(arena);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativePackTransposed(
    JNIEnv* env, jobject, jfloatArray source_array, jint axis0_count, jint axis1_count) {
    if (source_array == nullptr || axis0_count <= 0 || axis1_count <= 0) return nullptr;
    size_t source_count = 0, output_count = 0, output_bytes = 0;
    size_t descriptor_reserved = 0, values_reserved = 0;
    if (MultiplyOverflow(static_cast<size_t>(axis0_count), static_cast<size_t>(axis1_count),
                         &source_count)) return nullptr;
    const jsize input_length = env->GetArrayLength(source_array);
    if (input_length < 0 || static_cast<size_t>(input_length) != source_count) return nullptr;
    const uint64_t padded64 = (static_cast<uint64_t>(axis0_count) + 7u) & ~static_cast<uint64_t>(7u);
    if (padded64 > std::numeric_limits<uint32_t>::max() ||
        MultiplyOverflow(static_cast<size_t>(padded64), static_cast<size_t>(axis1_count),
                         &output_count) ||
        MultiplyOverflow(output_count, sizeof(float), &output_bytes) ||
        !Align32(VEA_S5_MATRIX_DESCRIPTOR_SIZE, &descriptor_reserved) ||
        !Align32(output_bytes, &values_reserved) ||
        descriptor_reserved > std::numeric_limits<size_t>::max() - values_reserved) return nullptr;
    const size_t capacity = descriptor_reserved + values_reserved;
    try {
        std::vector<jfloat> input(source_count);
        env->GetFloatArrayRegion(source_array, 0, input_length, input.data());
        if (env->ExceptionCheck()) return nullptr;
        VeaS5Arena* arena = nullptr;
        if (vea_s5_arena_create(capacity, &arena) != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        VeaS5MatrixDescriptor* descriptor = nullptr;
        const VeaS5TensorStatus status = vea_s5_pack_transposed_f32(
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeApplyGatedActivation(
    JNIEnv* env, jobject, jfloatArray planes_array, jint count, jint mode) {
    if (planes_array == nullptr || count <= 0 ||
        count > std::numeric_limits<jsize>::max() / 2) return nullptr;
    const jsize length = env->GetArrayLength(planes_array);
    if (length != count * 2) return nullptr;
    try {
        std::vector<jfloat> planes(static_cast<size_t>(length));
        env->GetFloatArrayRegion(planes_array, 0, length, planes.data());
        if (env->ExceptionCheck()) return nullptr;
        const VeaS5TensorStatus status =
            vea_s5_apply_gated_activation_f32(planes.data(), static_cast<size_t>(count), mode);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(length);
        if (result != nullptr) {
            env->SetFloatArrayRegion(result, 0, length, planes.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeDepthwiseConv7x7Reflect(
    JNIEnv* env, jobject, jfloatArray input_rows_array, jfloatArray weights_array, jint width) {
    if (input_rows_array == nullptr || weights_array == nullptr || width < 7) return nullptr;
    size_t input_count = 0;
    if (MultiplyOverflow(static_cast<size_t>(width), 7u, &input_count) ||
        input_count > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        env->GetArrayLength(input_rows_array) != static_cast<jsize>(input_count) ||
        env->GetArrayLength(weights_array) != 49) return nullptr;
    try {
        std::vector<jfloat> input(input_count);
        std::vector<jfloat> weights(49u);
        std::vector<jfloat> output(static_cast<size_t>(width));
        env->GetFloatArrayRegion(input_rows_array, 0, static_cast<jsize>(input_count), input.data());
        if (env->ExceptionCheck()) return nullptr;
        env->GetFloatArrayRegion(weights_array, 0, 49, weights.data());
        if (env->ExceptionCheck()) return nullptr;
        const float* rows[7] = {};
        for (size_t row = 0; row < 7u; ++row) rows[row] = input.data() + row * static_cast<size_t>(width);
        const VeaS5TensorStatus status = vea_s5_depthwise_conv7x7_reflect_f32(
            rows, weights.data(), static_cast<uint32_t>(width), output.data());
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(width);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, width, output.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) { return nullptr; }
      catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeResidualAffineAdd(
    JNIEnv* env, jobject, jfloatArray dst_array, jfloatArray residual_array,
    jfloat scale, jfloat bias) {
    if (dst_array == nullptr || residual_array == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(dst_array);
    if (count <= 0 || env->GetArrayLength(residual_array) != count) return nullptr;
    try {
        std::vector<jfloat> dst(static_cast<size_t>(count));
        std::vector<jfloat> residual(static_cast<size_t>(count));
        env->GetFloatArrayRegion(dst_array, 0, count, dst.data());
        if (env->ExceptionCheck()) return nullptr;
        env->GetFloatArrayRegion(residual_array, 0, count, residual.data());
        if (env->ExceptionCheck()) return nullptr;
        const VeaS5TensorStatus status = vea_s5_residual_affine_add_f32(
            dst.data(), residual.data(), static_cast<size_t>(count), scale, bias);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(count);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, count, dst.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeApplyGelu(
    JNIEnv* env, jobject, jfloatArray input_array, jfloat scale, jfloat bias) {
    if (input_array == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(input_array);
    if (count <= 0) return nullptr;
    try {
        std::vector<jfloat> values(static_cast<size_t>(count));
        env->GetFloatArrayRegion(input_array, 0, count, values.data());
        if (env->ExceptionCheck()) return nullptr;
        const VeaS5TensorStatus status = vea_s5_apply_gelu_f32(
            values.data(), static_cast<size_t>(count), scale, bias);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(count);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, count, values.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeReduceSumAndSquares(
    JNIEnv* env, jobject, jfloatArray input_array) {
    if (input_array == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(input_array);
    if (length <= 0) return nullptr;
    try {
        std::vector<jfloat> input(static_cast<size_t>(length));
        env->GetFloatArrayRegion(input_array, 0, length, input.data());
        if (env->ExceptionCheck()) return nullptr;
        float sum = 0.0f;
        float sum_squares = 0.0f;
        const VeaS5TensorStatus status = vea_s5_reduce_sum_sumsq_f32(
            input.data(), input.size(), &sum, &sum_squares);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        const jfloat values[2] = {sum, sum_squares};
        jfloatArray result = env->NewFloatArray(2);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, 2, values);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeComputeMeanInvStd(
    JNIEnv* env, jobject, jfloat sum, jfloat sum_squares, jint count, jfloat epsilon) {
    if (count <= 0) return nullptr;
    float mean = 0.0f;
    float inverse_std = 0.0f;
    const VeaS5TensorStatus status = vea_s5_compute_mean_invstd_f32(
        sum, sum_squares, static_cast<size_t>(count), epsilon, &mean, &inverse_std);
    if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
    const jfloat output[2] = {mean, inverse_std};
    jfloatArray result = env->NewFloatArray(2);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 2, output);
    return env->ExceptionCheck() ? nullptr : result;
}



extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeGemmRowMajor(
    JNIEnv* env, jobject, jfloatArray a_array, jfloatArray b_array, jfloatArray c_array,
    jint m, jint n, jint k, jfloat alpha, jint lda, jint ldb, jfloat beta, jint ldc) {
    if (a_array == nullptr || b_array == nullptr || c_array == nullptr ||
        m <= 0 || n <= 0 || k < 0 || lda < k || ldb < n || ldc < n) return nullptr;

    size_t a_required = 0, b_required = 0, c_required = 0, row_offset = 0;
    if (k > 0) {
        if (MultiplyOverflow(static_cast<size_t>(m - 1), static_cast<size_t>(lda), &row_offset) ||
            row_offset > std::numeric_limits<size_t>::max() - static_cast<size_t>(k)) return nullptr;
        a_required = row_offset + static_cast<size_t>(k);
        if (MultiplyOverflow(static_cast<size_t>(k - 1), static_cast<size_t>(ldb), &row_offset) ||
            row_offset > std::numeric_limits<size_t>::max() - static_cast<size_t>(n)) return nullptr;
        b_required = row_offset + static_cast<size_t>(n);
    }
    if (MultiplyOverflow(static_cast<size_t>(m - 1), static_cast<size_t>(ldc), &row_offset) ||
        row_offset > std::numeric_limits<size_t>::max() - static_cast<size_t>(n)) return nullptr;
    c_required = row_offset + static_cast<size_t>(n);

    const jsize a_length = env->GetArrayLength(a_array);
    const jsize b_length = env->GetArrayLength(b_array);
    const jsize c_length = env->GetArrayLength(c_array);
    if (static_cast<size_t>(a_length) < a_required ||
        static_cast<size_t>(b_length) < b_required ||
        static_cast<size_t>(c_length) < c_required) return nullptr;

    try {
        std::vector<jfloat> a(static_cast<size_t>(a_length));
        std::vector<jfloat> b(static_cast<size_t>(b_length));
        std::vector<jfloat> c(static_cast<size_t>(c_length));
        if (a_length > 0) env->GetFloatArrayRegion(a_array, 0, a_length, a.data());
        if (env->ExceptionCheck()) return nullptr;
        if (b_length > 0) env->GetFloatArrayRegion(b_array, 0, b_length, b.data());
        if (env->ExceptionCheck()) return nullptr;
        env->GetFloatArrayRegion(c_array, 0, c_length, c.data());
        if (env->ExceptionCheck()) return nullptr;

        const VeaS5TensorStatus status = vea_s5_gemm_row_major_f32(
            a.empty() ? nullptr : a.data(), b.empty() ? nullptr : b.data(), c.data(),
            static_cast<size_t>(m), static_cast<size_t>(n), static_cast<size_t>(k),
            alpha, static_cast<size_t>(lda), static_cast<size_t>(ldb), beta,
            static_cast<size_t>(ldc));
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        jfloatArray result = env->NewFloatArray(c_length);
        if (result != nullptr && c_length > 0) {
            env->SetFloatArrayRegion(result, 0, c_length, c.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeApplyBankedLinear(
    JNIEnv* env, jobject, jfloatArray weights_array,
    jint weight_row_stride, jint weight_bank_stride,
    jfloatArray input_array, jfloatArray bias_array,
    jint input_features, jint output_features, jint positions, jint banks) {
    if (weights_array == nullptr || input_array == nullptr ||
        weight_row_stride <= 0 || weight_bank_stride <= 0 ||
        input_features <= 0 || output_features <= 0 || positions <= 0 || banks <= 0) {
        return nullptr;
    }

    size_t weight_rows = 0, weight_extent = 0, weight_offset = 0;
    size_t input_required = 0, output_required = 0;
    if (MultiplyOverflow(static_cast<size_t>(output_features - 1),
                         static_cast<size_t>(weight_row_stride), &weight_rows) ||
        weight_rows > std::numeric_limits<size_t>::max() - static_cast<size_t>(input_features)) {
        return nullptr;
    }
    weight_extent = weight_rows + static_cast<size_t>(input_features);
    if (weight_bank_stride < static_cast<jint>(weight_extent)) return nullptr;
    if (MultiplyOverflow(static_cast<size_t>(banks - 1),
                         static_cast<size_t>(weight_bank_stride), &weight_offset) ||
        weight_offset > std::numeric_limits<size_t>::max() - weight_extent) return nullptr;
    const size_t weights_required = weight_offset + weight_extent;
    if (MultiplyOverflow(static_cast<size_t>(input_features),
                         static_cast<size_t>(positions), &input_required) ||
        MultiplyOverflow(static_cast<size_t>(output_features),
                         static_cast<size_t>(positions), &output_required) ||
        MultiplyOverflow(output_required, static_cast<size_t>(banks), &output_required) ||
        output_required > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;

    const jsize weights_length = env->GetArrayLength(weights_array);
    const jsize input_length = env->GetArrayLength(input_array);
    if (static_cast<size_t>(weights_length) < weights_required ||
        static_cast<size_t>(input_length) < input_required) return nullptr;
    if (bias_array != nullptr && env->GetArrayLength(bias_array) < output_features) return nullptr;

    try {
        std::vector<jfloat> weights(static_cast<size_t>(weights_length));
        std::vector<jfloat> input(static_cast<size_t>(input_length));
        std::vector<jfloat> bias;
        std::vector<jfloat> output(output_required, 0.0f);
        env->GetFloatArrayRegion(weights_array, 0, weights_length, weights.data());
        if (env->ExceptionCheck()) return nullptr;
        env->GetFloatArrayRegion(input_array, 0, input_length, input.data());
        if (env->ExceptionCheck()) return nullptr;
        const float* bias_data = nullptr;
        if (bias_array != nullptr) {
            const jsize bias_length = env->GetArrayLength(bias_array);
            bias.resize(static_cast<size_t>(bias_length));
            env->GetFloatArrayRegion(bias_array, 0, bias_length, bias.data());
            if (env->ExceptionCheck()) return nullptr;
            bias_data = bias.data();
        }

        const VeaS5TensorStatus status = vea_s5_banked_linear_projection_f32(
            weights.data(), static_cast<size_t>(weight_row_stride),
            static_cast<size_t>(weight_bank_stride), input.data(), bias_data, output.data(),
            static_cast<size_t>(input_features), static_cast<size_t>(output_features),
            static_cast<size_t>(positions), static_cast<size_t>(banks));
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result != nullptr && !output.empty()) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeRunResidualDwMlpBlock(
    JNIEnv* env, jobject,
    jfloatArray history_array, jfloatArray dw_weights_array,
    jfloatArray expand_weights_array, jfloatArray bias_a_array, jfloatArray bias_b_array,
    jfloatArray project_weights_array, jfloatArray residual_array,
    jfloatArray residual_scale_array, jfloatArray residual_bias_array,
    jint channels, jint positions, jint expanded_features, jfloat epsilon) {
    if (history_array == nullptr || dw_weights_array == nullptr || expand_weights_array == nullptr ||
        bias_a_array == nullptr || bias_b_array == nullptr || project_weights_array == nullptr ||
        residual_array == nullptr || residual_scale_array == nullptr ||
        channels <= 0 || positions < 7 || expanded_features <= 0 ||
        !std::isfinite(epsilon) || epsilon < 0.0f) return nullptr;

    size_t history_required=0, dw_required=0, expand_required=0, project_required=0, output_required=0;
    if (MultiplyOverflow(static_cast<size_t>(channels), 7u, &history_required) ||
        MultiplyOverflow(history_required, static_cast<size_t>(positions), &history_required) ||
        MultiplyOverflow(static_cast<size_t>(channels), 49u, &dw_required) ||
        MultiplyOverflow(static_cast<size_t>(expanded_features), static_cast<size_t>(channels), &expand_required) ||
        MultiplyOverflow(static_cast<size_t>(channels), static_cast<size_t>(expanded_features), &project_required) ||
        MultiplyOverflow(static_cast<size_t>(channels), static_cast<size_t>(positions), &output_required) ||
        output_required > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;

    auto enough = [&](jfloatArray array, size_t required) -> bool {
        return array != nullptr && static_cast<size_t>(env->GetArrayLength(array)) >= required;
    };
    if (!enough(history_array, history_required) || !enough(dw_weights_array, dw_required) ||
        !enough(expand_weights_array, expand_required) || !enough(bias_a_array, static_cast<size_t>(expanded_features)) ||
        !enough(bias_b_array, static_cast<size_t>(expanded_features)) ||
        !enough(project_weights_array, project_required) || !enough(residual_array, output_required) ||
        !enough(residual_scale_array, static_cast<size_t>(channels)) ||
        (residual_bias_array != nullptr && !enough(residual_bias_array, static_cast<size_t>(channels)))) return nullptr;
    if (env->ExceptionCheck()) return nullptr;

    try {
        auto copy = [&](jfloatArray array, size_t count, std::vector<float>* dst) -> bool {
            dst->resize(count);
            if (count != 0u) env->GetFloatArrayRegion(array, 0, static_cast<jsize>(count), dst->data());
            return !env->ExceptionCheck();
        };
        std::vector<float> history, dw, expand, bias_a, bias_b, project, residual, residual_scale, residual_bias, output(output_required);
        if (!copy(history_array, history_required, &history) ||
            !copy(dw_weights_array, dw_required, &dw) ||
            !copy(expand_weights_array, expand_required, &expand) ||
            !copy(bias_a_array, static_cast<size_t>(expanded_features), &bias_a) ||
            !copy(bias_b_array, static_cast<size_t>(expanded_features), &bias_b) ||
            !copy(project_weights_array, project_required, &project) ||
            !copy(residual_array, output_required, &residual) ||
            !copy(residual_scale_array, static_cast<size_t>(channels), &residual_scale)) return nullptr;
        const float* residual_bias_ptr = nullptr;
        if (residual_bias_array != nullptr) {
            if (!copy(residual_bias_array, static_cast<size_t>(channels), &residual_bias)) return nullptr;
            residual_bias_ptr = residual_bias.data();
        }

        const VeaS5TensorStatus status = vea_s5_run_residual_dw_mlp_block_f32(
            history.data(), dw.data(), expand.data(), bias_a.data(), bias_b.data(),
            project.data(), residual.data(), residual_scale.data(), residual_bias_ptr,
            output.data(), static_cast<size_t>(channels), static_cast<size_t>(positions),
            static_cast<size_t>(expanded_features), epsilon);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result != nullptr && !output.empty()) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kmj_vea_engine_NativeS5TensorBridge_nativeStride2Conv1dProjection(
    JNIEnv* env, jobject,
    jfloatArray input_array, jfloatArray weights_array, jfloatArray bias_array,
    jint input_channels, jint output_channels, jint positions, jint kernel_width) {
    if (input_array == nullptr || weights_array == nullptr ||
        input_channels <= 0 || output_channels <= 0 || positions <= 0 ||
        kernel_width <= 0 || (kernel_width & 1) == 0) return nullptr;

    const size_t input_channels_size = static_cast<size_t>(input_channels);
    const size_t output_channels_size = static_cast<size_t>(output_channels);
    const size_t positions_size = static_cast<size_t>(positions);
    const size_t kernel_width_size = static_cast<size_t>(kernel_width);
    const size_t output_positions = kernel_width == 1
        ? positions_size / 2u + 1u
        : positions_size / 2u + positions_size % 2u;

    size_t input_required = 0u;
    size_t inner_features = 0u;
    size_t weights_required = 0u;
    size_t output_required = 0u;
    if (MultiplyOverflow(input_channels_size, positions_size, &input_required) ||
        MultiplyOverflow(input_channels_size, kernel_width_size, &inner_features) ||
        MultiplyOverflow(output_channels_size, inner_features, &weights_required) ||
        MultiplyOverflow(output_channels_size, output_positions, &output_required) ||
        input_required > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        weights_required > static_cast<size_t>(std::numeric_limits<jsize>::max()) ||
        output_required > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;

    if (static_cast<size_t>(env->GetArrayLength(input_array)) < input_required ||
        static_cast<size_t>(env->GetArrayLength(weights_array)) < weights_required ||
        (bias_array != nullptr &&
         static_cast<size_t>(env->GetArrayLength(bias_array)) < output_channels_size)) {
        return nullptr;
    }
    if (env->ExceptionCheck()) return nullptr;

    try {
        std::vector<float> input(input_required);
        std::vector<float> weights(weights_required);
        std::vector<float> bias;
        std::vector<float> output(output_required, 0.0f);
        env->GetFloatArrayRegion(input_array, 0, static_cast<jsize>(input_required), input.data());
        if (env->ExceptionCheck()) return nullptr;
        env->GetFloatArrayRegion(weights_array, 0, static_cast<jsize>(weights_required), weights.data());
        if (env->ExceptionCheck()) return nullptr;

        const float* bias_ptr = nullptr;
        if (bias_array != nullptr) {
            bias.resize(output_channels_size);
            env->GetFloatArrayRegion(
                bias_array, 0, output_channels, bias.data());
            if (env->ExceptionCheck()) return nullptr;
            bias_ptr = bias.data();
        }

        const VeaS5TensorStatus status = vea_s5_stride2_conv1d_projection_f32(
            input.data(), weights.data(), bias_ptr, output.data(),
            input_channels_size, output_channels_size, positions_size, kernel_width_size);
        if (status != VEA_S5_TENSOR_STATUS_OK) return nullptr;

        jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result != nullptr && !output.empty()) {
            env->SetFloatArrayRegion(
                result, 0, static_cast<jsize>(output.size()), output.data());
        }
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}
