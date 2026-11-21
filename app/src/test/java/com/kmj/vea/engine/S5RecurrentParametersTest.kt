package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S5RecurrentParametersTest {
    @Test
    fun exposesRecoveredThreeGateMatrixShapes() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 5,
            hiddenFeatures = 7,
            layerCount = 1,
            bidirectional = false,
            hasBias = true
        )

        val specs = S5RecurrentParameterLoader.specifications(layout)

        assertEquals(
            listOf(
                "rnn.weight_ih_l0",
                "rnn.weight_hh_l0",
                "rnn.bias_ih_l0",
                "rnn.bias_hh_l0"
            ),
            specs.map { it.name }
        )
        assertEquals(listOf(21, 5), listOf(specs[0].rows, specs[0].columns))
        assertEquals(listOf(21, 7), listOf(specs[1].rows, specs[1].columns))
        assertEquals(21, specs[2].elementCount)
        assertEquals(21, specs[3].elementCount)
    }

    @Test
    fun doublesLaterLayerInputWidthForBidirectionalStack() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 4,
            hiddenFeatures = 3,
            layerCount = 2,
            bidirectional = true,
            hasBias = true
        )

        val specs = S5RecurrentParameterLoader.specifications(layout)
        val layerOne = specs.filter { it.name.contains("_l1") }

        assertEquals(8, layerOne.size)
        assertEquals(
            listOf(
                "rnn.weight_ih_l1",
                "rnn.weight_hh_l1",
                "rnn.bias_ih_l1",
                "rnn.bias_hh_l1",
                "rnn.weight_ih_l1_reverse",
                "rnn.weight_hh_l1_reverse",
                "rnn.bias_ih_l1_reverse",
                "rnn.bias_hh_l1_reverse"
            ),
            layerOne.map { it.name }
        )
        assertEquals(6, layerOne.first().rows)
        assertEquals(6, layerOne.first().columns)
        assertEquals(6, layerOne[4].rows)
        assertEquals(6, layerOne[4].columns)
    }

    @Test
    fun validatesAndCopiesDecodedForwardAndReverseTensors() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 2,
            hiddenFeatures = 2,
            layerCount = 1,
            bidirectional = true,
            hasBias = true
        )
        val source = S5RecurrentParameterLoader.specifications(layout)
            .associate { it.name to FloatArray(it.elementCount) { index -> index.toFloat() } }

        val parsed = S5RecurrentParameterLoader.validate(source, layout)

        assertEquals(2, parsed.size)
        assertFalse(parsed[0].reverseDirection)
        assertTrue(parsed[1].reverseDirection)
        assertEquals(2, parsed[0].inputFeatures)
        assertEquals(6, parsed[0].inputWeights.size)
        assertEquals(12, parsed[0].recurrentWeights.size)
        assertEquals(6, parsed[1].inputBias?.size)
        assertEquals(6, parsed[1].recurrentBias?.size)

        source["rnn.weight_ih_l0"]!![0] = 99.0f
        assertEquals(0.0f, parsed[0].inputWeights[0])
    }

    @Test
    fun biasDisabledLayoutDoesNotRequireBiasTensors() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 3,
            hiddenFeatures = 2,
            layerCount = 1,
            bidirectional = false,
            hasBias = false
        )
        val source = S5RecurrentParameterLoader.specifications(layout)
            .associate { it.name to FloatArray(it.elementCount) }

        val parsed = S5RecurrentParameterLoader.validate(source, layout)

        assertEquals(2, source.size)
        assertNull(parsed.single().inputBias)
        assertNull(parsed.single().recurrentBias)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTensor() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 3,
            hiddenFeatures = 2,
            layerCount = 1,
            bidirectional = false,
            hasBias = false
        )
        S5RecurrentParameterLoader.validate(emptyMap(), layout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteWeights() {
        val layout = S5RecurrentLayerLayout(
            inputFeatures = 1,
            hiddenFeatures = 1,
            layerCount = 1,
            bidirectional = false,
            hasBias = false
        )
        val source = S5RecurrentParameterLoader.specifications(layout)
            .associate { it.name to FloatArray(it.elementCount) }
        source["rnn.weight_hh_l0"]!![0] = Float.NaN

        S5RecurrentParameterLoader.validate(source, layout)
    }
}
