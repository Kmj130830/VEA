package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidFrameWindowBuilderTest {
    @Test
    fun buildsSymmetricHannWhenCoreLengthIsNotInsideTotalLength() {
        val actual = S5MegpoidFrameWindowBuilder.build(
            totalLength = 5,
            coreLength = 5,
            shapeMode = 1
        )

        assertEquals(listOf(0f, 0.5f, 1f, 0.5f, 0f), actual.toList().map {
            if (kotlin.math.abs(it) < 1e-6f) 0f else it
        })
    }

    @Test
    fun buildsCenteredPeriodicHannWithZeroPadding() {
        val actual = S5MegpoidFrameWindowBuilder.build(
            totalLength = 7,
            coreLength = 3,
            shapeMode = 0
        )

        assertEquals(7, actual.size)
        assertEquals(0f, actual[0], 0f)
        assertEquals(0f, actual[1], 0f)
        assertEquals(0f, actual[2], 1e-6f)
        assertEquals(0.75f, actual[3], 1e-5f)
        assertEquals(0.75f, actual[4], 1e-5f)
        assertEquals(0f, actual[5], 0f)
        assertEquals(0f, actual[6], 0f)
    }

    @Test
    fun buildsFourTermBlackmanHarrisInCenteredWindowMode() {
        val actual = S5MegpoidFrameWindowBuilder.build(
            totalLength = 5,
            coreLength = 3,
            shapeMode = S5MegpoidFrameWindowBuilder.BLACKMAN_HARRIS_MODE
        )

        assertEquals(0f, actual[0], 0f)
        assertEquals(0.00006f, actual[1], 1e-5f)
        assertEquals(0.520575f, actual[2], 1e-5f)
        assertEquals(0.520575f, actual[3], 1e-5f)
        assertEquals(0f, actual[4], 0f)
    }

    @Test
    fun unsupportedShapeModeFallsBackToPeriodicHannInCenteredBranch() {
        val actual = S5MegpoidFrameWindowBuilder.build(7, 3, 9)
        assertEquals(0.75f, actual[3], 1e-5f)
    }

    @Test
    fun zeroOrOversizedCoreUsesSymmetricFullWindow() {
        val zeroCore = S5MegpoidFrameWindowBuilder.build(5, 0, 1)
        val oversizedCore = S5MegpoidFrameWindowBuilder.build(5, 9, 1)
        assertEquals(zeroCore.toList(), oversizedCore.toList())
        assertEquals(0f, zeroCore.first(), 0f)
        assertEquals(0f, zeroCore.last(), 1e-6f)
    }

    @Test
    fun rejectsWindowLengthBelowTwo() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidFrameWindowBuilder.build(1, 0, 0)
        }
    }

    @Test
    fun allGeneratedCoefficientsAreFinite() {
        for (mode in listOf(0, 1, 2, 9)) {
            val actual = S5MegpoidFrameWindowBuilder.build(128, 96, mode)
            assertTrue(actual.all { it.isFinite() })
        }
    }
}
