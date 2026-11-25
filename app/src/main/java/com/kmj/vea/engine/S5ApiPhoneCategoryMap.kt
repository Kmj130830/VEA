package com.kmj.vea.engine

/**
 * Static phone groups recovered from FUN_180003a90 / FUN_18006c520.
 *
 * These list positions are the native numeric IDs, not a guessed ordering:
 * FUN_180003a90 builds the 14-entry group vector in this exact order,
 * FUN_1800111c0 copies it to DAT_1801c4160, and FUN_18006c520 iterates the
 * outer group index and writes that index to each label node at +0x40.
 * FUN_180074a50 then reads the stored uint32 index from the context-map value.
 */
object S5ApiPhoneCategoryMap {
    const val CATEGORY_COUNT = 14
    const val FALLBACK_CATEGORY_INDEX = 13

    private val groups = listOf(
        setOf("sil", "Sil", "SIL"),
        setOf("br", "Br", "BR"),
        setOf(
            "a", "i", "u", "e", "o", "wo", "ey", "aw", "ay", "ow", "oy",
            "aa", "uh", "iy", "ih", "ah", "uw", "eh", "ae", "er", "ao",
            "i_n", "uei", "iE_n", "yE_r", "@N", "u@N", "@U", "ia", "i`",
            "@`", "i\\", "i@U", "aI", "uaI", "y_n", "7", "iN", "iE_r",
            "ua_n", "iUN", "AN", "ei", "AU", "uAN", "a_n", "iAN", "y{_n",
            "u@_n", "@_n", "uo", "UN", "iAU", "ua", "ia+1", "iE_r+1",
            "yE_r+1", "ua+1", "uo+1", "iAU+1", "i@U+1", "uaI+1", "uei+1",
            "iAN+1", "iUN+1", "iE_n+1", "y{_n+1", "u@N+1", "u@_n+1",
            "ua_n+1", "uAN+1", "y|CH", "a|CH", "i|CH", "u|CH", "o|CH"
        ),
        setOf("n", "N", "m", "ny", "my", "ng"),
        setOf("g", "d", "b", "gy", "dy", "by", "dh"),
        setOf("z", "zh", "v", "z`"),
        setOf("j", "jh"),
        setOf(
            "y", "w", "ia+0", "iE_r+0", "yE_r+0", "ua+0", "uo+0", "iAU+0",
            "i@U+0", "uaI+0", "uei+0", "iAN+0", "iUN+0", "iE_n+0", "y{_n+0",
            "u@N+0", "u@_n+0", "ua_n+0", "uAN+0"
        ),
        setOf("r", "ry", "l", "4"),
        setOf("k", "t", "p", "ky", "ty", "py", "p_h", "k_h"),
        setOf("s", "sh", "h", "C", "f", "hy", "hh", "th", "s\\"),
        setOf("ch", "ts", "ts\\", "ts`", "t_h", "ts_h", "ts`_h", "ts\\_h"),
        setOf("cl"),
        setOf("junk")
    )

    fun resolve(phoneLabel: String): Int {
        val canonical = VsmPhoneLabelCanonicalizer.canonicalize(phoneLabel)
        val lookup = when {
            canonical != phoneLabel -> canonical
            groups.any { phoneLabel in it } -> phoneLabel
            else -> nativeNormalize(phoneLabel)
        }
        S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(lookup)?.let { return it }
        for (index in 0 until FALLBACK_CATEGORY_INDEX) {
            if (lookup in groups[index]) return index
        }
        return FALLBACK_CATEGORY_INDEX
    }

    fun labelsInCategory(index: Int): Set<String> {
        require(index in 0 until CATEGORY_COUNT)
        return groups[index].toSet()
    }

    private fun nativeNormalize(value: String): String = buildString(value.length) {
        for (c in value) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c == '4') append(c)
        }
    }
}
