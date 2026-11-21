package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidDepthwiseHistoryStateTest {
    @Test
    fun firstChunkIsPlacedInNewestRingRowWithZeroPrefilledHistory() {
        val state = S5MegpoidDepthwiseHistoryState(channels = 2, historyRows = 7)
        val chunk = FloatArray(2 * 5) { (it + 1).toFloat() }

        val window = state.appendAndBuildWindow(chunk)

        assertEquals(5, state.currentPositions)
        assertEquals(1, state.nextWriteIndex)
        assertEquals(2 * 7 * 5, window.size)
        for (channel in 0 until 2) {
            for (row in 0 until 6) {
                val offset = (channel * 7 + row) * 5
                assertTrue(window.copyOfRange(offset, offset + 5).all { it == 0.0f })
            }
        }
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), window.slice(30 until 35))
        assertEquals(listOf(6f, 7f, 8f, 9f, 10f), window.slice(65 until 70))
    }

    @Test
    fun chunksAreReturnedOldestToNewestAcrossRingWrap() {
        val state = S5MegpoidDepthwiseHistoryState(channels = 1, historyRows = 3)
        var window = FloatArray(0)
        for (chunk in 1..4) {
            window = state.appendAndBuildWindow(
                FloatArray(2) { index -> (chunk * 10 + index).toFloat() }
            )
        }

        assertEquals(1, state.nextWriteIndex)
        assertEquals(6, window.size)
        assertEquals(listOf(20f, 21f, 30f, 31f, 40f, 41f), window.toList())
    }

    @Test
    fun changingChunkLengthResetsTheHistoryAndWriteCursor() {
        val state = S5MegpoidDepthwiseHistoryState(channels = 2, historyRows = 3)
        state.appendAndBuildWindow(FloatArray(2 * 4) { 1f })
        val window = state.appendAndBuildWindow(FloatArray(2 * 6) { 9f })

        assertEquals(6, state.currentPositions)
        assertEquals(1, state.nextWriteIndex)
        assertEquals(2 * 3 * 6, window.size)
        for (channel in 0 until 2) {
            for (row in 0 until 2) {
                val offset = (channel * 3 + row) * 6
                assertTrue(window.copyOfRange(offset, offset + 6).all { it == 0.0f })
            }
            val newestOffset = (channel * 3 + 2) * 6
            assertTrue(
                window.copyOfRange(newestOffset, newestOffset + 6).all { it == 9.0f }
            )
        }
    }

    @Test
    fun resetClearsTheRingAndAllowsA_FreshSequence() {
        val state = S5MegpoidDepthwiseHistoryState(channels = 1, historyRows = 2)
        state.appendAndBuildWindow(FloatArray(3) { 4f })
        state.reset()
        assertEquals(0, state.currentPositions)
        assertEquals(0, state.nextWriteIndex)

        val window = state.appendAndBuildWindow(FloatArray(3) { 8f })
        assertEquals(listOf(0f, 0f, 0f, 8f, 8f, 8f), window.toList())
    }
}
