package com.kmj.vea.engine

/**
 * Selects the parameter-owned frame history at FUN_180076080's +0x14 path.
 *
 * This history is distinct from the per-operator ring descriptors passed
 * through param_2. The native function appends one selected/interpolated
 * frame to its temporary frame input:
 *
 * - param_8 < 0: copy frame param_7 directly;
 * - otherwise: blend frame param_7 with frame (int)param_8 using param_9,
 *   clamped to [0,1].
 *
 * The flattened storage layout is [historyFrame][feature]. Frame indices
 * are physical history indices, not relative operator delays.
 */
object S5ApiFrameHistoryInterpolator {
    fun select(
        historyFrameMajor: FloatArray,
        frameWidth: Int,
        firstFrameIndex: Int,
        secondFrameIndex: Int? = null,
        interpolationWeight: Float = 0f
    ): FloatArray {
        require(frameWidth > 0)
        require(historyFrameMajor.isNotEmpty() &&
            historyFrameMajor.size % frameWidth == 0) {
            "Frame history storage must contain complete frame-width rows."
        }
        require(historyFrameMajor.all { it.isFinite() }) {
            "Frame history storage contains a non-finite value."
        }

        val frameCount = historyFrameMajor.size / frameWidth
        require(firstFrameIndex in 0 until frameCount) {
            "First history frame index is outside the frame buffer."
        }

        val firstOffset = checkedProduct(firstFrameIndex, frameWidth)
        if (secondFrameIndex == null) {
            return historyFrameMajor.copyOfRange(firstOffset, firstOffset + frameWidth)
        }

        require(secondFrameIndex in 0 until frameCount) {
            "Second history frame index is outside the frame buffer."
        }
        require(interpolationWeight.isFinite()) {
            "History interpolation weight must be finite."
        }

        val weight = interpolationWeight.coerceIn(0f, 1f)
        val inverseWeight = 1f - weight
        val secondOffset = checkedProduct(secondFrameIndex, frameWidth)
        val output = FloatArray(frameWidth)
        for (feature in 0 until frameWidth) {
            output[feature] =
                inverseWeight * historyFrameMajor[firstOffset + feature] +
                    weight * historyFrameMajor[secondOffset + feature]
        }
        return output
    }

    /**
     * Implements the native param_8 control convention: negative means direct
     * frame selection, non-negative means blend with frameIndex=int(param_8).
     */
    fun selectFromNativeControls(
        historyFrameMajor: FloatArray,
        frameWidth: Int,
        firstFrameIndex: Int,
        param8: Float,
        param9: Float
    ): FloatArray {
        require(param8.isFinite()) { "Native frame selector param_8 must be finite." }
        return if (param8 < 0f) {
            select(
                historyFrameMajor = historyFrameMajor,
                frameWidth = frameWidth,
                firstFrameIndex = firstFrameIndex
            )
        } else {
            require(param8.toDouble() <= Int.MAX_VALUE.toDouble()) {
                "Native frame selector param_8 exceeds the supported index range."
            }
            val secondFrameIndex = param8.toInt()
            select(
                historyFrameMajor = historyFrameMajor,
                frameWidth = frameWidth,
                firstFrameIndex = firstFrameIndex,
                secondFrameIndex = secondFrameIndex,
                interpolationWeight = param9
            )
        }
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a >= 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Frame history offset exceeds the supported FloatArray range."
        }
        return product.toInt()
    }
}
