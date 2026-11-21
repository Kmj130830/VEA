package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidSpectralOverlapAddTest {
    private fun parameters(
        outputSamples: Int = 4,
        geometry: Int = 8,
        fftSize: Int = 8,
        windowOffset: Int = 1,
        overlapCount: Int = 2,
        ringMask: Int = 3,
        frameWindow: FloatArray = FloatArray(fftSize) { 1f },
        outputWindow: FloatArray = FloatArray(outputSamples) { 1f },
        alternatingBinSign: Boolean = false
    ) = S5MegpoidOverlapAddParameters(
        outputSamples = outputSamples,
        geometryParameterAt04 = geometry,
        fftSize = fftSize,
        windowOffsetAt10 = windowOffset,
        overlapContributionCountAt14 = overlapCount,
        ringMaskAt1C = ringMask,
        alternatingBinSignAt90 = alternatingBinSign,
        frameWindow = frameWindow,
        outputWindow = outputWindow
    )

    @Test
    fun combinesCurrentAndPreviousFramesUsingRecoveredWindowOffsets() {
        val overlap = S5MegpoidSpectralOverlapAdd(parameters())

        val first = overlap.processFrame(FloatArray(8) { (it + 1).toFloat() })
        assertEquals(listOf(1f, 2f, 3f, 4f), first.toList())

        val second = overlap.processFrame(FloatArray(8) { (it + 10).toFloat() })
        assertEquals(listOf(15f, 17f, 19f, 21f), second.toList())
    }

    @Test
    fun appliesFrameWindowBeforeRingStorageAndOutputWindowAfterSummation() {
        val overlap = S5MegpoidSpectralOverlapAdd(
            parameters(
                outputSamples = 4,
                geometry = 8,
                windowOffset = 0,
                overlapCount = 1,
                ringMask = 0,
                frameWindow = FloatArray(8) { 0.5f },
                outputWindow = FloatArray(4) { 2f }
            )
        )

        val output = overlap.processFrame(FloatArray(8) { 2f })

        output.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun processSpectrumConnectsInverseFftToOverlapAdd() {
        val overlap = S5MegpoidSpectralOverlapAdd(
            parameters(
                outputSamples = 4,
                geometry = 8,
                windowOffset = 0,
                overlapCount = 1,
                ringMask = 0
            )
        )
        val dcSpectrum = FloatArray(10)
        dcSpectrum[0] = 16f

        val output = overlap.processSpectrum(dcSpectrum)

        output.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun resetHistoryDropsPreviousFramesAndResetsRingCursor() {
        val overlap = S5MegpoidSpectralOverlapAdd(parameters())
        overlap.processFrame(FloatArray(8) { (it + 1).toFloat() })
        overlap.processFrame(FloatArray(8) { (it + 10).toFloat() })
        assertEquals(2, overlap.writeIndexForTesting)

        overlap.resetHistory()
        assertEquals(0, overlap.writeIndexForTesting)

        val output = overlap.processFrame(FloatArray(8) { (it + 20).toFloat() })
        assertEquals(listOf(20f, 21f, 22f, 23f), output.toList())
    }

    @Test
    fun rejectsInvalidRingAndWindowDescriptors() {
        assertThrows(IllegalArgumentException::class.java) {
            parameters(ringMask = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parameters(overlapCount = 5, ringMask = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parameters(frameWindow = FloatArray(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parameters(outputWindow = FloatArray(3))
        }
    }

    @Test
    fun rejectsFrameAndGeometryBoundsViolations() {
        val overlap = S5MegpoidSpectralOverlapAdd(
            parameters(outputSamples = 8, geometry = 16, windowOffset = 0, overlapCount = 1, ringMask = 0)
        )
        assertThrows(IllegalArgumentException::class.java) {
            overlap.processFrame(FloatArray(8))
        }
    }

    @Test
    fun returnsFiniteOutputForSilence() {
        val overlap = S5MegpoidSpectralOverlapAdd(parameters())
        val output = overlap.processFrame(FloatArray(8))
        assertEquals(4, output.size)
        assertTrue(output.all { it == 0f })
    }
}
