package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidOutputTransformInputsTest {
    private fun inputs(
        outputSamples: Int = 4,
        geometryAndFftSize: Int = 8,
        floatParameter: Float = 0f,
        coreWindowLength: Int = 8,
        alternatingBinSign: Boolean = false,
        flagAt91: Boolean = false,
        shapeMode: Int = 0,
        flagAt92: Boolean = false
    ) = S5MegpoidOutputTransformInputs(
        outputSamplesAtRoot0C = outputSamples,
        geometryAndFftSizeAtRoot04 = geometryAndFftSize,
        floatParameterAtRoot10 = floatParameter,
        frameWindowCoreLengthAtRoot61C = coreWindowLength,
        alternatingBinSignAtRoot620 = alternatingBinSign,
        flagAtRoot1F74 = flagAt91,
        frameWindowShapeModeAtRoot3418 = shapeMode,
        flagAtRoot1F75 = flagAt92
    )

    @Test
    fun mapsRootScalarInputsIntoDescriptorGeometry() {
        val actual = inputs().buildDescriptorGeometry()

        assertEquals(4, actual.outputSamples)
        assertEquals(8, actual.geometry)
        assertEquals(8, actual.fftSize)
        assertEquals(1, actual.windowOffset)
        assertEquals(3, actual.overlapContributionCount)
        assertEquals(1, actual.ringSlotCount)
        assertEquals(0, actual.ringMask)
    }

    @Test
    fun createsCompleteSpectralOutputPathFromRawDescriptorInputs() {
        val input = inputs(
            floatParameter = 0.75f,
            alternatingBinSign = true,
            flagAt91 = true
        )

        val parameters = input.buildOverlapAddParameters()
        assertEquals(8, parameters.frameWindow.size)
        assertEquals(4, parameters.outputWindow.size)
        assertTrue(parameters.alternatingBinSignAt90)

        val executor = input.createSpectralOverlapAdd()
        val spectrum = FloatArray(10)
        spectrum[0] = 16f
        val output = executor.processSpectrum(spectrum)

        assertEquals(4, output.size)
        assertTrue(output.all { it.isFinite() })
    }

    @Test
    fun composesUnityFrameAndReciprocalOutputWindowForFlagAt92() {
        val parameters = inputs(
            coreWindowLength = 5,
            flagAt91 = true,
            flagAt92 = true
        ).buildOverlapAddParameters()

        parameters.frameWindow.forEach { assertEquals(1f, it, 0f) }
        parameters.outputWindow.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun retainsUnusedFloatDescriptorParameterWithoutInferringItsMeaning() {
        val input = inputs(floatParameter = 0.125f)
        assertEquals(0.125f, input.floatParameterAtRoot10, 0f)
        assertEquals(8, input.buildDescriptorGeometry().fftSize)
    }

    @Test
    fun rejectsInvalidRenderRootScalars() {
        assertThrows(IllegalArgumentException::class.java) { inputs(outputSamples = 0) }
        assertThrows(IllegalArgumentException::class.java) { inputs(geometryAndFftSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { inputs(floatParameter = Float.NaN) }
    }
}
