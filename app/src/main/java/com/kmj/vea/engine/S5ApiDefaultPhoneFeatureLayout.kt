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

/**
 * Complete mode-0 phone-feature descriptor fields consumed by FUN_18003ab70
 * and forwarded to FUN_18006c720.
 *
 * This is a value object for the active render-context descriptor. It does
 * not supply defaults for fields that have not yet been recovered from the
 * live S5API context.
 */
data class S5ApiDefaultPhoneFeatureDescriptor(
    val categoryWidthAt10: Int,
    val minimumPhoneSecondsAt14: Float,
    val maximumPhoneSecondsAt18: Float,
    val followingContextCountAt1C: Int,
    val precedingContextCountAt20: Int,
    val includeLeadingFlagPairAt24: Boolean,
    val layoutModeAt25: Int,
    /** Preserved from +0x26; the recovered mode-0 branch does not consume it. */
    val auxiliaryByteAt26: Int = 0
) {
    init {
        require(categoryWidthAt10 >= S5ApiPhoneCategoryMap.CATEGORY_COUNT) {
            "Phone category width must fit all recovered category slots."
        }
        require(minimumPhoneSecondsAt14.isFinite() && minimumPhoneSecondsAt14 > 0f) {
            "Phone minimum duration must be a finite positive float."
        }
        require(maximumPhoneSecondsAt18.isFinite() &&
            maximumPhoneSecondsAt18 > minimumPhoneSecondsAt14) {
            "Phone maximum duration must exceed the positive minimum duration."
        }
        require(followingContextCountAt1C >= 0 && precedingContextCountAt20 >= 0) {
            "Phone context counts cannot be negative."
        }
        require(layoutModeAt25 in 0..255) {
            "Phone layout mode must fit in one byte."
        }
        require(auxiliaryByteAt26 in 0..255) {
            "Phone auxiliary byte must fit in one byte."
        }
    }

    fun requireDefaultMode0(): S5ApiDefaultPhoneFeatureLayout {
        require(layoutModeAt25 == 0) {
            "Only S5API phone-feature layout mode 0 is implemented."
        }
        return S5ApiDefaultPhoneFeatureLayout(
            categoryWidth = categoryWidthAt10,
            contextCountAt1C = followingContextCountAt1C,
            contextCountAt20 = precedingContextCountAt20,
            includeFlagPair = includeLeadingFlagPairAt24,
            layoutMode = layoutModeAt25
        )
    }
}
