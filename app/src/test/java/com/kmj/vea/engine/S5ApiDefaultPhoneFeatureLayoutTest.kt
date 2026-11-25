package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun mode0DescriptorMapsNativeOffsetsToFeatureLayoutAndDurationBounds() {
        val descriptor = S5ApiDefaultPhoneFeatureDescriptor(
            categoryWidthAt10 = 14,
            minimumPhoneSecondsAt14 = 0.01f,
            maximumPhoneSecondsAt18 = 1.5f,
            followingContextCountAt1C = 2,
            precedingContextCountAt20 = 3,
            includeLeadingFlagPairAt24 = true,
            layoutModeAt25 = 0,
            auxiliaryByteAt26 = 7
        )
        val layout = descriptor.requireDefaultMode0()

        assertEquals(14, layout.categoryWidth)
        assertEquals(2, layout.contextCountAt1C)
        assertEquals(3, layout.contextCountAt20)
        assertEquals(true, layout.includeFlagPair)
        assertEquals(0, layout.layoutMode)
        assertEquals(6, layout.categorySegmentCount)
        assertEquals(87, layout.rowWidth)
        assertEquals(0.01f, descriptor.minimumPhoneSecondsAt14, 0f)
        assertEquals(1.5f, descriptor.maximumPhoneSecondsAt18, 0f)
        assertEquals(7, descriptor.auxiliaryByteAt26)
    }

    @Test
    fun rejectsMode1RatherThanTreatingItAsMode0() {
        val descriptor = S5ApiDefaultPhoneFeatureDescriptor(
            categoryWidthAt10 = 14,
            minimumPhoneSecondsAt14 = 0.01f,
            maximumPhoneSecondsAt18 = 1.0f,
            followingContextCountAt1C = 0,
            precedingContextCountAt20 = 0,
            includeLeadingFlagPairAt24 = false,
            layoutModeAt25 = 1
        )
        assertThrows(IllegalArgumentException::class.java) {
            descriptor.requireDefaultMode0()
        }
    }

    @Test
    fun validatesDescriptorCategoryWidthDurationAndContextFields() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor(13, 0.01f, 1.0f, 0, 0, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor(14, 0.0f, 1.0f, 0, 0, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor(14, 0.1f, 0.1f, 0, 0, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor(14, 0.01f, 1.0f, -1, 0, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor(14, 0.01f, 1.0f, 0, 0, false, 0, 256)
        }
    }

}
