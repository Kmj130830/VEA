package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidOutputWindowBuilderTest {
    private fun buildBase(
        frameWindow: FloatArray = FloatArray(8) { 1f },
        outputSamples: Int = 4,
        geometry: Int = 8,
        fftSize: Int = 8,
        windowOffset: Int = 1,
        overlapCount: Int = 3,
        flagAt91: Boolean = false,
        flagAt92: Boolean = false
    ) = S5MegpoidOutputWindowBuilder.build(
        frameWindow = frameWindow,
        outputSamples = outputSamples,
        geometry = geometry,
        fftSize = fftSize,
        windowOffset = windowOffset,
        overlapContributionCount = overlapCount,
        flagAt91 = flagAt91,
        flagAt92 = flagAt92
    )

    @Test
    fun defaultBranchUsesReciprocalOverlapSumOfSquaredFrameWindow() {
        val frame = FloatArray(8) { 0.5f }

        val result = buildBase(frameWindow = frame)

        assertEquals(frame.toList(), result.frameWindow.toList())
        result.outputWindow.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun flagAt92BranchUsesReciprocalOverlapSumOfFrameWindow() {
        val frame = FloatArray(8) { 0.25f }

        val result = buildBase(frameWindow = frame, flagAt92 = true)

        assertEquals(frame.toList(), result.frameWindow.toList())
        result.outputWindow.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun flagAt91BranchBuildsRaisedCosineAndUpdatesFrameWindow() {
        val original = FloatArray(8) { 1f }

        val result = buildBase(frameWindow = original, flagAt91 = true)

        val expected = floatArrayOf(0f, 0.1464466f, 0.5f, 0.8535534f, 1f, 0.8535534f, 0.5f, 0.1464466f)
        expected.indices.forEach { index ->
            assertEquals(expected[index], result.frameWindow[index], 1e-5f)
        }
        result.outputWindow.forEach { assertEquals(1f, it, 1e-5f) }
        assertEquals(1f, original[4], 0f)
    }

    @Test
    fun flagAt92TakesPrecedenceWhenBothFlagsAreSet() {
        val result = buildBase(
            frameWindow = FloatArray(8) { 0.25f },
            flagAt91 = true,
            flagAt92 = true
        )

        result.frameWindow.forEach { assertEquals(0.25f, it, 0f) }
        result.outputWindow.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun rejectsGeometryNotMatchingMegpoidFrameBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            buildBase(geometry = 16, fftSize = 8, frameWindow = FloatArray(16))
        }
    }

    @Test
    fun rejectsNonFiniteFrameWindowAndBadShape() {
        assertThrows(IllegalArgumentException::class.java) {
            buildBase(frameWindow = FloatArray(8) { if (it == 3) Float.NaN else 1f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildBase(frameWindow = FloatArray(7))
        }
    }

    @Test
    fun outputWindowCoefficientsAreFiniteForGeneratedHannFrame() {
        val frame = S5MegpoidFrameWindowBuilder.build(
            totalLength = 8,
            coreLength = 8,
            shapeMode = 0
        )
        val result = buildBase(frameWindow = frame, windowOffset = 1, overlapCount = 3)
        assertTrue(result.outputWindow.all { it.isFinite() && it > 0f })
    }
}
