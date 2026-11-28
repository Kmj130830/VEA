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
    fun decodesVerifiedNativeDescriptorOffsetsFromLittleEndianBytes() {
        val base = 5
        val bytes = ByteArray(base + S5ApiDefaultPhoneFeatureDescriptor.NATIVE_MINIMUM_SIZE + 3)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(base + 0x10, 24)
        buffer.putFloat(base + 0x14, 0.004f)
        buffer.putFloat(base + 0x18, 2.75f)
        buffer.putInt(base + 0x1c, 3)
        buffer.putInt(base + 0x20, 2)
        buffer.put(base + 0x24, 0x7f.toByte())
        buffer.put(base + 0x25, 0)
        buffer.put(base + 0x26, 0xa5.toByte())

        val descriptor = S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(bytes, base)
        assertEquals(24, descriptor.categoryWidthAt10)
        assertEquals(0.004f, descriptor.minimumPhoneSecondsAt14, 0f)
        assertEquals(2.75f, descriptor.maximumPhoneSecondsAt18, 0f)
        assertEquals(3, descriptor.followingContextCountAt1C)
        assertEquals(2, descriptor.precedingContextCountAt20)
        assertEquals(true, descriptor.includeLeadingFlagPairAt24)
        assertEquals(0, descriptor.layoutModeAt25)
        assertEquals(0xa5, descriptor.auxiliaryByteAt26)
        assertEquals(24, descriptor.requireDefaultMode0().categoryWidth)
    }

    @Test
    fun rejectsTruncatedOrInvalidNativeDescriptorBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(ByteArray(0x26))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(ByteArray(64), -1)
        }

        val invalid = ByteArray(S5ApiDefaultPhoneFeatureDescriptor.NATIVE_MINIMUM_SIZE)
        val buffer = java.nio.ByteBuffer.wrap(invalid).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x10, 14)
        buffer.putFloat(0x14, 0.01f)
        buffer.putFloat(0x18, Float.NaN)
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(invalid)
        }
    }

    @Test
    fun decodesOptionalFramePeriodFromNativeOffset30() {
        val base = 3
        val bytes = ByteArray(base + S5ApiDefaultPhoneFeatureDescriptor.NATIVE_FRAME_PERIOD_SIZE)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(base + 0x10, 14)
        buffer.putFloat(base + 0x14, 0.01f)
        buffer.putFloat(base + 0x18, 1.0f)
        buffer.putInt(base + 0x1c, 1)
        buffer.putInt(base + 0x20, 1)
        buffer.put(base + 0x24, 0)
        buffer.put(base + 0x25, 0)
        buffer.put(base + 0x26, 0)
        buffer.putFloat(base + 0x30, 0.01f)

        val descriptor = S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(bytes, base)
        assertEquals(0.01f, descriptor.framePeriodSecondsAt30!!, 0f)
        assertEquals(0.01f, descriptor.requireFramePeriodSeconds(), 0f)
    }

    @Test
    fun olderNativeDescriptorCaptureLeavesFramePeriodUnavailable() {
        val descriptor = S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(
            ByteArray(S5ApiDefaultPhoneFeatureDescriptor.NATIVE_MINIMUM_SIZE).also { bytes ->
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(0x10, 14)
                buffer.putFloat(0x14, 0.01f)
                buffer.putFloat(0x18, 1.0f)
            }
        )
        assertNull(descriptor.framePeriodSecondsAt30)
        assertThrows(IllegalStateException::class.java) {
            descriptor.requireFramePeriodSeconds()
        }
    }

    @Test
    fun rejectsInvalidOptionalFramePeriod() {
        for (invalidPeriod in listOf(0f, -0.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val bytes = ByteArray(S5ApiDefaultPhoneFeatureDescriptor.NATIVE_FRAME_PERIOD_SIZE)
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x10, 14)
            buffer.putFloat(0x14, 0.01f)
            buffer.putFloat(0x18, 1.0f)
            buffer.putFloat(0x30, invalidPeriod)
            assertThrows(IllegalArgumentException::class.java) {
                S5ApiDefaultPhoneFeatureDescriptor.fromNativeBytes(bytes)
            }
        }
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
