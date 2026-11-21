package com.kmj.vea.engine

/**
 * Thin JNI access to VEA's Android-native G2P bridge.
 *
 * The current .so contains a recovered Korean input-normalization primitive and
 * the stable C/JNI boundary. It does not yet contain Yamaha's phoneme converter.
 * Keep this distinction visible: native library loaded != language G2P ready.
 */
object NativeG2pBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("veag2p")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    val isLoaded: Boolean
        get() = loaded

    val abiVersion: Int
        get() = if (loaded) nativeAbiVersion() else 0

    val supportedLanguageMask: Int
        get() = if (loaded) nativeSupportedLanguageMask() else 0

    fun isLanguageReady(languageId: Int): Boolean =
        loaded && nativeLanguageReady(languageId)

    fun normalizeKoreanInput(lyric: String): String =
        if (loaded) nativeNormalizeKoreanInput(lyric) else normalizeKoreanInputFallback(lyric)

    /**
     * Keeps the recovered code-point behavior available to JVM tooling and
     * devices where the optional ARM64 bridge cannot be loaded.
     */
    private fun normalizeKoreanInputFallback(lyric: String): String = buildString(lyric.length) {
        lyric.forEach { character ->
            append(
                when (character) {
                    '\u30FC', '\u2212' -> '-'
                    else -> character
                }
            )
        }
    }

    private external fun nativeAbiVersion(): Int
    private external fun nativeSupportedLanguageMask(): Int
    private external fun nativeLanguageReady(languageId: Int): Boolean
    private external fun nativeNormalizeKoreanInput(lyric: String): String
}
