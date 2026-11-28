package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidActiveMultipartProjectionStageTest {
    @Test
    fun foldsNormalizerAndDenseParametersIntoNativeMatrixBiasAndRowScale() {
        val denseWeights = floatArrayOf(
            1f, 2f, 3f,
            -1f, 0.5f, 2f
        )
        val denseBias = floatArrayOf(0.25f, -0.75f)
        val inputScale = floatArrayOf(2f, 0.5f, -1f)
        val inputBias = floatArrayOf(0.1f, -0.2f, 0.3f)

        val stage = S5MegpoidActiveMultipartProjectionStage.build(
            inputFeatures = 3,
            outputFeatures = 2,
            denseWeights = denseWeights,
            denseBias = denseBias,
            inputScale = inputScale,
            inputBias = inputBias,
            epsilon = 1.0e-6f
        )

        assertArrayEquals(
            floatArrayOf(2f, 1f, -3f, -2f, 0.25f, -2f),
            stage.copyFoldedWeights(),
            0f
        )
        assertArrayEquals(
            floatArrayOf(0.95f, -0.65f),
            stage.copyFoldedBias(),
            1.0e-6f
        )
        assertArrayEquals(
            floatArrayOf(0f, -0.5f),
            stage.copyFoldedScale(),
            0f
        )
    }

    @Test
    fun nativeFoldedPathMatchesGlobalNormalizationFollowedByDenseProjection() {
        val features = 3
        val outputs = 2
        val positions = 5
        val input = floatArrayOf(
            0.5f, 1.0f, -0.5f, 2.0f, 1.5f,
            -1.0f, 0.25f, 1.25f, -0.75f, 0.5f,
            2.0f, -1.5f, 0.75f, 0.5f, -0.25f
        )
        val scale = floatArrayOf(0.75f, 1.25f, -0.5f)
        val normBias = floatArrayOf(0.2f, -0.1f, 0.3f)
        val weights = floatArrayOf(
            0.5f, -0.25f, 1.5f,
            -1.0f, 0.75f, 0.125f
        )
        val bias = floatArrayOf(0.125f, -0.375f)
        val epsilon = 1.0e-5f

        val normalizer = S5MegpoidGlobalFeatureNormalization(
            features = features,
            scale = scale,
            bias = normBias,
            epsilon = epsilon
        )
        val dense = S5MegpoidDenseProjection(
            inputFeatures = features,
            outputFeatures = outputs,
            weights = weights,
            bias = bias
        )
        val expected = dense.project(
            input = normalizer.apply(
                input = input,
                positions = positions,
                statistics = ReferenceS5MegpoidStatisticsKernel
            ),
            positions = positions,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        val stage = S5MegpoidActiveMultipartProjectionStage.build(
            inputFeatures = features,
            outputFeatures = outputs,
            denseWeights = weights,
            denseBias = bias,
            inputScale = scale,
            inputBias = normBias,
            epsilon = epsilon
        )
        val actual = stage.forward(
            inputFeatureMajor = input,
            positions = positions,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        assertEquals(outputs * positions, actual.size)
        for (index in expected.indices) {
            assertEquals("feature value $index", expected[index], actual[index], 2.0e-5f)
        }
    }

    @Test
    fun rejectsIncorrectTensorSizesAndNonFiniteParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidActiveMultipartProjectionStage.build(
                inputFeatures = 3,
                outputFeatures = 2,
                denseWeights = FloatArray(5),
                denseBias = FloatArray(2),
                inputScale = FloatArray(3) { 1f },
                inputBias = FloatArray(3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidActiveMultipartProjectionStage.build(
                inputFeatures = 3,
                outputFeatures = 2,
                denseWeights = FloatArray(6) { 1f },
                denseBias = FloatArray(2) { 1f },
                inputScale = FloatArray(3) { 1f },
                inputBias = floatArrayOf(0f, Float.NaN, 0f)
            )
        }
    }
}
