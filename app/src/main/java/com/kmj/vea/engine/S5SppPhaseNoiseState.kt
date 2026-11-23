package com.kmj.vea.engine

/**
 * Stateful wrapper for FUN_180135490 across consecutive SPP frames.
 *
 * The four seed words are supplied by the recovered renderer/model state;
 * this class does not invent a default seed. Calls are synchronized so a
 * single state stream cannot be advanced concurrently out of frame order.
 */
class S5SppPhaseNoiseState(
    seedState: IntArray,
    private val generator: (FloatArray, IntArray, Int) -> FloatArray =
        NativeS5TensorBridge::generateSppPhaseNoise
) {
    private val state = seedState.copyOf().also {
        require(it.size == STATE_WORD_COUNT) {
            "SPP phase-noise state requires exactly four 32-bit words."
        }
    }

    /** Generates one complex spectrum and advances this stream's RNG state. */
    @Synchronized
    fun generate(magnitudes: FloatArray, edgeMode: Int = 0): FloatArray {
        require(edgeMode in 0..255)
        require(magnitudes.all { it.isFinite() })
        return generator(magnitudes, state, edgeMode)
    }

    /** Returns a detached copy of the four-word state for checkpointing. */
    @Synchronized
    fun snapshot(): IntArray = state.copyOf()

    /** Restores the stream from a previously saved four-word state. */
    @Synchronized
    fun restore(savedState: IntArray) {
        require(savedState.size == STATE_WORD_COUNT) {
            "SPP phase-noise state requires exactly four 32-bit words."
        }
        savedState.copyInto(state)
    }

    companion object {
        const val STATE_WORD_COUNT = 4
    }
}
