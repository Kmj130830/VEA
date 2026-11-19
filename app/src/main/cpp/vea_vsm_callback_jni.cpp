#include "vea_vsm_callback.h"

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeAbiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(vea_vsm_callback_abi_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeVsmCallbackBridge_nativeDefaultSampleRate(JNIEnv*, jobject) {
    return static_cast<jint>(vea_vsm_default_sample_rate());
}
