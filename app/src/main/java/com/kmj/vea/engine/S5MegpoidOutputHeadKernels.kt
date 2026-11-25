package com.kmj.vea.engine

/**
 * Portable reference kernels for the S5API post-residual output path.
 *
 * FUN_1800d52b0 and FUN_1800d5610 contain two CPU-dispatched implementations
 * of the same complex modulation/accumulation equation. FUN_1800cea30 and
 * FUN_1800ceba0 likewise implement the same in-place affine vector operation
 * with different vectorization strategies.
 *
 * The functions here implement the common scalar equations. They are not a
 * full port of the per-part interpolation, carrier-history scheduler, or
 * render-state dispatch logic.
 */
object S5MegpoidOutputHeadKernels {
    /**
     * Accumulates a gain-scaled two-plane feature pair into an interleaved
     * complex output buffer.
     *
     * Per element:
     *
     *   x = gain[i] * firstFeaturePlane[i]
     *   y = gain[i] * secondFeaturePlane[i]
     *   out.real += modulationReal[i] * x - modulationImag[i] * y
     *   out.imag += modulationImag[i] * x + modulationReal[i] * y
     *
     * This is the scalar equation visible in the tails of both native
     * complex modulation helpers.
     */
    fun accumulateComplexModulatedOutput(
        outputInterleaved: FloatArray,
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray,
        gain: FloatArray,
        modulationReal: FloatArray,
        modulationImag: FloatArray
    ): FloatArray {
        val count = firstFeaturePlane.size
        require(outputInterleaved.size.toLong() == count.toLong() * 2L) {
            "Complex output must contain exactly two float values per feature position."
        }
        require(secondFeaturePlane.size == count && gain.size == count &&
            modulationReal.size == count && modulationImag.size == count) {
            "All modulation inputs must have the same number of positions."
        }
        require(outputInterleaved.all { it.isFinite() } &&
            firstFeaturePlane.all { it.isFinite() } &&
            secondFeaturePlane.all { it.isFinite() } &&
            gain.all { it.isFinite() } &&
            modulationReal.all { it.isFinite() } &&
            modulationImag.all { it.isFinite() }) {
            "Complex modulation input contains a non-finite value."
        }

        for (index in 0 until count) {
            val scaledReal = gain[index] * firstFeaturePlane[index]
            val scaledImag = gain[index] * secondFeaturePlane[index]
            val modReal = modulationReal[index]
            val modImag = modulationImag[index]
            val offset = index * 2
            outputInterleaved[offset] += modReal * scaledReal - modImag * scaledImag
            outputInterleaved[offset + 1] += modImag * scaledReal + modReal * scaledImag
        }
        require(outputInterleaved.all { it.isFinite() }) {
            "Complex modulation produced a non-finite output value."
        }
        return outputInterleaved
    }

    /**
     * Applies the shared native carrier-path affine vector operation:
     *
     *   values[i] = values[i] * scale + bias
     *
     * This is the common behavior of FUN_1800cea30 and FUN_1800ceba0.
     */
    fun applyAffineInPlace(
        values: FloatArray,
        scale: Float,
        bias: Float
    ): FloatArray {
        require(scale.isFinite() && bias.isFinite()) {
            "Affine scale and bias must be finite."
        }
        require(values.all { it.isFinite() }) {
            "Affine input contains a non-finite value."
        }
        for (index in values.indices) {
            values[index] = values[index] * scale + bias
        }
        require(values.all { it.isFinite() }) {
            "Affine transform produced a non-finite output value."
        }
        return values
    }
}
