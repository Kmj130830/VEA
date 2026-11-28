package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.exp

class S5ApiFrameProjectionStackTest {
    private fun dense(
        input: Int,
        output: Int,
        weights: FloatArray
    ) = S5ApiFrameDenseVectorOperator(
        inputFeatures = input,
        outputFeatures = output,
        weights = weights,
        gemm = ReferenceS5MegpoidGemmKernel
    )

    private fun nativeTanh(value: Float): Float =
        S5ApiFrameMath.nativeTanh(value)

    @Test
    fun runsFirstAndHiddenProjectionBiasActivationThenFinalProjection() {
        val stack = S5ApiFrameProjectionStack(
            preOutputLayers = listOf(
                S5ApiFrameProjectionLayer(
                    dense(2, 2, floatArrayOf(1f, 0f, 0f, 1f)),
                    bias = floatArrayOf(0.5f, -0.5f)
                ),
                S5ApiFrameProjectionLayer(
                    dense(2, 2, floatArrayOf(1f, 0f, 0f, 1f)),
                    bias = floatArrayOf(0.1f, 0.2f)
                )
            ),
            finalProjection = S5ApiFrameProjectionLayer(
                dense(2, 1, floatArrayOf(2f, -1f)),
                bias = floatArrayOf(0.25f)
            ),
            hiddenActivation = S5ApiFrameHiddenActivation.TANH,
            finalTanhEnabled = true
        )

        val input = floatArrayOf(0.25f, -0.75f)
        val first = floatArrayOf(
            nativeTanh(input[0] + 0.5f),
            nativeTanh(input[1] - 0.5f)
        )
        val second = floatArrayOf(
            nativeTanh(first[0] + 0.1f),
            nativeTanh(first[1] + 0.2f)
        )
        val expected = nativeTanh(2f * second[0] - second[1] + 0.25f)

        val actual = stack.forward(input)

        assertEquals(2, stack.preOutputLayerCount)
        assertEquals(2, stack.inputFeatures)
        assertEquals(2, stack.hiddenFeatures)
        assertEquals(1, stack.outputFeatures)
        assertEquals(expected, actual[0], 1.0e-6f)
    }

    @Test
    fun leakyReluModeAppliesPointZeroOneSlopeAndOptionalUpperClamp() {
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val stack = S5ApiFrameProjectionStack(
            preOutputLayers = listOf(
                S5ApiFrameProjectionLayer(dense(3, 3, identity))
            ),
            finalProjection = S5ApiFrameProjectionLayer(dense(3, 3, identity)),
            hiddenActivation = S5ApiFrameHiddenActivation.LEAKY_RELU,
            clampLeakyReluToFour = true
        )

        assertArrayEquals(
            floatArrayOf(-0.02f, 2f, 4f),
            stack.forward(floatArrayOf(-2f, 2f, 9f)),
            0f
        )
    }

    @Test
    fun noPreOutputLayersRunsTheFinalProjectionDirectly() {
        val stack = S5ApiFrameProjectionStack(
            preOutputLayers = emptyList(),
            finalProjection = S5ApiFrameProjectionLayer(
                dense(2, 2, floatArrayOf(2f, 1f, -1f, 3f)),
                bias = floatArrayOf(0.5f, -0.25f)
            ),
            hiddenActivation = S5ApiFrameHiddenActivation.LEAKY_RELU
        )

        assertArrayEquals(
            floatArrayOf(4.5f, 4.75f),
            stack.forward(floatArrayOf(1f, 2f)),
            0f
        )
    }

    @Test
    fun omitsLayerBiasWhenNoBiasWasConfigured() {
        val stack = S5ApiFrameProjectionStack(
            preOutputLayers = listOf(
                S5ApiFrameProjectionLayer(
                    dense(1, 1, floatArrayOf(2f)),
                    bias = null
                )
            ),
            finalProjection = S5ApiFrameProjectionLayer(
                dense(1, 1, floatArrayOf(3f)),
                bias = null
            ),
            hiddenActivation = S5ApiFrameHiddenActivation.LEAKY_RELU
        )

        assertArrayEquals(floatArrayOf(12f), stack.forward(floatArrayOf(2f)), 0f)
    }

    @Test
    fun rejectsShapeMismatchAndNonFiniteInput() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameProjectionStack(
                preOutputLayers = listOf(
                    S5ApiFrameProjectionLayer(dense(2, 3, FloatArray(6) { 1f })),
                    S5ApiFrameProjectionLayer(dense(2, 2, FloatArray(4) { 1f }))
                ),
                finalProjection = S5ApiFrameProjectionLayer(dense(2, 1, FloatArray(2) { 1f })),
                hiddenActivation = S5ApiFrameHiddenActivation.TANH
            )
        }

        val stack = S5ApiFrameProjectionStack(
            preOutputLayers = listOf(
                S5ApiFrameProjectionLayer(dense(1, 1, floatArrayOf(1f)))
            ),
            finalProjection = S5ApiFrameProjectionLayer(dense(1, 1, floatArrayOf(1f))),
            hiddenActivation = S5ApiFrameHiddenActivation.TANH
        )
        assertThrows(IllegalArgumentException::class.java) {
            stack.forward(floatArrayOf(Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            stack.forward(floatArrayOf(1f, 2f))
        }
    }
}
