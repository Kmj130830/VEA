package com.kmj.vea.engine

/**
 * Stateful block history used by the S5API depthwise layer.
 *
 * Blocks are appended as [channel][position]. snapshotChronological() returns
 * [oldest history row][channel][position], matching the order assembled by
 * FUN_1800c67e0 before it invokes its depthwise kernel.
 *
 * The ring is native-owned so repeated frame blocks do not allocate or copy
 * the entire history on every append. A snapshot is only made when a caller
 * needs to pass the history into a stateless operator.
 */
class S5DepthwiseHistoryRing(
    val channels: Int,
    val positions: Int,
    val historyRows: Int
) : AutoCloseable {
    private var handle: Long

    init {
        require(channels > 0 && positions > 0 && historyRows > 0)
        handle = NativeS5TensorBridge.createHistoryRing(channels, positions, historyRows)
    }

    val blockSize: Int
        get() = Math.multiplyExact(channels, positions)

    @Synchronized
    fun append(block: FloatArray) {
        check(handle != 0L) { "S5 history ring is already closed." }
        require(block.size == blockSize) {
            "Expected $blockSize samples, received ${block.size}."
        }
        NativeS5TensorBridge.appendHistoryRing(handle, block)
    }

    /**
     * Returns [historyRows][channels][positions] in oldest-to-newest order.
     * Unwritten rows are zero-initialized, matching the model's fresh cache.
     */
    @Synchronized
    fun snapshotChronological(): FloatArray {
        check(handle != 0L) { "S5 history ring is already closed." }
        return NativeS5TensorBridge.copyHistoryRingChronological(handle)
    }

    /**
     * Returns [channel][history row][position], the exact layout accepted by
     * vea_s5_run_vtb2_c5d50_block_f32. The native ring's public snapshot is
     * intentionally kept in chronological [row][channel][position] order.
     */
    @Synchronized
    fun snapshotChannelMajor(): FloatArray {
        val chronological = snapshotChronological()
        return toChannelMajor(
            chronological,
            channels = channels,
            historyRows = historyRows,
            positions = positions
        )
    }
    @Synchronized
    fun reset() {
        check(handle != 0L) { "S5 history ring is already closed." }
        NativeS5TensorBridge.resetHistoryRing(handle)
    }

    @Synchronized
    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativeS5TensorBridge.destroyHistoryRing(current)
        }
    }
    companion object {
        /**
         * Transposes [history row][channel][position] to
         * [channel][history row][position] without changing chronological order.
         */
        internal fun toChannelMajor(
            chronological: FloatArray,
            channels: Int,
            historyRows: Int,
            positions: Int
        ): FloatArray {
            require(channels > 0 && historyRows > 0 && positions > 0)
            val expected = Math.multiplyExact(
                Math.multiplyExact(channels, historyRows),
                positions
            )
            require(chronological.size == expected) {
                "Expected $expected history values, received ${chronological.size}."
            }
            val result = FloatArray(expected)
            for (row in 0 until historyRows) {
                for (channel in 0 until channels) {
                    val source = (row * channels + channel) * positions
                    val destination = (channel * historyRows + row) * positions
                    chronological.copyInto(
                        result,
                        destinationOffset = destination,
                        startIndex = source,
                        endIndex = source + positions
                    )
                }
            }
            return result
        }
    }
}
