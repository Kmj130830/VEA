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
    fun reconstructsCosineUsingS5SpectrumConvention() {
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
    fun reconstructsSineFromConjugatedS5ForwardSpectrum() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        // S5API conjugates the imaginary part of every interior forward bin.
        spectrum[3] = -size / 2f

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        for (sample in output.indices) {
            val expected = sin(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, output[sample], 1e-5f)
        }
    }

    @Test
    fun alternatingBinFlagNegatesBothComponentsOfOddBins() {
        val size = 8
        val cosineSpectrum = FloatArray((size / 2 + 1) * 2)
        cosineSpectrum[2] = size / 2f

        val negativeCosine = S5MegpoidInverseRealFft(size).transform(
            interleavedSpectrum = cosineSpectrum,
            alternatingBinSign = true
        )

        for (sample in negativeCosine.indices) {
            val expected = -cos(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, negativeCosine[sample], 1e-5f)
        }

        val sineSpectrum = FloatArray((size / 2 + 1) * 2)
        sineSpectrum[3] = -size / 2f
        val negativeSine = S5MegpoidInverseRealFft(size).transform(
            interleavedSpectrum = sineSpectrum,
            alternatingBinSign = true
        )

        for (sample in negativeSine.indices) {
            val expected = -sin(2.0 * PI * sample / size).toFloat()
            assertEquals(expected, negativeSine[sample], 1e-5f)
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
    fun ignoresImaginaryDcAndNyquistValues() {
        val size = 8
        val spectrum = FloatArray((size / 2 + 1) * 2)
        spectrum[1] = 100f
        spectrum[size + 1] = -50f

        val output = S5MegpoidInverseRealFft(size).transform(spectrum)

        output.forEach { assertEquals(0f, it, 0f) }
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

    @Test
    fun matchesDirectRealInverseForArbitrarySpectraAcrossRadix2Sizes() {
        val random = kotlin.random.Random(0x5A5A)
        for (size in listOf(2, 4, 8, 16, 32)) {
            val bins = size / 2 + 1
            val spectrum = FloatArray(bins * 2) {
                random.nextFloat() * 8f - 4f
            }
            val useAlternatingSign = size % 4 == 0
            val actual = S5MegpoidInverseRealFft(size).transform(
                interleavedSpectrum = spectrum,
                alternatingBinSign = useAlternatingSign
            )

            for (sample in 0 until size) {
                var sum = spectrum[0].toDouble()
                val nyquistSign = if (sample % 2 == 0) 1.0 else -1.0
                sum += spectrum[size].toDouble() * nyquistSign
                for (bin in 1 until size / 2) {
                    val sign = if (useAlternatingSign && bin % 2 == 1) -1.0 else 1.0
                    val real = spectrum[bin * 2] * sign
                    val imaginary = spectrum[bin * 2 + 1] * sign
                    val angle = 2.0 * Math.PI * bin * sample / size
                    sum += 2.0 * (real * kotlin.math.cos(angle) -
                        imaginary * kotlin.math.sin(angle))
                }
                val expected = (sum / size).toFloat()
                assertEquals("N=$size sample=$sample", expected, actual[sample], 2e-5f)
            }
        }
    }

}
