#include "vea_vsm_callback.h"

#include <jni.h>

#include <cstdint>
#include <new>
#include <vector>

namespace {

VeaVsmCapture* CaptureFromHandle(jlong handle) {
    if (handle == 0) return nullptr;
    return reinterpret_cast<VeaVsmCapture*>(static_cast<uintptr_t>(handle));
}

jlong HandleFromCapture(VeaVsmCapture* capture) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(capture));
}

jint Status(VeaVsmStatus status) {
    return static_cast<jint>(status);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeAbiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(vea_vsm_callback_abi_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeDefaultSampleRate(JNIEnv*, jobject) {
    return static_cast<jint>(vea_vsm_default_sample_rate());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureCreate(
    JNIEnv*, jobject, jint sample_rate) {
    VeaVsmCapture* capture = nullptr;
    const VeaVsmStatus status = vea_vsm_capture_create(
        static_cast<uint32_t>(sample_rate), &capture);
    if (status != VEA_VSM_STATUS_OK) return 0;
    return HandleFromCapture(capture);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureAppend(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint metadata_u32,
    jdouble metadata_f64,
    jfloatArray java_plane_a,
    jfloatArray java_plane_b) {
    VeaVsmCapture* capture = CaptureFromHandle(handle);
    if (capture == nullptr || java_plane_a == nullptr || java_plane_b == nullptr) {
        return Status(VEA_VSM_STATUS_INVALID_ARGUMENT);
    }

    const jsize count_a = env->GetArrayLength(java_plane_a);
    const jsize count_b = env->GetArrayLength(java_plane_b);
    if (count_a <= 0 || count_a != count_b ||
        static_cast<uint64_t>(count_a) > VEA_VSM_MAX_BLOCK_SAMPLES) {
        return Status(VEA_VSM_STATUS_INVALID_ARGUMENT);
    }

    try {
        std::vector<float> plane_a(static_cast<size_t>(count_a));
        std::vector<float> plane_b(static_cast<size_t>(count_b));
        env->GetFloatArrayRegion(java_plane_a, 0, count_a, plane_a.data());
        if (env->ExceptionCheck()) return Status(VEA_VSM_STATUS_INVALID_ARGUMENT);
        env->GetFloatArrayRegion(java_plane_b, 0, count_b, plane_b.data());
        if (env->ExceptionCheck()) return Status(VEA_VSM_STATUS_INVALID_ARGUMENT);

        const VeaVsmRenderBlockView block{
            static_cast<uint32_t>(metadata_u32),
            static_cast<double>(metadata_f64),
            static_cast<uint64_t>(count_a),
            plane_a.data(),
            plane_b.data()
        };
        return Status(vea_vsm_capture_append(capture, &block));
    } catch (const std::bad_alloc&) {
        return Status(VEA_VSM_STATUS_OUT_OF_MEMORY);
    } catch (...) {
        return Status(VEA_VSM_STATUS_OUT_OF_MEMORY);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureSampleCount(
    JNIEnv*, jobject, jlong handle) {
    return static_cast<jlong>(
        vea_vsm_capture_sample_count(CaptureFromHandle(handle)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureBlockCount(
    JNIEnv*, jobject, jlong handle) {
    return static_cast<jlong>(
        vea_vsm_capture_block_count(CaptureFromHandle(handle)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureWriteWavF32(
    JNIEnv* env, jobject, jlong handle, jstring java_path) {
    VeaVsmCapture* capture = CaptureFromHandle(handle);
    if (capture == nullptr || java_path == nullptr) {
        return Status(VEA_VSM_STATUS_INVALID_ARGUMENT);
    }

    const char* utf8_path = env->GetStringUTFChars(java_path, nullptr);
    if (utf8_path == nullptr) return Status(VEA_VSM_STATUS_OUT_OF_MEMORY);
    const VeaVsmStatus status = vea_vsm_capture_write_wav_f32(capture, utf8_path);
    env->ReleaseStringUTFChars(java_path, utf8_path);
    return Status(status);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeCaptureDestroy(
    JNIEnv*, jobject, jlong handle) {
    vea_vsm_capture_destroy(CaptureFromHandle(handle));
}
