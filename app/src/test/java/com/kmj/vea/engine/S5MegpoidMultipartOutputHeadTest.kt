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
    fun modelBackedHeadProjectsThenSplitsTheOutputIntoPartPairs() {
        val normalizer = S5MegpoidGlobalFeatureNormalization(
            features = 2,
            scale = floatArrayOf(1f, 1f),
            bias = floatArrayOf(0f, 0f),
            epsilon = 1.0e-6f
        )
        val dense = S5MegpoidDenseProjection(
            inputFeatures = 2,
            outputFeatures = 4,
            weights = floatArrayOf(
                1f, 0f,
                0f, 1f,
                1f, 1f,
                2f, 0f
            ),
            bias = floatArrayOf(0.5f, 1f, 1.5f, 2f)
        )
        val outputHead = S5MegpoidMultipartOutputHeadStage(
            normalizedDenseProjection = S5MegpoidPostResidualFallbackStage(
                inputFeatures = 2,
                outputFeatures = 4,
                normalization = normalizer,
                denseProjection = dense
            ),
            partCount = 2
        )
        val input = floatArrayOf(1f, 2f, 3f, 4f)

        val expectedFeatureMajor = normalizer.apply(
            input = input,
            positions = 2,
            statistics = ReferenceS5MegpoidStatisticsKernel
        ).let { normalized ->
            dense.project(
                input = normalized,
                positions = 2,
                gemm = ReferenceS5MegpoidGemmKernel
            )
        }
        val actual = outputHead.project(
            inputFeatureMajor = input,
            inputPositions = 2,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        val expected = S5MegpoidMultipartFeaturePlaneExtractor.extract(
            outputFeatureMajor = expectedFeatureMajor,
            partCount = 2,
            positionCount = 2
        )
        assertArrayEquals(expected.firstFeaturePlane(0), actual.firstFeaturePlane(0), 0f)
        assertArrayEquals(expected.secondFeaturePlane(0), actual.secondFeaturePlane(0), 0f)
        assertArrayEquals(expected.firstFeaturePlane(1), actual.firstFeaturePlane(1), 0f)
        assertArrayEquals(expected.secondFeaturePlane(1), actual.secondFeaturePlane(1), 0f)
    }
}
