package com.kmj.vea.engine

/**
 * The verified default-layout portion of the descriptor consumed by
 * S5API FUN_18006c720.
 *
 * Values are kept tied to their source offsets. The context counts are not
 * given left/right semantic names here because their descriptor construction
 * path has not yet been completely recovered.
 */
data class S5ApiDefaultPhoneFeatureLayout(
    val categoryWidth: Int,             // render descriptor +0x10
    val contextCountAt1C: Int,          // render descriptor +0x1C
    val contextCountAt20: Int,          // render descriptor +0x20
    val includeFlagPair: Boolean        // render descriptor +0x24 != 0
) {
    init {
        require(categoryWidth > 0) { "Phone category width must be positive." }
        require(contextCountAt1C >= 0 && contextCountAt20 >= 0) {
            "Phone context counts cannot be negative."
        }
    }

    val flagFeatureWidth: Int
        get() = if (includeFlagPair) 2 else 0

    val categorySegmentCount: Int
        get() = checkedInt(contextCountAt1C.toLong() + 1L + contextCountAt20.toLong())

    /** Total float32 values in one row for the verified param_12 == 0 branch. */
    val rowWidth: Int
        get() {
            val categoryValues = categorySegmentCount.toLong() * categoryWidth.toLong()
            return checkedInt(1L + flagFeatureWidth.toLong() + categoryValues)
        }

    /** Duration scalar is always the first float in the default layout. */
    val durationFeatureIndex: Int
        get() = 0

    /** First optional two-value flag pair; null when the descriptor disables it. */
    val flagFeatureStartIndex: Int?
        get() = if (includeFlagPair) 1 else null

    /** First category one-hot segment in the default layout. */
    val categorySegmentsStartIndex: Int
        get() = 1 + flagFeatureWidth

    /**
     * Starting float index for one category segment, without assigning a
     * directional meaning to its ordinal.
     */
    fun categorySegmentStartIndex(segmentOrdinal: Int): Int {
        require(segmentOrdinal in 0 until categorySegmentCount) {
            "Category segment ordinal is outside the active descriptor window."
        }
        val offset = categorySegmentsStartIndex.toLong() +
            segmentOrdinal.toLong() * categoryWidth.toLong()
        return checkedInt(offset)
    }

    private fun checkedInt(value: Long): Int {
        require(value in 0..Int.MAX_VALUE.toLong()) {
            "Phone feature row width exceeds the supported integer range."
        }
        return value.toInt()
    }
}
