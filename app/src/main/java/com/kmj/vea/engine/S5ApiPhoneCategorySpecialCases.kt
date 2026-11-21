package com.kmj.vea.engine

/** Exact category overrides recovered from FUN_180074a50. */
object S5ApiPhoneCategorySpecialCases {
    // SIL and Sil are also stored in group 0; these four native comparisons override to 1.
    fun fixedIndexOrNull(phoneLabel: String): Int? = when (phoneLabel) {
        "SIL", "Sil", "Br", "BR" -> 1
        else -> null
    }

    fun fixedIndexOrNull(event: VsmPhoneEvent): Int? =
        fixedIndexOrNull(event.canonicalPhoneLabel)
}
