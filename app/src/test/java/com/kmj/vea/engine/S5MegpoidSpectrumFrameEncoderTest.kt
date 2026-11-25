package com.kmj.vea.engine

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidSpectrumFrameEncoderTest {
    private val shape = S5MegpoidSpectrumOutputShape(
        fftSize = 8,
        complexBinCount = 5
    )

    @Test
    fun interleavesTwoDirectComplexFeaturePlanesWithoutChangingValues() {
        val real = floatArrayOf(1f, -2f, 3.5f, 0f, 8f)
        val imaginary = floatArrayOf(-1f, 2f, 0.5f, 4f, -8f)

        val output = S5MegpoidSpectrumFrameEncoder.encode(
            firstFeaturePlane = real,
            secondFeaturePlane = imaginary,
            shape = shape,
            encoding = S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX
        )

        assertEquals(
            listOf(1f, -1f, -2f, 2f, 3.5f, 0.5f, 0f, 4f, 8f, -8f),
            output.toList()
        )
    }

    @Test
    fun convertsLogMagnitudeAndPhaseToComplexPairs() {
        val logMagnitude = floatArrayOf(ln(2f), ln(3f), 0f, ln(0.5f), ln(4f))
        val phase = floatArrayOf(0f, (PI / 2.0).toFloat(), PI.toFloat(), (-PI / 2.0).toFloat(), 0.25f)

        val output = S5MegpoidSpectrumFrameEncoder.encode(
            firstFeaturePlane = logMagnitude,
            secondFeaturePlane = phase,
            shape = shape,
            encoding = S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
        )

        assertEquals(2f, output[0], 0.00001f)
        assertEquals(0f, output[1], 0.00001f)
        assertEquals(0f, output[2], 0.00002f)
        assertEquals(3f, output[3], 0.00002f)
        assertEquals(-1f, output[4], 0.00001f)
        assertEquals(0f, output[5], 0.00002f)
        assertEquals(0f, output[6], 0.00002f)
        assertEquals(-0.5f, output[7], 0.00002f)
        assertEquals(4f * kotlin.math.cos(0.25f), output[8], 0.00001f)
        assertEquals(4f * kotlin.math.sin(0.25f), output[9], 0.00001f)
    }

    @Test
    fun capsExponentiatedMagnitudeAtOneHundredLikeTheNativeOutputPath() {
        val output = S5MegpoidSpectrumFrameEncoder.encode(
            firstFeaturePlane = floatArrayOf(ln(200f), ln(0.5f), 0f, 0f, 0f),
            secondFeaturePlane = FloatArray(5),
            shape = shape,
            encoding = S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
        )

        assertEquals(100f, output[0], 0f)
        assertEquals(0f, output[1], 0f)
        assertEquals(0.5f, output[2], 0.00001f)
        assertEquals(0f, output[3], 0.00001f)
    }

    @Test
    fun supportsVeryNegativeLogMagnitudeThroughFloatUnderflowToZero() {
        val output = S5MegpoidSpectrumFrameEncoder.encode(
            firstFeaturePlane = floatArrayOf(-1000f, -1000f, -1000f, -1000f, -1000f),
            secondFeaturePlane = FloatArray(5),
            shape = shape,
            encoding = S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
        )
        assertTrueAllZeros(output)
    }

    @Test
    fun rejectsWrongPlaneLengthsAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSpectrumFrameEncoder.encode(
                FloatArray(4), FloatArray(5), shape,
                S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSpectrumFrameEncoder.encode(
                FloatArray(5), FloatArray(4), shape,
                S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSpectrumFrameEncoder.encode(
                FloatArray(5).also { it[2] = Float.NaN },
                FloatArray(5), shape,
                S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSpectrumFrameEncoder.encode(
                FloatArray(5),
                FloatArray(5).also { it[3] = Float.POSITIVE_INFINITY },
                shape,
                S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
            )
        }
    }

    private fun assertTrueAllZeros(values: FloatArray) {
        assertEquals(10, values.size)
        values.forEach { assertEquals(0f, it, 0f) }
    }
}
