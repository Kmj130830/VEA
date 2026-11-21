package com.kmj.vea.engine

/**
 * Thin JNI access to VEA's Android-native G2P bridge.
 *
 * The current .so contains a recovered Korean input-normalization primitive and
 * the stable C/JNI boundary. It does not yet contain Yamaha's phoneme converter.
 * Keep this distinction visible: native library loaded != language G2P ready.
 */
/**
 * One Hangul syllable's raw base-table labels.
 *
 * These labels are looked up before Korean adjacent-syllable transformations,
 * marker handling, and final syllable/phoneme assembly. They are diagnostic
 * intermediate values, not ready-to-sing phonemes.
 */
data class KoreanBaseSyllableComponents(
    val index: Int,
    val syllable: Char,
    val onset: String,
    val vowel: String,
    val coda: String
)

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
        if (loaded) nativeNormalizeKoreanInput(lyric) else lyric

    /**
     * Returns the raw onset/vowel/coda table entries for each precomposed
     * modern Hangul syllable in the normalized lyric.
     *
     * Spaces, punctuation, Latin text, and non-Hangul characters are omitted
     * from this diagnostic list. The original normalized character index is
     * retained in each result. This does not apply the Korean G2P context rules.
     */
    fun inspectKoreanBaseComponents(lyric: String): List<KoreanBaseSyllableComponents> {
        if (!loaded) return emptyList()
        val normalized = nativeNormalizeKoreanInput(lyric)
        return buildList {
            normalized.forEachIndexed { index, syllable ->
                if (syllable in '\uAC00'..'\uD7A3') {
                    val values = nativeKoreanBaseComponents(syllable.code) ?: return@forEachIndexed
                    if (values.size == 3) {
                        add(
                            KoreanBaseSyllableComponents(
                                index = index,
                                syllable = syllable,
                                onset = values[0],
                                vowel = values[1],
                                coda = values[2]
                            )
                        )
                    }
                }
            }
        }
    }

    private external fun nativeAbiVersion(): Int
    private external fun nativeSupportedLanguageMask(): Int
    private external fun nativeLanguageReady(languageId: Int): Boolean
    private external fun nativeNormalizeKoreanInput(lyric: String): String
    private external fun nativeKoreanBaseComponents(codePoint: Int): Array<String>?
}
