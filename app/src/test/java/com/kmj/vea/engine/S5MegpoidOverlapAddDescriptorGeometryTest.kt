package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidOverlapAddDescriptorGeometryTest {
    @Test
    fun computesWindowOffsetOverlapCountAndPowerOfTwoRing() {
        val actual = S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = 100,
            geometry = 512,
            fftSize = 1024
        )

        assertEquals(100, actual.outputSamples)
        assertEquals(512, actual.geometry)
        assertEquals(1024, actual.fftSize)
        assertEquals(3, actual.windowOffset)
        assertEquals(5, actual.overlapContributionCount)
        assertEquals(4, actual.ringSlotCount)
        assertEquals(3, actual.ringMask)
    }

    @Test
    fun usesCeilingDivisionWhenGeometryHalfIsNotAnOutputMultiple() {
        val actual = S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = 256,
            geometry = 1536,
            fftSize = 1536
        )

        assertEquals(3, actual.windowOffset)
        assertEquals(5, actual.overlapContributionCount)
        assertEquals(4, actual.ringSlotCount)
        assertEquals(3, actual.ringMask)
    }

    @Test
    fun ringCapacityIsIndependentFromOverlapContributionCount() {
        val actual = S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = 256,
            geometry = 1024,
            fftSize = 2048
        )

        assertEquals(2, actual.windowOffset)
        assertEquals(4, actual.overlapContributionCount)
        assertEquals(2, actual.ringSlotCount)
        assertEquals(1, actual.ringMask)
        assertTrue(actual.overlapContributionCount > actual.ringSlotCount)
    }

    @Test
    fun zeroWindowOffsetStillAllocatesOneRingSlot() {
        val actual = S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = 1024,
            geometry = 64,
            fftSize = 512
        )

        assertEquals(1, actual.ringSlotCount)
        assertEquals(0, actual.ringMask)
        assertEquals(3, actual.overlapContributionCount)
    }

    @Test
    fun preservesGeometryAndFftSizeAsIndependentInputs() {
        val actual = S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = 128,
            geometry = 600,
            fftSize = 2048
        )

        assertEquals(600, actual.geometry)
        assertEquals(2048, actual.fftSize)
    }

    @Test
    fun rejectsInvalidDimensionsAndIntegerOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOverlapAddDescriptorGeometryBuilder.build(0, 512, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOverlapAddDescriptorGeometryBuilder.build(256, 0, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOverlapAddDescriptorGeometryBuilder.build(256, 512, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
                outputSamples = 1,
                geometry = Int.MAX_VALUE,
                fftSize = 2048
            )
        }
    }
}
