package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidMultipartOutputHeadTest {
    @Test
    fun extractsFourConsecutiveRealImaginaryPlanePairsFromFeatureMajorTensor() {
        val tensor = FloatArray(8 * 3) { it.toFloat() }

        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = tensor,
            partCount = 4,
            positionCount = 3
        )

        assertArrayEquals(floatArrayOf(0f, 1f, 2f), planes.firstFeaturePlane(0), 0f)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), planes.secondFeaturePlane(0), 0f)
        assertArrayEquals(floatArrayOf(6f, 7f, 8f), planes.firstFeaturePlane(1), 0f)
        assertArrayEquals(floatArrayOf(9f, 10f, 11f), planes.secondFeaturePlane(1), 0f)
        assertArrayEquals(floatArrayOf(12f, 13f, 14f), planes.firstFeaturePlane(2), 0f)
        assertArrayEquals(floatArrayOf(15f, 16f, 17f), planes.secondFeaturePlane(2), 0f)
        assertArrayEquals(floatArrayOf(18f, 19f, 20f), planes.firstFeaturePlane(3), 0f)
        assertArrayEquals(floatArrayOf(21f, 22f, 23f), planes.secondFeaturePlane(3), 0f)
    }

    @Test
    fun accumulatesTheFourFeaturePairsIntoInterleavedComplexOutput() {
        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = FloatArray(8 * 3) { it.toFloat() },
            partCount = 4,
            positionCount = 3
        )
        val tableSize = 4 * 3
        val modulation = S5MegpoidMultipartOutputModulation(
            partCount = 4,
            positionCount = 3,
            phaseLocations = FloatArray(tableSize),
            distances = FloatArray(tableSize),
            gains = FloatArray(tableSize) { 1f },
            rotationReal = FloatArray(tableSize) { 1f },
            nativeRotationSine = FloatArray(tableSize)
        )

        val output = planes.newInterleavedComplexAccumulator()
        planes.accumulateInto(output, modulation)

        assertArrayEquals(
            floatArrayOf(36f, 48f, 40f, 52f, 44f, 56f),
            output,
            0f
        )
    }

    @Test
    fun returnsDefensiveCopiesOfFeaturePlanes() {
        val input = FloatArray(8 * 2) { it.toFloat() }
        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = input,
            partCount = 4,
            positionCount = 2
        )
        val first = planes.firstFeaturePlane(0)
        first[0] = -1f

        assertEquals(0f, planes.firstFeaturePlane(0)[0], 0f)
    }

    @Test
    fun rejectsMalformedMultipartTensorShapesAndCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(7 * 3),
                partCount = 4,
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(8 * 3),
                partCount = 1,
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(8 * 3),
                partCount = 4,
                positionCount = 0
            )
        }
    }

    @Test
    fun extractsAlreadyProducedActiveFeaturesWithoutInventingAProjection() {
        val input = FloatArray(8 * 2) { (it + 1).toFloat() }

        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = input,
            partCount = 4,
            positionCount = 2
        )

        assertEquals(4, planes.partCount)
        assertEquals(2, planes.positionCount)
        assertArrayEquals(floatArrayOf(1f, 2f), planes.firstFeaturePlane(0), 0f)
        assertArrayEquals(floatArrayOf(3f, 4f), planes.secondFeaturePlane(0), 0f)
        assertArrayEquals(floatArrayOf(5f, 6f), planes.firstFeaturePlane(1), 0f)
        assertArrayEquals(floatArrayOf(7f, 8f), planes.secondFeaturePlane(1), 0f)
        assertArrayEquals(floatArrayOf(9f, 10f), planes.firstFeaturePlane(2), 0f)
        assertArrayEquals(floatArrayOf(11f, 12f), planes.secondFeaturePlane(2), 0f)
        assertArrayEquals(floatArrayOf(13f, 14f), planes.firstFeaturePlane(3), 0f)
        assertArrayEquals(floatArrayOf(15f, 16f), planes.secondFeaturePlane(3), 0f)
    }

}
