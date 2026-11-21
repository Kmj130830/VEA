package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5MegpoidResidualTransitionRowTest {
    private class Node(
        override val row: Int, override val column: Int,
        private val calls: MutableList<Pair<Int, Int>>
    ) : S5MegpoidResidualBlockNode {
        override val channels = 1
        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            calls += row to column
            return FloatArray(residualInput.size) {
                residualInput[it] + (row * 2 + column + 1).toFloat()
            }
        }
        override fun resetHistory() = Unit
    }

    @Test
    fun runsOnlyRequestedRowAndFeedsColumnZeroOutputIntoColumnOne() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val executor = S5MegpoidResidualBlockExecutor(buildList {
            for (row in 0 until 4) for (column in 0 until 2) add(Node(row, column, calls))
        })
        val input = FloatArray(7) { 10f }
        val output = executor.runTransitionRow(2, input)
        assertEquals(listOf(2 to 0, 2 to 1), calls)
        assertEquals(List(7) { 15f }, output.toList())
        assertEquals(List(7) { 10f }, input.toList())
    }
}
