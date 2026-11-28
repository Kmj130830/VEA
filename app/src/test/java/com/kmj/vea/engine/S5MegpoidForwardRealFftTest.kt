package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class S5MegpoidForwardRealFftTest {
    @Test
    fun impulseProducesFlatOneSidedSpectrum() {
        val fft = S5MegpoidForwardRealFft(8)
        val frame = FloatArray(8)
        frame[0] = 1f

        val spectrum = fft.transform(frame)

        assertEquals(10, spectrum.size)
        for (bin in 0..4) {
            assertEquals(1f, spectrum[bin * 2], 1.0e-6f)
            assertEquals(0f, spectrum[bin * 2 + 1], 1.0e-6f)
        }
    }

    @Test
    fun constantAndNyquistSignalsLandInTheirExpectedRealBins() {
        val fft = S5MegpoidForwardRealFft(8)

        val dcSpectrum = fft.transform(FloatArray(8) { 2f })
        assertEquals(16f, dcSpectrum[0], 1.0e-5f)
        for (bin in 1..4) {
            assertEquals(0f, dcSpectrum[bin * 2], 1.0e-5f)
            assertEquals(0f, dcSpectrum[bin * 2 + 1], 1.0e-5f)
        }

        val nyquistSpectrum = fft.transform(FloatArray(8) { index ->
            if ((index and 1) == 0) 0.75f else -0.75f
        })
        assertEquals(6f, nyquistSpectrum[8], 1.0e-5f)
        assertEquals(0f, nyquistSpectrum[9], 0f)
        for (bin in 0 until 4) {
            assertEquals(0f, nyquistSpectrum[bin * 2], 1.0e-5f)
            assertEquals(0f, nyquistSpectrum[bin * 2 + 1], 1.0e-5f)
        }
    }

    @Test
    fun sineAndCosineUseTheForwardNegativeExponentConvention() {
        val fft = S5MegpoidForwardRealFft(8)
        val sineFrame = FloatArray(8) { index ->
            sin(2.0 * PI * index.toDouble() / 8.0).toFloat()
        }
        val spectrum = fft.transform(sineFrame)

        // X[1] = -j * N/2 for a unit-amplitude sine at bin 1.
        assertEquals(0f, spectrum[2], 1.0e-5f)
        assertEquals(-4f, spectrum[3], 1.0e-5f)

        val cosineFrame = FloatArray(8) { index ->
            0.5f * kotlin.math.cos(2.0 * PI * 2.0 * index.toDouble() / 8.0).toFloat()
        }
        val cosineSpectrum = fft.transform(cosineFrame)
        assertEquals(2f, cosineSpectrum[4], 1.0e-5f)
        assertEquals(0f, cosineSpectrum[5], 1.0e-5f)
    }

    @Test
    fun forwardAndInverseReferenceTransformsRoundTripRealFrames() {
        val fftSize = 64
        val source = FloatArray(fftSize) { index ->
            (index % 11 - 5) * 0.125f + sin(index * 0.17).toFloat()
        }

        val spectrum = S5MegpoidForwardRealFft(fftSize).transform(source)
        val restored = S5MegpoidInverseRealFft(fftSize).transform(spectrum)

        for (index in source.indices) {
            assertEquals("sample $index", source[index], restored[index], 1.0e-4f)
        }
    }

    @Test
    fun rejectsInvalidGeometryAndNonFiniteInput() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardRealFft(12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardRealFft(8).transform(FloatArray(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidForwardRealFft(8).transform(FloatArray(8).also {
                it[3] = Float.POSITIVE_INFINITY
            })
        }
    }
}
