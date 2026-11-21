package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidGlobalFeatureNormalizationTest {
    @Test
    fun appliesGlobalMeanVarianceAndPerFeatureAffine() {
        val normalization = S5MegpoidGlobalFeatureNormalization(
            features = 2,
            scale = floatArrayOf(2.0f, 0.5f),
            bias = floatArrayOf(10.0f, -3.0f),
            epsilon = 0.0f
        )
        val input = floatArrayOf(
            1.0f, 2.0f,
            3.0f, 4.0f
        )

        val output = normalization.apply(
            input = input,
            positions = 2,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        val inverseStd = 1.0f / kotlin.math.sqrt(1.25f)
        val expected = floatArrayOf(
            2.0f * inverseStd * 1.0f + (10.0f - 2.0f * inverseStd * 2.5f),
            2.0f * inverseStd * 2.0f + (10.0f - 2.0f * inverseStd * 2.5f),
            0.5f * inverseStd * 3.0f + (-3.0f - 0.5f * inverseStd * 2.5f),
            0.5f * inverseStd * 4.0f + (-3.0f - 0.5f * inverseStd * 2.5f)
        )
        output.indices.forEach { index ->
            assertEquals(expected[index], output[index], 1.0e-5f)
        }
    }

    @Test
    fun constantInputUsesEpsilonAndReturnsThePerFeatureBias() {
        val normalization = S5MegpoidGlobalFeatureNormalization(
            features = 2,
            scale = floatArrayOf(3.0f, 7.0f),
            bias = floatArrayOf(4.0f, -2.0f),
            epsilon = 1.0e-4f
        )

        val output = normalization.apply(
            input = FloatArray(2 * 3) { 5.0f },
            positions = 3,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        assertEquals(listOf(4.0f, 4.0f, 4.0f, -2.0f, -2.0f, -2.0f), output.toList())
    }

    @Test
    fun validatesFeatureRowsPositionsScaleBiasAndEpsilon() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = FloatArray(1),
                bias = FloatArray(2),
                epsilon = 1.0e-5f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidGlobalFeatureNormalization(
                features = 2,
                scale = FloatArray(2),
                bias = FloatArray(2),
                epsilon = Float.NaN
            )
        }

        val valid = S5MegpoidGlobalFeatureNormalization(
            features = 2,
            scale = FloatArray(2) { 1.0f },
            bias = FloatArray(2),
            epsilon = 1.0e-5f
        )
        assertThrows(IllegalArgumentException::class.java) {
            valid.apply(
                input = FloatArray(4),
                positions = 3,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }
}
