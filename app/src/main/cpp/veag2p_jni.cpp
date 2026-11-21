#include "veag2p.h"

#include <jni.h>

#include <cstdint>
#include <vector>

namespace {

void ThrowIllegalArgument(JNIEnv* env, const char* message) {
    jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

void AppendUtf8(char32_t value, std::vector<char>* output) {
    if (value <= 0x7F) {
        output->push_back(static_cast<char>(value));
    } else if (value <= 0x7FF) {
        output->push_back(static_cast<char>(0xC0U | (value >> 6U)));
        output->push_back(static_cast<char>(0x80U | (value & 0x3FU)));
    } else if (value <= 0xFFFF) {
        output->push_back(static_cast<char>(0xE0U | (value >> 12U)));
        output->push_back(static_cast<char>(0x80U | ((value >> 6U) & 0x3FU)));
        output->push_back(static_cast<char>(0x80U | (value & 0x3FU)));
    } else {
        output->push_back(static_cast<char>(0xF0U | (value >> 18U)));
        output->push_back(static_cast<char>(0x80U | ((value >> 12U) & 0x3FU)));
        output->push_back(static_cast<char>(0x80U | ((value >> 6U) & 0x3FU)));
        output->push_back(static_cast<char>(0x80U | (value & 0x3FU)));
    }
}

bool Utf16ToUtf8(JNIEnv* env, jstring input, std::vector<char>* output) {
    if (input == nullptr || output == nullptr) return false;

    const jsize length = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (chars == nullptr) return false;

    output->clear();
    output->reserve(static_cast<size_t>(length) * 3U);

    for (jsize index = 0; index < length; ++index) {
        char32_t value = chars[index];
        if (value >= 0xD800 && value <= 0xDBFF) {
            if (index + 1 >= length) {
                env->ReleaseStringChars(input, chars);
                return false;
            }
            const char32_t low = chars[index + 1];
            if (low < 0xDC00 || low > 0xDFFF) {
                env->ReleaseStringChars(input, chars);
                return false;
            }
            value = 0x10000 + ((value - 0xD800) << 10U) + (low - 0xDC00);
            ++index;
        } else if (value >= 0xDC00 && value <= 0xDFFF) {
            env->ReleaseStringChars(input, chars);
            return false;
        }
        AppendUtf8(value, output);
    }

    env->ReleaseStringChars(input, chars);
    return true;
}

bool Utf8ToUtf16(JNIEnv* env, const char* input, size_t size, std::vector<jchar>* output) {
    if (output == nullptr || (input == nullptr && size != 0)) return false;
    output->clear();

    size_t index = 0;
    while (index < size) {
        const auto first = static_cast<uint8_t>(input[index]);
        char32_t value = 0;
        size_t count = 0;
        char32_t minimum = 0;

        if (first <= 0x7F) {
            value = first;
            count = 1;
            minimum = 0;
        } else if ((first & 0xE0U) == 0xC0U) {
            value = first & 0x1FU;
            count = 2;
            minimum = 0x80;
        } else if ((first & 0xF0U) == 0xE0U) {
            value = first & 0x0FU;
            count = 3;
            minimum = 0x800;
        } else if ((first & 0xF8U) == 0xF0U) {
            value = first & 0x07U;
            count = 4;
            minimum = 0x10000;
        } else {
            return false;
        }
        if (count > size - index) return false;

        for (size_t offset = 1; offset < count; ++offset) {
            const auto continuation = static_cast<uint8_t>(input[index + offset]);
            if ((continuation & 0xC0U) != 0x80U) return false;
            value = (value << 6U) | (continuation & 0x3FU);
        }
        if (value < minimum || value > 0x10FFFF ||
            (value >= 0xD800 && value <= 0xDFFF)) return false;

        if (value <= 0xFFFF) {
            output->push_back(static_cast<jchar>(value));
        } else {
            value -= 0x10000;
            output->push_back(static_cast<jchar>(0xD800U + (value >> 10U)));
            output->push_back(static_cast<jchar>(0xDC00U + (value & 0x3FFU)));
        }
        index += count;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeG2pBridge_nativeAbiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(vea_g2p_bridge_abi_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kmj_vea_engine_NativeG2pBridge_nativeSupportedLanguageMask(JNIEnv*, jobject) {
    return static_cast<jint>(vea_g2p_supported_language_mask());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kmj_vea_engine_NativeG2pBridge_nativeLanguageReady(JNIEnv*, jobject, jint language_id) {
    (void)language_id;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_kmj_vea_engine_NativeG2pBridge_nativeKoreanBaseComponents(
    JNIEnv* env,
    jobject,
    jint code_point) {
    VeaKoreanPhonemeComponents components{};
    const VeaG2pStatus status = vea_g2p_korean_syllable_components(
        static_cast<uint32_t>(code_point), &components);
    if (status != VEA_G2P_STATUS_OK) {
        return nullptr;
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(3, string_class, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    const char* labels[] = {
        components.onset,
        components.vowel,
        components.coda,
    };
    for (jsize index = 0; index < 3; ++index) {
        jstring label = env->NewStringUTF(labels[index]);
        if (label == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, label);
        env->DeleteLocalRef(label);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kmj_vea_engine_NativeG2pBridge_nativeNormalizeKoreanInput(JNIEnv* env, jobject, jstring input) {
    std::vector<char> utf8;
    if (!Utf16ToUtf8(env, input, &utf8)) {
        ThrowIllegalArgument(env, "Input contains an invalid UTF-16 sequence.");
        return nullptr;
    }

    size_t required = 0;
    VeaG2pStatus status = vea_g2p_normalize_korean_input_utf8(
        utf8.data(), utf8.size(), nullptr, &required);
    if (status != VEA_G2P_STATUS_BUFFER_TOO_SMALL && status != VEA_G2P_STATUS_OK) {
        ThrowIllegalArgument(env, "Input could not be normalized.");
        return nullptr;
    }

    std::vector<char> normalized(required);
    size_t capacity = normalized.size();
    status = vea_g2p_normalize_korean_input_utf8(
        utf8.data(), utf8.size(), normalized.data(), &capacity);
    if (status != VEA_G2P_STATUS_OK) {
        ThrowIllegalArgument(env, "Native Korean input normalization failed.");
        return nullptr;
    }

    std::vector<jchar> utf16;
    if (!Utf8ToUtf16(env, normalized.data(), capacity, &utf16)) {
        ThrowIllegalArgument(env, "Native output was not valid UTF-8.");
        return nullptr;
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}
