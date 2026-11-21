package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidDenseProjectionTest {
    @Test
    fun computesMatrixProjectionAndBroadcastsPerOutputFeatureBias() {
        val projection = S5MegpoidDenseProjection(
            inputFeatures = 3,
            outputFeatures = 2,
            weights = floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f
            ),
            bias = floatArrayOf(10f, -1f)
        )
        val input = floatArrayOf(
            1f, 2f,
            3f, 4f,
            5f, 6f
        )

        val output = projection.project(
            input = input,
            positions = 2,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertEquals(listOf(32f, 38f, 48f, 63f), output.toList())
    }

    @Test
    fun supportsProjectionWithoutBias() {
        val projection = S5MegpoidDenseProjection(
            inputFeatures = 2,
            outputFeatures = 1,
            weights = floatArrayOf(2f, 3f)
        )

        val output = projection.project(
            input = floatArrayOf(4f, 5f, 6f, 7f),
            positions = 2,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertEquals(listOf(26f, 31f), output.toList())
    }

    @Test
    fun rejectsMismatchedWeightAndBiasLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidDenseProjection(
                inputFeatures = 3,
                outputFeatures = 2,
                weights = FloatArray(5)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidDenseProjection(
                inputFeatures = 2,
                outputFeatures = 2,
                weights = FloatArray(4),
                bias = FloatArray(1)
            )
        }
    }

    @Test
    fun rejectsBadPositionCountAndInputLength() {
        val projection = S5MegpoidDenseProjection(
            inputFeatures = 2,
            outputFeatures = 1,
            weights = floatArrayOf(1f, 1f)
        )

        assertThrows(IllegalArgumentException::class.java) {
            projection.project(
                input = FloatArray(4),
                positions = 0,
                gemm = ReferenceS5MegpoidGemmKernel
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            projection.project(
                input = FloatArray(3),
                positions = 2,
                gemm = ReferenceS5MegpoidGemmKernel
            )
        }
    }
}
