package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidAlternateTemporalProjectionTest {
    @Test
    fun doublesPositionsAndBuildsLeftCentreRightTemporalWindows() {
        val stage = S5MegpoidAlternateTemporalProjection(
            inputFeatures = 1,
            outputFeatures = 3,
            // Three identity rows select left, centre and right taps.
            weights = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            ),
            bias = floatArrayOf(0f, 0f, 0f)
        )

        assertEquals(6, stage.outputPositions(3))
        val output = stage.project(
            input = floatArrayOf(2f, 4f, 6f),
            inputPositions = 3,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        // 2x interpolation: [2, 2.5, 3.5, 4.5, 5.5, 6]
        assertEquals(
            listOf(0f, 2f, 2.5f, 3.5f, 4.5f, 5.5f),
            output.copyOfRange(0, 6).toList()
        )
        assertEquals(
            listOf(2f, 2.5f, 3.5f, 4.5f, 5.5f, 6f),
            output.copyOfRange(6, 12).toList()
        )
        assertEquals(
            listOf(2.5f, 3.5f, 4.5f, 5.5f, 6f, 0f),
            output.copyOfRange(12, 18).toList()
        )
    }

    @Test
    fun usesEdgeExtensionForInterpolationAndZeroPaddingForTemporalWindows() {
        val stage = S5MegpoidAlternateTemporalProjection(
            inputFeatures = 1,
            outputFeatures = 3,
            weights = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            ),
            bias = floatArrayOf(0f, 0f, 0f)
        )

        val output = stage.project(
            input = floatArrayOf(5f),
            inputPositions = 1,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertEquals(listOf(0f, 5f), output.copyOfRange(0, 2).toList())
        assertEquals(listOf(5f, 5f), output.copyOfRange(2, 4).toList())
        assertEquals(listOf(5f, 0f), output.copyOfRange(4, 6).toList())
    }

    @Test
    fun flattensKernelTapsPerInputChannelAndAddsOutputBias() {
        val stage = S5MegpoidAlternateTemporalProjection(
            inputFeatures = 2,
            outputFeatures = 1,
            // [output][inputChannel][left, centre, right]
            weights = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
            bias = floatArrayOf(7f)
        )

        val output = stage.project(
            input = floatArrayOf(1f, 10f),
            inputPositions = 1,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertEquals(listOf(122f, 100f), output.toList())
    }

    @Test
    fun rejectsInvalidDimensionsWeightsBiasAndNonFiniteInput() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidAlternateTemporalProjection(0, 1, FloatArray(0), floatArrayOf(0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidAlternateTemporalProjection(1, 1, FloatArray(2), floatArrayOf(0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidAlternateTemporalProjection(1, 1, FloatArray(3), FloatArray(0))
        }

        val stage = S5MegpoidAlternateTemporalProjection(
            1, 1, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f)
        )
        assertThrows(IllegalArgumentException::class.java) {
            stage.project(floatArrayOf(Float.NaN), 1, ReferenceS5MegpoidGemmKernel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            stage.project(floatArrayOf(1f), 0, ReferenceS5MegpoidGemmKernel)
        }
    }
}
