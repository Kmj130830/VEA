package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidDenseNormalizationStageTest {
    @Test
    fun forwardsDenseProjectionIntoGlobalFeatureNormalization() {
        val stage = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = 2,
                outputFeatures = 2,
                weights = floatArrayOf(
                    1f, 0f,
                    0f, 1f
                )
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = floatArrayOf(1f, 2f),
                bias = floatArrayOf(0f, 1f),
                epsilon = 0f
            )
        )

        val output = stage.forward(
            input = floatArrayOf(1f, 2f, 3f, 4f),
            positions = 2,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        val inverseStd = 1.0f / kotlin.math.sqrt(1.25f)
        val expected = floatArrayOf(
            (1f - 2.5f) * inverseStd,
            (2f - 2.5f) * inverseStd,
            (3f - 2.5f) * inverseStd * 2f + 1f,
            (4f - 2.5f) * inverseStd * 2f + 1f
        )
        output.indices.forEach { index ->
            assertEquals(expected[index], output[index], 1.0e-5f)
        }
    }

    @Test
    fun rejectsMismatchedProjectionAndNormalizationFeatureCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidDenseNormalizationStage(
                projection = S5MegpoidDenseProjection(
                    inputFeatures = 2,
                    outputFeatures = 3,
                    weights = FloatArray(6)
                ),
                normalization = S5MegpoidGlobalFeatureNormalization(
                    features = 2,
                    scale = FloatArray(2),
                    bias = FloatArray(2),
                    epsilon = 1.0e-5f
                )
            )
        }
    }

    @Test
    fun propagatesInvalidInputShapeFromTheProjectionStage() {
        val stage = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = 2,
                outputFeatures = 2,
                weights = floatArrayOf(1f, 0f, 0f, 1f)
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = FloatArray(2) { 1f },
                bias = FloatArray(2),
                epsilon = 1.0e-5f
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            stage.forward(
                input = FloatArray(3),
                positions = 2,
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }
}
