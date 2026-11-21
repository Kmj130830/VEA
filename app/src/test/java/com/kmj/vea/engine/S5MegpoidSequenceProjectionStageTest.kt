package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidSequenceProjectionStageTest {
    @Test
    fun composesDenseGlobalNormalizationAndStrideTwoTemporalProjection() {
        val denseNormalization = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = 2,
                outputFeatures = 2,
                weights = floatArrayOf(
                    1f, 0f,
                    0f, 1f
                ),
                bias = floatArrayOf(0.5f, -0.5f)
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = floatArrayOf(1.25f, 0.75f),
                bias = floatArrayOf(0.1f, -0.2f),
                epsilon = 1.0e-4f
            )
        )
        val temporal = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 2,
            outputFeatures = 1,
            kernelSize = 1,
            weights = floatArrayOf(2f, -1f),
            bias = floatArrayOf(0.25f)
        )
        val stage = S5MegpoidSequenceProjectionStage(
            denseNormalization = denseNormalization,
            temporalProjection = temporal
        )
        val input = floatArrayOf(
            1f, 2f, 3f, 4f, 5f,
            5f, 4f, 3f, 2f, 1f
        )

        val expectedNormalized = denseNormalization.forward(
            input = input,
            positions = 5,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )
        val expected = temporal.project(
            input = expectedNormalized,
            inputPositions = 5,
            gemm = ReferenceS5MegpoidGemmKernel
        )
        val actual = stage.forward(
            input = input,
            inputPositions = 5,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        assertEquals(3, stage.outputPositions(5))
        assertEquals(3, actual.size)
        assertArrayEquals(expected, actual, 0f)
    }

    @Test
    fun rejectsMismatchedNormalizationAndTemporalInputFeatures() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceProjectionStage(
                denseNormalization = S5MegpoidDenseNormalizationStage(
                    projection = S5MegpoidDenseProjection(
                        inputFeatures = 2,
                        outputFeatures = 2,
                        weights = FloatArray(4)
                    ),
                    normalization = S5MegpoidGlobalFeatureNormalization(
                        features = 2,
                        scale = FloatArray(2) { 1f },
                        bias = FloatArray(2),
                        epsilon = 1.0e-5f
                    )
                ),
                temporalProjection = S5MegpoidTemporalStridedConvolution(
                    inputFeatures = 3,
                    outputFeatures = 1,
                    kernelSize = 1,
                    weights = FloatArray(3)
                )
            )
        }
    }
}
