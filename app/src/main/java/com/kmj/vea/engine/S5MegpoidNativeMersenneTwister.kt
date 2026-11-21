package com.kmj.vea.engine

/**
 * The 624-word MT19937 stream recovered from S5API.dll FUN_180026fd0.
 *
 * FUN_180127a40 initializes the state with
 *   state[i] = 1812433253 * (state[i-1] xor (state[i-1] ushr 30)) + i
 * and FUN_180026fd0 applies the standard MT19937 twist and temper sequence.
 * The decompiler's pre-shift masks (0xff3a58ad and 0xffffdf8c) are equivalent
 * to the conventional post-shift masks (0x9d2c5680 and 0xefc60000).
 *
 * This is a source-level reconstruction of the observed algorithm; parity
 * against a live S5API invocation has not yet been measured.
 */
internal class S5MegpoidNativeMersenneTwister(seed: Int = DEFAULT_SEED) {
    private val state = IntArray(STATE_WORDS)
    private var index = STATE_WORDS

    init {
        val effectiveSeed = if (seed == 0) DEFAULT_SEED else seed
        state[0] = effectiveSeed
        for (i in 1 until STATE_WORDS) {
            val previous = state[i - 1]
            state[i] = 1812433253 * (previous xor (previous ushr 30)) + i
        }
    }

    /** Returns the next unsigned 32-bit MT output represented as an Int bit pattern. */
    fun nextUInt32(): Int {
        if (index >= STATE_WORDS) twist()

        var value = state[index++]
        value = value xor (value ushr 11)
        value = value xor ((value shl 7) and TEMPER_MASK_B)
        value = value xor ((value shl 15) and TEMPER_MASK_C)
        value = value xor (value ushr 18)
        return value
    }

    /**
     * Matches FUN_180127720's conversion: discard the low eight bits, map the
     * remaining 24 bits to [0,1), then map that value to [-1,1).
     */
    fun nextUniformMinusOneToOne(): Float {
        val unit = (nextUInt32() ushr 8).toFloat() * UINT24_SCALE
        return (unit + unit) - 1.0f
    }

    private fun twist() {
        for (i in 0 until STATE_WORDS) {
            val next = state[(i + 1) % STATE_WORDS]
            val combined = (state[i] and UPPER_MASK) or (next and LOWER_MASK)
            val matrix = if ((combined and 1) != 0) MATRIX_A else 0
            state[i] = state[(i + PERIOD) % STATE_WORDS] xor
                (combined ushr 1) xor matrix
        }
        index = 0
    }

    internal companion object {
        const val DEFAULT_SEED = 42
        private const val STATE_WORDS = 624
        private const val PERIOD = 397
        private const val UPPER_MASK = -2_147_483_648
        private const val LOWER_MASK = 0x7fffffff
        private const val MATRIX_A = -1_727_483_681
        private const val TEMPER_MASK_B = -1_658_038_656
        private const val TEMPER_MASK_C = -272_236_544
        private const val UINT24_SCALE = 5.9604645e-8f
    }
}
