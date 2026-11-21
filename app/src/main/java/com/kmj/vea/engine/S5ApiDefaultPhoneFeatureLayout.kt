package com.kmj.vea.engine

/** Recovered default-layout fields consumed by S5API FUN_18006c720. */
data class S5ApiDefaultPhoneFeatureLayout(
    val categoryWidth: Int,             // descriptor +0x10
    val contextCountAt1C: Int,          // descriptor +0x1C, forward context count
    val contextCountAt20: Int,          // descriptor +0x20, backward context count
    val includeFlagPair: Boolean,       // descriptor +0x24 != 0
    val layoutMode: Int = 0             // descriptor +0x25; only mode 0 is ported
) {
    init {
        require(categoryWidth > 0) { "Phone category width must be positive." }
        require(contextCountAt1C >= 0 && contextCountAt20 >= 0) {
            "Phone context counts cannot be negative."
        }
        require(layoutMode in 0..255) { "Phone layout mode must fit in one byte." }
    }

    val flagFeatureWidth: Int
        get() = if (includeFlagPair) 2 else 0

    val categorySegmentCount: Int
        get() = checkedInt(contextCountAt1C.toLong() + 1L + contextCountAt20.toLong())

    val rowWidth: Int
        get() {
            val categoryValues = categorySegmentCount.toLong() * categoryWidth.toLong()
            return checkedInt(1L + flagFeatureWidth.toLong() + categoryValues)
        }

    val durationFeatureIndex: Int
        get() = 0

    val flagFeatureStartIndex: Int?
        get() = if (includeFlagPair) 1 else null

    val categorySegmentsStartIndex: Int
        get() = 1 + flagFeatureWidth

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
