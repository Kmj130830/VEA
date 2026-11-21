package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidCompiledRuntimeBlockHistoryTest {
    private fun block(row: Int, column: Int) = S5MegpoidCompiledRuntimeBlock(
        row = row,
        column = column,
        channels = 1,
        expansionMultiplier = 1,
        expandedFeatures = 1,
        depthwiseWeightsChannelMajor = FloatArray(49),
        nativeRecordDepthwiseFilter = FloatArray(49),
        expansionWeights = floatArrayOf(1f),
        expansionGamma = floatArrayOf(0f),
        expansionBeta = floatArrayOf(0f),
        projectionWeights = floatArrayOf(1f),
        projectionBias = floatArrayOf(0f),
        residualScale = floatArrayOf(1f)
    )

    @Test
    fun eachCompiledBlockKeepsItsOwnHistoryRing() {
        val first = block(0, 0)
        val second = block(0, 1)

        val firstWindow = first.buildHistoryWindow(FloatArray(7) { 11f })
        val secondWindow = second.buildHistoryWindow(FloatArray(7) { 22f })

        assertTrue(firstWindow.take(6 * 7).all { it == 0f })
        assertEquals(List(7) { 11f }, firstWindow.takeLast(7))
        assertTrue(secondWindow.take(6 * 7).all { it == 0f })
        assertEquals(List(7) { 22f }, secondWindow.takeLast(7))

        first.buildHistoryWindow(FloatArray(7) { 33f })
        val secondAgain = second.buildHistoryWindow(FloatArray(7) { 44f })
        // Oldest-to-newest: five zero rows, then the previous chunk, then the new chunk.
        assertEquals(List(7) { 22f }, secondAgain.toList().subList(5 * 7, 6 * 7))
        assertEquals(List(7) { 44f }, secondAgain.takeLast(7))
    }

    @Test
    fun resetHistoryAffectsOnlyTheSelectedBlock() {
        val first = block(1, 0)
        val second = block(1, 1)
        first.buildHistoryWindow(FloatArray(7) { 3f })
        second.buildHistoryWindow(FloatArray(7) { 8f })

        first.resetHistory()
        val firstAfterReset = first.buildHistoryWindow(FloatArray(7) { 5f })
        val secondAfterFirstReset = second.buildHistoryWindow(FloatArray(7) { 9f })

        assertTrue(firstAfterReset.take(6 * 7).all { it == 0f })
        assertEquals(List(7) { 5f }, firstAfterReset.takeLast(7))
        assertEquals(
            List(7) { 8f },
            secondAfterFirstReset.toList().subList(5 * 7, 6 * 7)
        )
        assertEquals(List(7) { 9f }, secondAfterFirstReset.takeLast(7))
    }
}
