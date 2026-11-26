package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidMultipartOutputHeadTest {
    @Test
    fun extractsFourPartSpecificRealImaginaryPairsFromFeatureMajorTensor() {
        val tensor = FloatArray(8 * 3) { it.toFloat() }

        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = tensor,
            partCount = 4,
            positionCount = 3
        )

        assertEquals(4, planes.partCount)
        assertEquals(3, planes.positionCount)

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
    fun accumulatesEachPartsOwnComplexPairWithPartMajorModulationTables() {
        // Each consecutive pair of rows belongs to a different D00 part.
        val tensor = floatArrayOf(
            1f, 3f,       // part 0 real
            2f, 4f,       // part 0 imag
            10f, 30f,     // part 1 real
            20f, 40f,     // part 1 imag
            100f, 300f,   // part 2 real
            200f, 400f,   // part 2 imag
            1000f, 3000f, // part 3 real
            2000f, 4000f  // part 3 imag
        )
        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = tensor,
            partCount = 4,
            positionCount = 2
        )
        val tableSize = 4 * 2
        val modulation = S5MegpoidMultipartOutputModulation(
            partCount = 4,
            positionCount = 2,
            phaseLocations = FloatArray(tableSize),
            distances = FloatArray(tableSize),
            gains = FloatArray(tableSize) { 1f },
            rotationReal = FloatArray(tableSize) { 1f },
            nativeRotationSine = FloatArray(tableSize)
        )

        val output = planes.newInterleavedComplexAccumulator()
        planes.accumulateInto(output, modulation)

        assertArrayEquals(
            floatArrayOf(1111f, 2222f, 3333f, 4444f),
            output,
            0f
        )
    }

    @Test
    fun returnsDefensiveCopiesOfEveryFeaturePlane() {
        val planes = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = FloatArray(8 * 2) { it.toFloat() },
            partCount = 4,
            positionCount = 2
        )
        val first = planes.firstFeaturePlane(2)
        val second = planes.secondFeaturePlane(2)
        first[0] = -1f
        second[0] = -2f

        assertArrayEquals(floatArrayOf(8f, 9f), planes.firstFeaturePlane(2), 0f)
        assertArrayEquals(floatArrayOf(10f, 11f), planes.secondFeaturePlane(2), 0f)
    }

    @Test
    fun rejectsMalformedMultipartTensorShapesAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(23),
                partCount = 4,
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(24),
                partCount = 1,
                positionCount = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(0),
                partCount = 4,
                positionCount = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidMultipartFeaturePlaneExtractor.extract(
                outputFeatureMajor = FloatArray(24).also { it[17] = Float.NaN },
                partCount = 4,
                positionCount = 3
            )
        }
    }
}
