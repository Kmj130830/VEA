package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class S5MegpoidInverseRealFftTest {
    @Test
    fun reconstructsConstantFrameFromDcBin() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[0] = size * 2f

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        assertEquals(size, output.size)
        output.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun reconstructsCosineUsingRecoveredInverseDirection() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[2] = size / 2f

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        for (sample in output.indices) {
            val expected = cos(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, output[sample], 1e-5f)
        }
    }

    @Test
    fun reconstructsSineUsingRecoveredInverseDirection() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[3] = size / 2f

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        for (sample in output.indices) {
            val expected = sin(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, output[sample], 1e-5f)
        }
    }

    @Test
    fun reconstructsNyquistAlternatingFrame() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[size] = size.toFloat()

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        output.forEachIndexed { index, value ->
            assertEquals(if (index % 2 == 0) 1f else -1f, value, 1e-6f)
        }
    }

    @Test
    fun appliesAlternatingSignToCallerSelectedParity() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[2] = size / 2f

        val output = S5MegpoidInverseRealFft(size).transform(
            interleavedSpectrum = spectrum,
            alternatingBinParity = 1
        )

        for (sample in output.indices) {
            val expected = -cos(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, output[sample], 1e-5f)
        }
    }

    @Test
    fun rejectsInvalidSpectrumAndUnsupportedFftSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidInverseRealFft(12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidInverseRealFft(8).transform(FloatArray(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidInverseRealFft(8).transform(
                FloatArray(10).also { it[0] = Float.NaN }
            )
        }
    }

    @Test
    fun returnsFiniteSamplesForMixedSpectrum() {
        val spectrum = floatArrayOf(
            1f, 5f, 2f, -1f, 0.5f, 0.75f, -2f, 1.5f, 3f, 9f
        )

        val output = S5MegpoidInverseRealFft(8).transform(spectrum)

        assertEquals(8, output.size)
        assertTrue(output.all { it.isFinite() })
    }
}
