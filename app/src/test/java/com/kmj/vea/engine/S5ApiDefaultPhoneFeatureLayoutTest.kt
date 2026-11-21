package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class S5ApiDefaultPhoneFeatureLayoutTest {
    @Test
    fun calculatesRowWidthWithoutOptionalFlagPair() {
        val layout = S5ApiDefaultPhoneFeatureLayout(
            categoryWidth = 64,
            contextCountAt1C = 2,
            contextCountAt20 = 3,
            includeFlagPair = false
        )

        assertEquals(6, layout.categorySegmentCount)
        assertEquals(385, layout.rowWidth)
        assertEquals(0, layout.durationFeatureIndex)
        assertNull(layout.flagFeatureStartIndex)
        assertEquals(1, layout.categorySegmentsStartIndex)
    }

    @Test
    fun calculatesRowWidthWithOptionalFlagPair() {
        val layout = S5ApiDefaultPhoneFeatureLayout(
            categoryWidth = 32,
            contextCountAt1C = 1,
            contextCountAt20 = 2,
            includeFlagPair = true
        )

        assertEquals(4, layout.categorySegmentCount)
        assertEquals(131, layout.rowWidth)
        assertEquals(1, layout.flagFeatureStartIndex)
        assertEquals(3, layout.categorySegmentsStartIndex)
    }

    @Test
    fun computesSegmentOffsetsAsCategoryWidthStrides() {
        val layout = S5ApiDefaultPhoneFeatureLayout(
            categoryWidth = 48,
            contextCountAt1C = 1,
            contextCountAt20 = 1,
            includeFlagPair = false
        )

        assertEquals(1, layout.categorySegmentStartIndex(0))
        assertEquals(49, layout.categorySegmentStartIndex(1))
        assertEquals(97, layout.categorySegmentStartIndex(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeSegmentOrdinal() {
        S5ApiDefaultPhoneFeatureLayout(32, 1, 1, false)
            .categorySegmentStartIndex(3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCategoryWidth() {
        S5ApiDefaultPhoneFeatureLayout(0, 1, 1, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeContextCounts() {
        S5ApiDefaultPhoneFeatureLayout(32, -1, 1, false)
    }
}
