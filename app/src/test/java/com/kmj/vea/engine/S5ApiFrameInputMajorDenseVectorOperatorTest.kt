package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ApiFrameInputMajorDenseVectorOperatorTest {
    @Test
    fun scalarPathReadsWeightsInNativeInputMajorOrder() {
        val operator = S5ApiFrameInputMajorDenseVectorOperator(
            inputFeatures = 2,
            outputFeatures = 3,
            weightsInputMajor = floatArrayOf(
                1f, 4f, 7f,
                2f, 5f, 8f
            )
        )

        assertArrayEquals(
            floatArrayOf(8f, 23f, 38f),
            operator.project(floatArrayOf(2f, 3f)),
            0f
        )
    }

    @Test
    fun alignedKernelIsUsedOnlyWhenOutputWidthIsMultipleOfSixtyFour() {
        var called = false
        val operator = S5ApiFrameInputMajorDenseVectorOperator(
            inputFeatures = 2,
            outputFeatures = 64,
            weightsInputMajor = FloatArray(128) { it.toFloat() },
            alignedKernel = S5ApiFrameAlignedDenseKernel {
                    weightsInputMajor, input, output, inputFeatures, outputFeatures ->
                called = true
                assertEquals(128, weightsInputMajor.size)
                assertEquals(2, inputFeatures)
                assertEquals(64, outputFeatures)
                assertArrayEquals(floatArrayOf(1f, 2f), input, 0f)
                // Simulate the native +0x398 kernel writing the full output.
                output.indices.forEach { index -> output[index] = index.toFloat() }
            }
        )

        val result = operator.project(floatArrayOf(1f, 2f))

        assertTrue(called)
        assertArrayEquals(FloatArray(64) { it.toFloat() }, result, 0f)
    }

    @Test
    fun scalarPathDoesNotCallAlignedKernelForNonAlignedOutputWidth() {
        var called = false
        val operator = S5ApiFrameInputMajorDenseVectorOperator(
            inputFeatures = 2,
            outputFeatures = 3,
            weightsInputMajor = floatArrayOf(
                1f, 4f, 7f,
                2f, 5f, 8f
            ),
            alignedKernel = S5ApiFrameAlignedDenseKernel { _, _, _, _, _ ->
                called = true
            }
        )

        assertArrayEquals(
            floatArrayOf(8f, 23f, 38f),
            operator.project(floatArrayOf(2f, 3f)),
            0f
        )
        assertFalse(called)
    }

    @Test
    fun validatesInputAndTensorGeometry() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameInputMajorDenseVectorOperator(
                inputFeatures = 2,
                outputFeatures = 3,
                weightsInputMajor = FloatArray(5)
            )
        }
        val operator = S5ApiFrameInputMajorDenseVectorOperator(
            inputFeatures = 2,
            outputFeatures = 3,
            weightsInputMajor = FloatArray(6) { 1f }
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            operator.project(floatArrayOf(1f))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            operator.project(floatArrayOf(1f, Float.NaN))
        }
    }
}
