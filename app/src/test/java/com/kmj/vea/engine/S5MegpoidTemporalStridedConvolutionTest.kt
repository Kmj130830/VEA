package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidTemporalStridedConvolutionTest {
    @Test
    fun buildsCenteredStrideTwoWindowsWithZeroPaddingAndBias() {
        val convolution = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 1,
            outputFeatures = 1,
            kernelSize = 3,
            weights = floatArrayOf(1f, 2f, 3f),
            bias = floatArrayOf(5f)
        )

        assertEquals(3, convolution.outputPositions(inputPositions = 5))
        val output = convolution.project(
            input = floatArrayOf(10f, 20f, 30f, 40f, 50f),
            inputPositions = 5,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertEquals(listOf(85f, 205f, 145f), output.toList())
    }

    @Test
    fun flattensMultipleInputFeaturesBeforeTheTemporalWindowProjection() {
        val convolution = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 2,
            outputFeatures = 1,
            kernelSize = 3,
            weights = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        )

        val output = convolution.project(
            input = floatArrayOf(
                1f, 2f, 3f, 4f, 5f,
                10f, 20f, 30f, 40f, 50f
            ),
            inputPositions = 5,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        // First window: [0,1,2, 0,10,20], second: [2,3,4, 20,30,40].
        assertEquals(listOf(83f, 263f, 423f), output.toList())
    }

    @Test
    fun usesTheNativeOutputLengthFormulaForOddAndEvenKernelSizes() {
        val odd = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 1,
            outputFeatures = 1,
            kernelSize = 3,
            weights = FloatArray(3)
        )
        val even = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 1,
            outputFeatures = 1,
            kernelSize = 4,
            weights = FloatArray(4)
        )

        assertEquals(4, odd.outputPositions(7))
        assertEquals(4, odd.outputPositions(8))
        assertEquals(4, even.outputPositions(7))
        assertEquals(5, even.outputPositions(8))
    }

    @Test
    fun rejectsInvalidWeightsBiasAndInputLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidTemporalStridedConvolution(
                inputFeatures = 2,
                outputFeatures = 3,
                kernelSize = 3,
                weights = FloatArray(17)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidTemporalStridedConvolution(
                inputFeatures = 2,
                outputFeatures = 3,
                kernelSize = 3,
                weights = FloatArray(18),
                bias = FloatArray(2)
            )
        }

        val convolution = S5MegpoidTemporalStridedConvolution(
            inputFeatures = 1,
            outputFeatures = 1,
            kernelSize = 3,
            weights = FloatArray(3)
        )
        assertThrows(IllegalArgumentException::class.java) {
            convolution.project(
                input = FloatArray(4),
                inputPositions = 5,
                gemm = ReferenceS5MegpoidGemmKernel
            )
        }
    }
}
