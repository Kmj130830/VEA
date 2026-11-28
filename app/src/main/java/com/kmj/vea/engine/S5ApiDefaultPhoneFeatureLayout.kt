package com.kmj.vea.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    val auxiliaryByteAt26: Int = 0,
    /** FUN_18003ab70 frame period in seconds at descriptor +0x30; absent in older captures. */
    val framePeriodSecondsAt30: Float? = null
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
        require(framePeriodSecondsAt30 == null ||
            (framePeriodSecondsAt30.isFinite() && framePeriodSecondsAt30 > 0f)) {
            "Phone frame period at +0x30 must be finite and positive when present."
        }
    }

    fun requireFramePeriodSeconds(): Float =
        framePeriodSecondsAt30 ?: throw IllegalStateException(
            "The native phone descriptor does not include its +0x30 frame period."
        )

    companion object {
        /**
         * Decodes only the verified fields from an in-memory native descriptor.
         *
         * All offsets are relative to descriptor base. The function does not
         * read or infer the opaque fields before +0x10 or mode-specific fields
         * after +0x26. It is a transport/parser boundary for a future captured
         * S5API context; the current Android renderer does not own this native
         * Windows object.
         */
        fun fromNativeBytes(bytes: ByteArray, baseOffset: Int = 0): S5ApiDefaultPhoneFeatureDescriptor {
            require(baseOffset >= 0) { "Descriptor base offset cannot be negative." }
            require(baseOffset.toLong() + NATIVE_MINIMUM_SIZE.toLong() <= bytes.size.toLong()) {
                "Native phone descriptor requires at least $NATIVE_MINIMUM_SIZE bytes from its base."
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun intAt(relativeOffset: Int): Int = buffer.getInt(baseOffset + relativeOffset)
            fun floatAt(relativeOffset: Int): Float = buffer.getFloat(baseOffset + relativeOffset)
            fun byteAt(relativeOffset: Int): Int = buffer.get(baseOffset + relativeOffset).toInt() and 0xff

            return S5ApiDefaultPhoneFeatureDescriptor(
                categoryWidthAt10 = intAt(CATEGORY_WIDTH_OFFSET),
                minimumPhoneSecondsAt14 = floatAt(MINIMUM_DURATION_OFFSET),
                maximumPhoneSecondsAt18 = floatAt(MAXIMUM_DURATION_OFFSET),
                followingContextCountAt1C = intAt(FOLLOWING_CONTEXT_OFFSET),
                precedingContextCountAt20 = intAt(PRECEDING_CONTEXT_OFFSET),
                includeLeadingFlagPairAt24 = byteAt(FLAG_PAIR_OFFSET) != 0,
                layoutModeAt25 = byteAt(LAYOUT_MODE_OFFSET),
                auxiliaryByteAt26 = byteAt(AUXILIARY_BYTE_OFFSET),
                framePeriodSecondsAt30 = if (
                    baseOffset.toLong() + NATIVE_FRAME_PERIOD_SIZE.toLong() <= bytes.size.toLong()
                ) {
                    floatAt(FRAME_PERIOD_SECONDS_OFFSET)
                } else {
                    null
                }
            )
        }

        const val CATEGORY_WIDTH_OFFSET = 0x10
        const val MINIMUM_DURATION_OFFSET = 0x14
        const val MAXIMUM_DURATION_OFFSET = 0x18
        const val FOLLOWING_CONTEXT_OFFSET = 0x1c
        const val PRECEDING_CONTEXT_OFFSET = 0x20
        const val FLAG_PAIR_OFFSET = 0x24
        const val LAYOUT_MODE_OFFSET = 0x25
        const val AUXILIARY_BYTE_OFFSET = 0x26
        const val FRAME_PERIOD_SECONDS_OFFSET = 0x30
        const val NATIVE_MINIMUM_SIZE = 0x27
        const val NATIVE_FRAME_PERIOD_SIZE = 0x34
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
