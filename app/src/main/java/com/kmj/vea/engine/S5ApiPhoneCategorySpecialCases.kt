package com.kmj.vea.engine

/**
 * Context-independent phone-category cases recovered from S5API
 * FUN_180074a50 after its alias/fallback-string preparation.
 *
 * This deliberately handles only the four exact spellings present in the
 * native comparisons. Every other label needs the active render-context
 * label-to-category map and must not be assigned a guessed numeric ID.
 */
object S5ApiPhoneCategorySpecialCases {
    /**
     * Returns a recovered fixed category index, or null when the label must
     * be resolved through the active S5API render-context map.
     *
     * Matching is exact and case-sensitive. In particular, lowercase "sil"
     * and "br" are not folded here.
     */
    fun fixedIndexOrNull(phoneLabel: String): Int? = when (phoneLabel) {
        "SIL" -> 0
        "Sil", "Br", "BR" -> 1
        else -> null
    }
}
