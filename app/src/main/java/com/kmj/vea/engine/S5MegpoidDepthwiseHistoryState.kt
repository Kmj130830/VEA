package com.kmj.vea.engine

/**
 * Stateful input ring used by S5API FUN_1800c67e0.
 *
 * The original helper stores one [channel][position] feature chunk per slot.
 * After copying the new chunk, it increments/wraps the write index and
 * exposes the retained slots starting at that index (oldest first).
 *
 * This class ports only that state/buffer contract. The depthwise arithmetic
 * remains in the separately tested native reference kernel.
 */
class S5MegpoidDepthwiseHistoryState(
    val channels: Int,
    val historyRows: Int = S5MegpoidResidualBlockShape.KERNEL_WIDTH
) {
    private var positions = 0
    private var nextWriteSlot = 0
    private var history = FloatArray(0)

    init {
        require(channels > 0) { "Channel count must be positive." }
        require(historyRows > 0) { "History ring must contain at least one slot." }
    }

    val currentPositions: Int
        @Synchronized get() = positions

    val nextWriteIndex: Int
        @Synchronized get() = nextWriteSlot

    /**
     * Copies a contiguous [channel][position] chunk into the ring and returns
     * a snapshot in [channel][historyRow][position] order, oldest to newest.
     *
     * A positions change mirrors the native helper's shape-change branch:
     * allocate a zero-filled ring and reset the write cursor before storing
     * the current chunk.
     */
    @Synchronized
    fun appendAndBuildWindow(chunk: FloatArray): FloatArray {
        require(chunk.isNotEmpty() && chunk.size % channels == 0) {
            "Input chunk must contain a non-empty, whole number of channel rows."
        }
        val newPositions = chunk.size / channels
        val chunkElements = checkedProduct(channels, newPositions)

        if (positions != newPositions) {
            val historyElements = checkedProduct(chunkElements, historyRows)
            history = FloatArray(historyElements)
            positions = newPositions
            nextWriteSlot = 0
        }

        // The write cursor is legitimately zero for the first slot.
        // The enclosing history allocation already proved this product fits.
        val slotStart = nextWriteSlot * chunkElements
        System.arraycopy(chunk, 0, history, slotStart, chunkElements)

        nextWriteSlot++
        if (nextWriteSlot == historyRows) nextWriteSlot = 0

        val windowElements = checkedProduct(channels, historyRows, positions)
        val window = FloatArray(windowElements)
        for (channel in 0 until channels) {
            for (row in 0 until historyRows) {
                val ringSlot = (nextWriteSlot + row) % historyRows
                val sourceOffset = ringSlot * chunkElements + channel * positions
                val destinationOffset = (channel * historyRows + row) * positions
                System.arraycopy(history, sourceOffset, window, destinationOffset, positions)
            }
        }
        return window
    }

    @Synchronized
    fun reset() {
        history = FloatArray(0)
        positions = 0
        nextWriteSlot = 0
    }

    private fun checkedProduct(vararg factors: Int): Int {
        var product = 1L
        for (factor in factors) {
            require(factor > 0)
            require(product <= Int.MAX_VALUE.toLong() / factor.toLong()) {
                "History ring size exceeds the supported Kotlin array limit."
            }
            product *= factor.toLong()
        }
        return product.toInt()
    }
}
