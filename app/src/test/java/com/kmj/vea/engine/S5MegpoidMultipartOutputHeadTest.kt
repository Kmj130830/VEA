package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidMultipartOutputHeadTest {
    @Test
    fun extractsOneSharedRealImaginaryPairFromTwoFeaturePlanes() {
        val tensor = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        val pair = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = tensor,
            positionCount = 3
        )

        assertEquals(3, pair.positionCount)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), pair.firstFeaturePlane(), 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), pair.secondFeaturePlane(), 0f)
    }

    @Test
    fun reusesTheSameFeaturePairAcrossAllFourPartModulations() {
        val pair = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = floatArrayOf(1f, 3f, 2f, 4f),
            positionCount = 2
        )
        val tableSize = 4 * 2
        val modulation = S5MegpoidMultipartOutputModulation(
            partCount = 4,
            positionCount = 2,
            phaseLocations = FloatArray(tableSize),
            distances = FloatArray(tableSize),
            gains = FloatArray(tableSize) { 1f },
            // Part-major: first all positions for part 0, then part 1, etc.
            rotationReal = floatArrayOf(
                1f, 1f, 0f, 0f,
                -1f, 1f, 0f, 1f
            ),
            nativeRotationSine = floatArrayOf(
                0f, 0f, 1f, 1f,
                0f, 0f, -1f, 0f
            )
        )

        val output = pair.newInterleavedComplexAccumulator()
        pair.accumulateInto(output, modulation)

        // Position 0: (1,2) + (-2,1) + (-1,-2) + (2,-1) = (0,0).
        // Position 1: (3,4) + (-4,3) + (3,4) + (3,4) = (5,15).
        assertArrayEquals(floatArrayOf(0f, 0f, 5f, 15f), output, 0f)
    }

    @Test
    fun returnsDefensiveCopiesOfBothFeaturePlanes() {
        val pair = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = floatArrayOf(1f, 2f, 3f, 4f),
            positionCount = 2
        )
        val first = pair.firstFeaturePlane()
        val second = pair.secondFeaturePlane()
        first[0] = -1f
        second[0] = -2f

        assertArrayEquals(floatArrayOf(1f, 2f), pair.firstFeaturePlane(), 0f)
        assertArrayEquals(floatArrayOf(3f, 4f), pair.secondFeaturePlane(), 0f)
    }

    @Test
    fun rejectsMalformedTwoPlaneTensorShapesAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(5),
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(8),
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(2),
                positionCount = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = floatArrayOf(1f, Float.NaN),
                positionCount = 1
            )
        }
    }
}
