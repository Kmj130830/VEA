package com.kmj.vea.engine

/**
 * Stateful streaming wrapper for one recovered VTB2 c5d50 descriptor.
 *
 * Each instance owns an independent seven-frame history. The current input
 * block is appended before inference, matching FUN_1800c67e0's ring update
 * and chronological-window selection. Use one instance per descriptor;
 * do not share a history ring between residual blocks.
 *
 * This class runs only the c5d50 operator. It does not perform stage routing,
 * VTMG projections, recurrent layers, or the final spectral decoder.
 */
class S5Vtb2C5d50BlockProcessor(
    val parameters: S5Vtb2C5d50StageBlockParameters,
    val positions: Int = S5Vtb2C5d50StageBlockParameterSet.EXPECTED_HOP_SIZE,
    private val epsilon: Float = MODEL_EPSILON
) : AutoCloseable {
    private val history = S5DepthwiseHistoryRing(
        channels = S5Vtb2C5d50StageBlockParameters.CHANNEL_COUNT,
        positions = positions,
        historyRows = HISTORY_ROWS
    )
    private var closed = false

    init {
        require(positions >= HISTORY_ROWS) {
            "c5d50 requires at least $HISTORY_ROWS positions per frame block."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f)
    }

    /**
     * Processes one [channel][position] activation block and advances this
     * descriptor's persistent seven-frame convolution history.
     */
    @Synchronized
    fun process(input: FloatArray): FloatArray {
        check(!closed) { "c5d50 block processor is already closed." }
        val expected = Math.multiplyExact(
            S5Vtb2C5d50StageBlockParameters.CHANNEL_COUNT,
            positions
        )
        require(input.size == expected) {
            "Expected $expected c5d50 input values, received ${input.size}."
        }

        history.append(input)
        val modelHistory = history.snapshotChannelMajor()
        return NativeS5TensorBridge.runVtb2C5d50StageBlock(
            historyRows = modelHistory,
            parameters = parameters,
            residualInput = input,
            positions = positions,
            epsilon = epsilon
        )
    }

    /** Clears only this block's temporal state. */
    @Synchronized
    fun reset() {
        check(!closed) { "c5d50 block processor is already closed." }
        history.reset()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            history.close()
        }
    }

    companion object {
        const val HISTORY_ROWS = 7
        const val MODEL_EPSILON = 1.0e-6f
    }
}
